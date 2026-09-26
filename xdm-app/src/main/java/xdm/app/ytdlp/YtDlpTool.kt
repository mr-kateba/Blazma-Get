package xdm.app.ytdlp

import xdm.app.AppContext
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** yt-dlp could not read the page; [message] is yt-dlp's own explanation. */
class YtDlpException(message: String) : IOException(message)

/**
 * The yt-dlp tool (github.com/yt-dlp/yt-dlp, public domain): downloaded into `<config>/tools` the
 * first time a video page is opened, checked against the release's SHA-256 list, and updated with
 * its own `-U` at most once a day, because sites like YouTube change often.
 *
 * Optionally also Deno (deno.com, MIT), the JavaScript runtime yt-dlp uses to unlock all of
 * YouTube's qualities: without one, yt-dlp falls back to a client that offers fewer formats.
 */
object YtDlpTool {
    private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    private const val DENO_RELEASE = "https://github.com/denoland/deno/releases/latest/download/"
    private const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000
    private val lock = Any()

    private val os = System.getProperty("os.name").lowercase()
    private val arch = System.getProperty("os.arch").lowercase()
    private val isWindows = os.startsWith("windows")
    private val isArm = arch.contains("aarch64") || arch.contains("arm64")

    private val folder get() = File(AppContext.configDir, "tools")

    val executable: File get() = File(folder, if (isWindows) "yt-dlp.exe" else "yt-dlp")
    val isInstalled: Boolean get() = executable.isFile

    val deno: File get() = File(folder, if (isWindows) "deno.exe" else "deno")
    val isDenoInstalled: Boolean get() = deno.isFile

    /** The yt-dlp release file for this computer. */
    internal fun assetName(osName: String = os, arm: Boolean = isArm): String = when {
        osName.startsWith("windows") -> if (arm) "yt-dlp_arm64.exe" else "yt-dlp.exe"
        osName.contains("mac") -> "yt-dlp_macos"
        arm -> "yt-dlp_linux_aarch64"
        else -> "yt-dlp_linux"
    }

    /** The Deno release archive for this computer (Windows on ARM runs the x64 build). */
    internal fun denoAssetName(osName: String = os, arm: Boolean = isArm): String = when {
        osName.startsWith("windows") -> "deno-x86_64-pc-windows-msvc.zip"
        osName.contains("mac") -> if (arm) "deno-aarch64-apple-darwin.zip" else "deno-x86_64-apple-darwin.zip"
        arm -> "deno-aarch64-unknown-linux-gnu.zip"
        else -> "deno-x86_64-unknown-linux-gnu.zip"
    }

    /**
     * The executable, downloading it first when missing. [progress] gets (bytes, total or -1).
     * Throws [IOException] when it cannot be downloaded or does not match its checksum.
     */
    fun ensure(progress: (Long, Long) -> Unit = { _, _ -> }): String {
        synchronized(lock) {
            if (!isInstalled) install(progress)
            return executable.absolutePath
        }
    }

