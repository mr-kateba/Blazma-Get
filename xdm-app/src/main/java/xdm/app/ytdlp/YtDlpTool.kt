package xdm.app.ytdlp

import xdm.app.AppContext
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** yt-dlp could not read the page; [message] is yt-dlp's own explanation. */
class YtDlpException(message: String) : IOException(message)

/**
 * The yt-dlp tool (github.com/yt-dlp/yt-dlp, public domain): downloaded into `<config>/tools` the
 * first time a video page is opened, checked against the release's SHA-256 list, and updated with
 * its own `-U` at most once a day, because sites like YouTube change often.
 */
object YtDlpTool {
    private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    private const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000
    private val lock = Any()

    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = os.startsWith("windows")

    private val folder get() = File(AppContext.configDir, "tools")

    val executable: File get() = File(folder, if (isWindows) "yt-dlp.exe" else "yt-dlp")

    val isInstalled: Boolean get() = executable.isFile

    /** The release file for this computer. */
    internal fun assetName(osName: String = os, arch: String = System.getProperty("os.arch").lowercase()): String {
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            osName.startsWith("windows") -> if (arm) "yt-dlp_arm64.exe" else "yt-dlp.exe"
            osName.contains("mac") -> "yt-dlp_macos"
            arm -> "yt-dlp_linux_aarch64"
            else -> "yt-dlp_linux"
        }
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
        val digest = MessageDigest.getInstance("SHA-256")
        open(RELEASE + asset).let { conn ->
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
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
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            tmp.delete()
            throw IOException("yt-dlp checksum mismatch")
        }
        executable.delete()
        if (!tmp.renameTo(executable)) throw IOException("Unable to install yt-dlp")
        executable.setExecutable(true)
        stamp().writeText(System.currentTimeMillis().toString())
        Logger.info("XDM", "yt-dlp installed")
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

    /** The qualities yt-dlp finds on [url]. Throws [IOException] (a [YtDlpException] with yt-dlp's reason). */
    fun fetchInfo(url: String): VideoInfo {
        val exe = ensure()
        updateIfStale()
        val errFile = File.createTempFile("yt-dlp", ".log")
        try {
            val cmd = listOf(
                exe, "--ignore-config", "-J", "--no-playlist", "--no-warnings", "--encoding", "utf-8",
            ) + proxyArgs() + listOf("--", url)
            val p = ProcessBuilder(cmd).redirectError(errFile).apply {
                environment()["PYTHONIOENCODING"] = "utf-8"
                environment()["PYTHONUTF8"] = "1"
            }.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(3, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                throw YtDlpException("timed out")
            }
            if (p.exitValue() != 0 || out.isBlank()) {
                val err = errFile.readText(Charsets.UTF_8)
                Logger.error("XDM", "yt-dlp -J failed for $url:\n$err")
                throw YtDlpException(
                    err.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
                        ?: err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed"
                )
            }
            return YtDlpFormats.parse(out, url)
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

    private fun fetch(url: String): ByteArray =
        open(url).inputStream.use { it.readBytes() }
}
