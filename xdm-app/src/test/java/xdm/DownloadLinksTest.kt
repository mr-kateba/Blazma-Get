package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.DownloadLinks
import xdm.app.utils.FileChecksum
import java.io.File

class DownloadLinksTest {
    private val types = listOf("zip", "EXE", ".mp4", "iso")

    @Test
    fun extractsLinksFromText() {
        val text = "حمّل من هنا: https://cdn.example.com/game.zip, أو (http://mirror.example.org/a/b.exe)."
        assertEquals(
            listOf("https://cdn.example.com/game.zip", "http://mirror.example.org/a/b.exe"),
            DownloadLinks.extract(text)
        )
    }

    @Test
    fun ignoresTextWithoutLinks() {
        assertTrue(DownloadLinks.extract("hello world").isEmpty())
    }

    @Test
    fun recognisesFileLinksIgnoringCaseAndQuery() {
        assertTrue(DownloadLinks.looksLikeDownload("https://x.com/files/Setup.EXE?token=1", types))
        assertTrue(DownloadLinks.looksLikeDownload("https://x.com/movie.mp4#t=10", types))
        assertTrue(DownloadLinks.looksLikeDownload("ftp://x.com/ubuntu.iso", types))
    }

    @Test
    fun webPagesAreNotDownloads() {
        assertFalse(DownloadLinks.looksLikeDownload("https://x.com/", types))
        assertFalse(DownloadLinks.looksLikeDownload("https://x.com/watch?v=abc", types))
        assertFalse(DownloadLinks.looksLikeDownload("https://x.com/page.html", types))
        assertFalse(DownloadLinks.looksLikeDownload("https://x.com/zip", types))
    }

    @Test
    fun sha256MatchesKnownValue() {
        val f = File.createTempFile("blazma", ".txt").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileChecksum.sha256(f))
    }
}