    private fun install(progress: (Long, Long) -> Unit) {
        val asset = assetName()
        Logger.info("XDM", "Downloading yt-dlp ($asset)")
        folder.mkdirs()
        val sums = String(fetch(RELEASE + "SHA2-256SUMS"), Charsets.UTF_8)
        val expected = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == asset }?.get(0)?.lowercase()
            ?: throw IOException("No checksum for $asset")
        val tmp = File(folder, "$asset.download")
        downloadVerified(RELEASE + asset, expected, tmp, progress)
        executable.delete()
        if (!tmp.renameTo(executable)) throw IOException("Unable to install yt-dlp")
        executable.setExecutable(true)
        stamp().writeText(System.currentTimeMillis().toString())
        Logger.info("XDM", "yt-dlp installed")
    }

    /** Downloads and unpacks Deno next to yt-dlp. Throws [IOException] on failure or checksum mismatch. */
    fun installDeno(progress: (Long, Long) -> Unit = { _, _ -> }) {
        synchronized(lock) {
            if (isDenoInstalled) return
            val asset = denoAssetName()
            Logger.info("XDM", "Downloading Deno ($asset)")
            folder.mkdirs()
            // The Windows file is PowerShell's Get-FileHash output, the others "hash  name": take the hash.
            val expected = Regex("\\b[0-9a-fA-F]{64}\\b")
                .find(String(fetch("$DENO_RELEASE$asset.sha256sum"), Charsets.UTF_8))?.value?.lowercase()
                ?: throw IOException("No checksum for $asset")
            val zip = File(folder, "$asset.download")
            try {
                downloadVerified(DENO_RELEASE + asset, expected, zip, progress)
                val tmp = File(folder, deno.name + ".download")
                ZipInputStream(zip.inputStream().buffered()).use { z ->
                    generateSequence { z.nextEntry }.firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == deno.name }
                        ?: throw IOException("No ${deno.name} in $asset")
                    tmp.outputStream().use { z.copyTo(it) }
                }
                deno.delete()
                if (!tmp.renameTo(deno)) throw IOException("Unable to install Deno")
                deno.setExecutable(true)
                Logger.info("XDM", "Deno installed")
            } finally {
                zip.delete()
            }
        }
    }

    /** Streams [url] into [target] while hashing it; deletes it and throws if it is not [sha256]. */
    private fun downloadVerified(url: String, sha256: String, target: File, progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(url)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            target.delete()
            throw IOException("Checksum mismatch for $url")
        }
    }

    /** Runs yt-dlp's self-update when the last check is more than a day old. Never throws. */
    fun updateIfStale() {
        synchronized(lock) {
            if (!isInstalled) return
            val last = runCatching { stamp().readText().trim().toLong() }.getOrDefault(0L)
            if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
            stamp().writeText(System.currentTimeMillis().toString())
            runCatching {
                val p = ProcessBuilder(listOf(executable.absolutePath, "-U") + proxyArgs())
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(2, TimeUnit.MINUTES)
                Logger.info("XDM", "yt-dlp update: ${out.trim().lines().lastOrNull()}")
            }.onFailure { Logger.error("XDM", "yt-dlp update failed", it) }
        }
    }

    private fun stamp() = File(folder, "yt-dlp.checked")

    /** Points yt-dlp at Deno when it is installed (downloads get this; they set the proxy themselves). */
    fun denoArgs(): List<String> =
        if (isDenoInstalled) listOf("--js-runtimes", "deno:" + deno.absolutePath) else emptyList()

    private fun commonArgs(): List<String> = proxyArgs() + denoArgs()

    /** The qualities yt-dlp finds on [url]. Throws [IOException] (a [YtDlpException] with yt-dlp's reason). */
    fun fetchInfo(url: String): VideoInfo {
        val (out, err) = runJson(listOf("--no-playlist"), url)
        return YtDlpFormats.parse(out, url).copy(jsRuntimeMissing = err.contains("JavaScript runtime", ignoreCase = true))
    }

    /** The videos of a playlist (titles and addresses only, which is fast even for long lists). */
    fun fetchPlaylist(url: String): PlaylistInfo = YtDlpFormats.parsePlaylist(runJson(listOf("--flat-playlist", "--yes-playlist"), url).first)

    /** Runs `yt-dlp -J` and returns (stdout JSON, stderr). */
    private fun runJson(options: List<String>, url: String): Pair<String, String> {
        val exe = ensure()
        updateIfStale()
        val errFile = File.createTempFile("yt-dlp", ".log")
        try {
            val cmd = listOf(exe, "--ignore-config", "-J", "--encoding", "utf-8") + options + commonArgs() + listOf("--", url)
            val p = ProcessBuilder(cmd).redirectError(errFile).apply {
                environment()["PYTHONIOENCODING"] = "utf-8"
                environment()["PYTHONUTF8"] = "1"
            }.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(3, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                throw YtDlpException("timed out")
            }
            val err = errFile.readText(Charsets.UTF_8)
            if (p.exitValue() != 0 || out.isBlank()) {
                Logger.error("XDM", "yt-dlp -J failed for $url:\n$err")
                throw YtDlpException(
                    err.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
                        ?: err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed"
                )
            }
            return out to err
        } finally {
            errFile.delete()
        }
    }

    private fun proxyArgs(): List<String> {
        val c = AppContext.config
        if (!c.useProxy || c.proxyHost.isBlank()) return emptyList()
        val scheme = if (c.socksProxy) "socks5" else "http"
        val auth = if (c.proxyUser.isNotBlank()) {
            java.net.URLEncoder.encode(c.proxyUser, "UTF-8") + ":" + java.net.URLEncoder.encode(c.proxyPass, "UTF-8") + "@"
        } else ""
        return listOf("--proxy", "$scheme://$auth${c.proxyHost}:${c.proxyPort}")
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val conn = (AppContext.config.toProxy()?.let { u.openConnection(it) } ?: u.openConnection()) as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Blazma-Get")
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
        return conn
    }

    private fun fetch(url: String): ByteArray = open(url).inputStream.use { it.readBytes() }
}
