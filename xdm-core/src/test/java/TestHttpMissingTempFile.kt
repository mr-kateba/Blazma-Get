import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.saveState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A paused download whose temp file (or whole temp folder) was deleted. Before the fix every chunk
 * failed to open the file, the chunks restarted each other's failed ranges and the download spun
 * at "downloading" forever, tens of thousands of write attempts a minute.
 */
class TestHttpMissingTempFile : HttpDownloadTestBase() {

    private val size = 512 * 1024L
    private val half = size / 2

    /** Persists a paused two-chunk download, half of each chunk done, with no temp file on disk. */
    private fun persistWithoutTempFile(id: Long, host: TestDownloadHost, url: String, tempFolder: File) {
        fun chunk(cid: Long, offset: Long) = Chunk(
            id = cid,
            offset = offset,
            length = AtomicLong(half),
            downloaded = AtomicLong(half / 2),
            status = AtomicReference(ChunkStatus.Ready),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
        )
        val chunks = ConcurrentHashMap<Long, Chunk>()
        chunks[1L] = chunk(1L, 0)
        chunks[2L] = chunk(2L, half)
        val ctx = HttpTaskContext(
            id = id,
            chunks = chunks,
            init = AtomicBoolean(true),
            totalSize = size,
            downloaded = AtomicLong(half),
            url = url,
            contentType = null,
            headers = null,
            cookie = null,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            tempFileName = "missing-$id.tmp",
            tempFileCreated = AtomicBoolean(true),
            diskError = AtomicBoolean(false),
            downloadHost = host,
            tempFolder = tempFolder.absolutePath,
        )
        saveState(ctx, work.absolutePath)
    }

    @Test
    fun resume_tempFolderDeleted_downloadsWholeFileAgain() {
        val id = 950L
        val bytes = randomData(size.toInt(), seed = 950)
        server.register("/whole", Endpoint(bytes))
        val host = host()
        persistWithoutTempFile(id, host, server.url("/whole"), File(work, "gone"))

        newTaskForUrl(id, server.url("/whole"), host, TestConfig(maxSegments = 2)).resume()

        awaitSuccess(host, 20)
        assertDownloaded(host, bytes)
    }

    @Test
    fun diskError_failsOnceInsteadOfRetryingForever() {
        val id = 951L
        val bytes = randomData(size.toInt(), seed = 951)
        server.register("/blocked", Endpoint(bytes))
        val host = host()
        // The temp "folder" sits under a regular file, so it can never be created or written.
        val blocker = File(work, "blocker").apply { writeText("x") }
        persistWithoutTempFile(id, host, server.url("/blocked"), File(blocker, "tmp"))

        val task = newTaskForUrl(id, server.url("/blocked"), host, TestConfig(maxSegments = 8))
        task.resume()

        assertTrue("download never failed; chunks kept retrying", host.latch.await(10, TimeUnit.SECONDS))
        assertEquals(DownloadError.DiskSpaceError, host.failure)
        Thread.sleep(1000) // any chunk still running would fail again, or restart the others
        assertEquals("failure reported more than once", 1, host.failureCount.get())
    }
}
