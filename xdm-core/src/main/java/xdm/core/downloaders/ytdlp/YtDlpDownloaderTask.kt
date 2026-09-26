package xdm.core.downloaders.ytdlp

import xdm.core.CoreConfig
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.YtDlpDownloadTaskInfo
import xdm.core.media.muxer.Muxer
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a video page with the yt-dlp tool, which knows how YouTube and 1000+ other sites serve
 * their media. Each requested format is fetched by its own yt-dlp run into the task's temp folder
 * (yt-dlp resumes its `.part` files, so pause and resume work); two formats (video + audio) are
 * then merged into one MP4 by the in-house transmuxer, so ffmpeg is never needed.
 *
 * [toolPath] returns the yt-dlp executable, downloading it first if needed; it may throw.
 */
class YtDlpDownloaderTask(
    private val task: YtDlpDownloadTaskInfo,
    private val host: DownloadHost,
    private val toolPath: () -> String,
    private val muxer: Muxer,
    private val config: CoreConfig,
) : DownloaderTask {
    private val stopFlag = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    private val tempDir = File(task.tempDir)
    private val totals = LongArray(task.formatIds.size) { -1 }
    private val downloaded = LongArray(task.formatIds.size)
    private var lastReport = 0L
    private var reportedTotal = -1L

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({ run() }, "yt-dlp-${task.id}").start()
    }

    override fun resume() = start()

    override fun stop() {
        if (!stopFlag.compareAndSet(false, true)) return
        Thread {
            muxer.stop()
            process?.let { killTree(it) }
            host.onDownloadPaused(task.id, PauseEvent.PausedByUser)
        }.start()
    }

    override fun deleteTemp() {
        FileUtils.deleteFolder(tempDir.absolutePath)
    }

    private fun run() {
        try {
            host.onDownloadActivated(task.id)
            host.onDownloadInit(
                DownloadStatusInfo.InitInfo(
                    id = task.id, url = task.pageUrl, isRedirect = false, fileSize = task.expectedSize,
                    contentDisposition = null, contentType = null,
                ),
                DownloadType.YtDlp
            )
            if (!tempDir.isDirectory && !tempDir.mkdirs()) {
                host.onDownloadFailed(task.id, DownloadError.DiskSpaceError)
                return
            }
            val tool = try {
                toolPath()
            } catch (e: Exception) {
                Logger.error("XDM", "yt-dlp is not available", e)
                if (!stopFlag.get()) host.onDownloadFailed(task.id, DownloadError.NetworkError)
                return
            }
            val files = ArrayList<File>()
            for ((index, formatId) in task.formatIds.withIndex()) {
                if (stopFlag.get()) return
                val file = finishedFile(index) ?: when (val result = fetch(tool, index, formatId)) {
                    is Fetch.Done -> result.file.also { markFinished(index, it) }
                    is Fetch.Failed -> {
                        if (!stopFlag.get()) host.onDownloadFailed(task.id, result.error)
                        return
                    }
                }
                totals[index] = file.length()
                downloaded[index] = file.length()
                files += file
            }
            if (stopFlag.get()) return
            val output = assemble(files) ?: return
            if (stopFlag.get()) return
            when (val res = host.commitOutputFile(task.id, output.absolutePath, DownloadType.YtDlp)) {
                is CommitResult.Failed -> if (!stopFlag.get()) host.onDownloadFailed(task.id, res.error)
                is CommitResult.Success -> {
                    deleteTemp()
                    host.onDownloadSuccess(
                        DownloadStatusInfo.FinalInfo(
                            task.id, File(res.outputDir, res.fileName).length(), res.fileName, res.outputDir
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.error("XDM", "yt-dlp download ${task.id} failed", e)
            if (!stopFlag.get()) host.onDownloadFailed(task.id, DownloadError.InternalError)
        }
    }

    /**
     * Two formats are video + audio, merged into MP4. A single file is committed as it is, unless it
     * is MPEG-TS (what yt-dlp's own HLS downloader writes without ffmpeg): that is rewrapped as MP4 so
     * it plays everywhere.
     */
    private fun assemble(files: List<File>): File? {
        val needsMux = files.size == 2 || (files.size == 1 && isMpegTs(files[0]) && task.outputExt == "mp4")
        if (!needsMux) return files.single()
        host.onAssembleStart(task.id)
        val out = host.outputFilePath(task.id, DownloadType.YtDlp, ".${task.outputExt}")?.let { File(it) }
            ?: File(tempDir, "${task.id}.${task.outputExt}")
        out.absoluteFile.parentFile.mkdirs()
        FileUtils.hideFile(out)
        val progress = { p: Int -> host.onAssembleProgress(DownloadStatusInfo.AssembleInfo(task.id, p)) }
        val ok = try {
            if (files.size == 2) {
                muxer.mux(files[0].absolutePath, files[1].absolutePath, out.absolutePath, progress, tempDir.absolutePath)
            } else {
                muxer.mux(listOf(files[0].absolutePath), out.absolutePath, progress, tempDir.absolutePath, false, false)
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Merging yt-dlp formats failed", e)
            false
        }
        if (stopFlag.get()) return null
        if (!ok) {
            host.onDownloadFailed(task.id, DownloadError.MuxError)
            return null
        }
        return out
    }

    private sealed interface Fetch {
        data class Done(val file: File) : Fetch
        data class Failed(val error: DownloadError) : Fetch
    }

    /** Runs yt-dlp for one format, reporting its progress, and returns the file it wrote. */
    private fun fetch(tool: String, index: Int, formatId: String): Fetch {
        val command = buildCommand(tool, index, formatId)
        Logger.info("XDM", "yt-dlp ${task.id}: format $formatId")
        val proc = try {
            ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["PYTHONIOENCODING"] = "utf-8"
                environment()["PYTHONUTF8"] = "1"
            }.start()
        } catch (e: IOException) {
            Logger.error("XDM", "Unable to start yt-dlp", e)
            return Fetch.Failed(DownloadError.InternalError)
        }
        process = proc
        if (stopFlag.get()) killTree(proc)
        var outFile: File? = null
        val recent = ArrayDeque<String>()
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith(PROGRESS) -> onProgress(index, line.removePrefix(PROGRESS))
                    line.startsWith(FILE) -> outFile = File(line.removePrefix(FILE).trim())
                    else -> {
                        recent.addLast(line)
                        if (recent.size > 40) recent.removeFirst()
                    }
                }
            }
        }
        val exit = proc.waitFor()
        process = null
        if (stopFlag.get()) return Fetch.Failed(DownloadError.Cancelled)
        val file = outFile
        if (exit == 0 && file != null && file.isFile) return Fetch.Done(file)
        Logger.error("XDM", "yt-dlp exited with $exit:\n" + recent.joinToString("\n"))
        return Fetch.Failed(classifyError(recent))
    }

    internal fun buildCommand(tool: String, index: Int, formatId: String): List<String> {
        val cmd = mutableListOf(
            tool,
            "--ignore-config", "--no-playlist", "--newline", "--no-colors", "--encoding", "utf-8",
            "--no-quiet", "--progress", "--continue", "--no-mtime", "--fixup", "never",
            "--concurrent-fragments", "4",
            "-f", formatId,
            "-o", File(tempDir, "part-$index.%(ext)s").absolutePath,
            "--progress-template", "download:$PROGRESS%(progress.status)s %(progress.downloaded_bytes)s " +
                "%(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s %(progress.eta)s",
            "--print", "after_move:$FILE%(filepath)s",
        )
        if (host.applySpeedLimit && host.speedLimit > 0) {
            cmd += listOf("--limit-rate", "${host.speedLimit}K")
        }
        proxyUrl()?.let { cmd += listOf("--proxy", it) }
        task.cookie?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--add-header", "Cookie:$it") }
        // "--" so a page address can never be read as an option.
        cmd += listOf("--", task.pageUrl)
        return cmd
    }

    private fun proxyUrl(): String? {
        if (!config.useProxy || config.proxyHost.isBlank()) return null
        val scheme = if (config.socksProxy) "socks5" else "http"
        val auth = if (config.proxyUser.isNotBlank()) {
            "${enc(config.proxyUser)}:${enc(config.proxyPass)}@"
        } else ""
        return "$scheme://$auth${config.proxyHost}:${config.proxyPort}"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** `status downloaded total estimate speed eta`, "NA" for unknown values. */
    private fun onProgress(index: Int, fields: String) {
        val parts = fields.trim().split(' ')
        if (parts.size < 6) return
        val done = parts[1].toDoubleOrNull()?.toLong() ?: return
        val total = parts[2].toDoubleOrNull()?.toLong() ?: parts[3].toDoubleOrNull()?.toLong()
        downloaded[index] = done
        if (total != null && total > 0) totals[index] = total
        val now = System.currentTimeMillis()
        if (now - lastReport < 400 && parts[0] != "finished") return
        lastReport = now
        val all = downloaded.sum()
        // Sizes of later formats are only known once yt-dlp reaches them: until then use the size
        // the page reported, or what is known so far, rather than showing no total at all.
        val allTotal = when {
            totals.all { it > 0 } -> totals.sum()
            task.expectedSize != null -> task.expectedSize!!
            else -> totals.filter { it > 0 }.sum().takeIf { it > 0 } ?: -1
        }
        if (allTotal > 0 && allTotal != reportedTotal) {
            reportedTotal = allTotal
            host.onDownloadInit(
                DownloadStatusInfo.InitInfo(task.id, task.pageUrl, false, allTotal, null, null), DownloadType.YtDlp
            )
        }
        val speed = parts[4].toDoubleOrNull()?.toFloat() ?: 0f
        val remaining = if (allTotal > 0) allTotal - all else -1
        host.onDownloadProgress(
            DownloadStatusInfo.ProgressInfo(
                id = task.id,
                progress = if (allTotal > 0) (all * 100 / allTotal).toInt().coerceIn(0, 100) else 0,
                speed = speed,
                eta = if (speed > 0 && remaining > 0) (remaining / speed).toLong() else 0,
                downloaded = all,
            )
        )
    }

    /** A format finished in an earlier run (the download was paused while fetching the next one). */
    private fun finishedFile(index: Int): File? =
        File(tempDir, "done-$index").takeIf { it.isFile }
            ?.let { runCatching { File(it.readText().trim()) }.getOrNull() }
            ?.takeIf { it.isFile }

    private fun markFinished(index: Int, file: File) {
        runCatching { File(tempDir, "done-$index").writeText(file.absolutePath) }
    }

    companion object {
        private const val PROGRESS = "BLAZMA-PROGRESS "
        private const val FILE = "BLAZMA-FILE "

        /** Maps yt-dlp's last output lines to the closest download error. */
        internal fun classifyError(lines: Collection<String>): DownloadError {
            val text = lines.joinToString("\n")
            fun has(vararg s: String) = s.any { text.contains(it, ignoreCase = true) }
            return when {
                has("No space left", "Errno 28", "disk full") -> DownloadError.DiskSpaceError
                has("HTTP Error 403", "HTTP Error 410", "Sign in to confirm", "expired") -> DownloadError.SessionExpired
                has(
                    "Unable to download", "timed out", "Connection", "getaddrinfo", "Name or service not known",
                    "Network is unreachable", "Temporary failure", "HTTP Error 5", "IncompleteRead",
                    "Remote end closed", "urlopen error",
                ) -> DownloadError.NetworkError
                else -> DownloadError.InvalidResponse
            }
        }

        /** True for an MPEG transport stream (sync byte 0x47 every 188 bytes). */
        internal fun isMpegTs(file: File): Boolean = runCatching {
            file.inputStream().use { s ->
                val b = ByteArray(189)
                s.read(b) == 189 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte()
            }
        }.getOrDefault(false)

        /** Stops yt-dlp and the helper processes it started (the Windows build unpacks itself). */
        internal fun killTree(p: Process) {
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            p.destroyForcibly()
        }
    }
}
