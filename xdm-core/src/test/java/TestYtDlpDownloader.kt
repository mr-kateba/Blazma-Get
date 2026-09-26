import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.YtDlpDownloadTaskInfo
import xdm.core.downloaders.ytdlp.YtDlpDownloaderTask
import xdm.core.media.muxer.Muxer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The yt-dlp downloader against a fake yt-dlp (a shell script speaking the same progress lines), so
 * no network or real tool is needed: formats are fetched one by one, two are merged by the muxer,
 * one is committed as is, errors are classified, and pause kills the tool without a failure.
 */
class TestYtDlpDownloader {
    private lateinit var work: File
    private lateinit var tmp: File
    private lateinit var out: File

    @Before
    fun setup() {
        assumeTrue("needs a POSIX shell", File("/bin/bash").canExecute())
        work = Files.createTempDirectory("ytdlp-test").toFile()
        tmp = File(work, "tmp").apply { mkdirs() }
        out = File(work, "out").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::work.isInitialized) work.deleteRecursively()
    }

    /** A fake yt-dlp: writes `data-<format>` to the -o template ("a*" formats are m4a). */
    private fun fakeTool(body: String = ""): String {
        val script = File(work, "yt-dlp")
        script.writeText(
            """
            #!/bin/bash
            out=""; fmt=""
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in -o) out="${'$'}2"; shift;; -f) fmt="${'$'}2"; shift;; esac; shift
            done
            ext=mp4; [[ "${'$'}fmt" == a* ]] && ext=m4a
            file="${'$'}{out/\%(ext)s/${'$'}ext}"
            $body
            echo "[youtube] extracting"
            echo "BLAZMA-PROGRESS downloading 500 1000 NA 1000.0 1"
            printf 'data-%s' "${'$'}fmt" > "${'$'}file"
            echo "BLAZMA-PROGRESS finished 1000 1000 NA 1000.0 NA"
            echo "BLAZMA-FILE ${'$'}file"
            """.trimIndent()
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun task(formats: List<String>, ext: String = "mp4") = YtDlpDownloadTaskInfo(
        id = 42, fileName = "video.$ext", tempDir = tmp.absolutePath, respectFileName = true,
        cookie = null, headers = null, origin = "https://www.youtube.com/watch?v=x", autoCategorize = false,
        defaultDownloadFolder = out.absolutePath, userSelectedDownloadFolder = null, maxPiece = 1,
        authInfo = null, pageUrl = "https://www.youtube.com/watch?v=x", formatIds = formats,
        outputExt = ext, expectedSize = 2000,
    )

    /** Records the merge and writes both inputs, video first, into the output. */
    private class StubMuxer : Muxer {
        var merged: Pair<String, String>? = null
        override fun mux(
            segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String,
            independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
        ) = false

        override fun mux(
            file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String,
        ): Boolean {
            merged = file1 to file2
            File(outputFile).writeText(File(file1).readText() + "+" + File(file2).readText())
            progressCallback(100)
            return true
        }

        override fun mux(
            audioSegments: List<String>, videoSegments: List<String>, outputFile: String,
            progressCallback: (Int) -> Unit, tempDir: String, independentSegement: Boolean, isMp4: Boolean,
            discontinuous: Boolean,
        ) = false

        override fun stop() {}
    }

    private fun host() = TestDownloadHost(work.absolutePath, tmp.absolutePath, out.absolutePath)

    @Test
    fun videoAndAudio_areFetchedThenMergedIntoOneFile() {
        val host = host()
        val muxer = StubMuxer()
        YtDlpDownloaderTask(task(listOf("137", "a140")), host, { fakeTool() }, muxer, TestConfig(1)).start()

        assertTrue("download did not finish", host.latch.await(20, TimeUnit.SECONDS))
        assertNull("unexpected failure ${host.failure}", host.failure)
        assertNotNull(muxer.merged)
        assertTrue(muxer.merged!!.first.endsWith("part-0.mp4"))
        assertTrue(muxer.merged!!.second.endsWith("part-1.m4a"))
        assertEquals("data-137+data-a140", host.finalFile!!.readText())
        assertFalse("temp folder left behind", tmp.exists())
    }

    @Test
    fun singleFormat_isCommittedWithoutMerging() {
        val host = host()
        val muxer = StubMuxer()
        YtDlpDownloaderTask(task(listOf("a140"), "m4a"), host, { fakeTool() }, muxer, TestConfig(1)).start()

        assertTrue(host.latch.await(20, TimeUnit.SECONDS))
        assertNull(host.failure)
        assertNull("single file must not be merged", muxer.merged)
        assertEquals("data-a140", host.finalFile!!.readText())
    }

    @Test
    fun toolError_isReportedWithTheMatchingError() {
        val host = host()
        val tool = fakeTool("echo 'ERROR: [youtube] x: HTTP Error 403: Forbidden'; exit 1")
        YtDlpDownloaderTask(task(listOf("137", "a140")), host, { tool }, StubMuxer(), TestConfig(1)).start()

        assertTrue(host.latch.await(20, TimeUnit.SECONDS))
        assertEquals(DownloadError.SessionExpired, host.failure)
    }

    @Test
    fun missingTool_failsAsNetworkErrorSoItIsRetried() {
        val host = host()
        YtDlpDownloaderTask(task(listOf("18")), host, { throw java.io.IOException("offline") }, StubMuxer(), TestConfig(1))
            .start()

        assertTrue(host.latch.await(20, TimeUnit.SECONDS))
        assertEquals(DownloadError.NetworkError, host.failure)
    }

    @Test
    fun stop_killsTheToolAndPausesWithoutFailing() {
        val host = host()
        val marker = File(work, "running")
        val tool = fakeTool("touch '${marker.absolutePath}'; sleep 60")
        val t = YtDlpDownloaderTask(task(listOf("137", "a140")), host, { tool }, StubMuxer(), TestConfig(1))
        t.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (!marker.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("fake tool never started", marker.exists())

        t.stop()
        assertTrue("stop did not pause", host.pauseLatch.await(10, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertNull("a stopped download must not report a failure", host.failure)
        assertEquals(1L, host.latch.count)
    }

    @Test
    fun command_endsWithThePageAfterDoubleDash_andCarriesTheSpeedLimit() {
        val host = object : xdm.core.downloaders.DownloadHost by host() {
            override val applySpeedLimit = true
            override val speedLimit = 512
        }
        val cmd = YtDlpDownloaderTask(task(listOf("137", "a140")), host, { "yt-dlp" }, StubMuxer(), TestConfig(1))
            .buildCommand("yt-dlp", 1, "a140")
        assertEquals(listOf("--", "https://www.youtube.com/watch?v=x"), cmd.takeLast(2))
        assertEquals("512K", cmd[cmd.indexOf("--limit-rate") + 1])
        assertEquals("a140", cmd[cmd.indexOf("-f") + 1])
        assertTrue(cmd[cmd.indexOf("-o") + 1].endsWith("part-1.%(ext)s"))
    }

    @Test
    fun errors_areClassified() {
        fun c(line: String) = YtDlpDownloaderTask.classifyError(listOf(line))
        assertEquals(DownloadError.NetworkError, c("ERROR: Unable to download webpage: <urlopen error timed out>"))
        assertEquals(DownloadError.SessionExpired, c("ERROR: [youtube] x: Sign in to confirm you're not a bot"))
        assertEquals(DownloadError.DiskSpaceError, c("OSError: [Errno 28] No space left on device"))
        assertEquals(DownloadError.InvalidResponse, c("ERROR: [youtube] x: Video unavailable"))
    }
}
