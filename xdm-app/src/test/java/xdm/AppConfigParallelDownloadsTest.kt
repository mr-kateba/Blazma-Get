package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** Older builds allowed one download at a time by default; that default is raised once on load. */
class AppConfigParallelDownloadsTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun load() = AppConfig(dir.absolutePath).apply { load() }

    /** Strips the trailing "default already raised" flag, as an older build wrote the config. */
    private fun dropRaisedFlag() {
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        val end = "XDM-END!".toByteArray(Charsets.US_ASCII)
        file.writeBytes(bytes.copyOf(bytes.size - end.size - 1) + end)
    }

    @Test
    fun newDefault_allowsSeveralDownloads() {
        assertEquals(4, AppConfig(dir.absolutePath).maxParallelDownloads)
    }

    @Test
    fun oldConfigWithTheOldDefault_isRaised() {
        AppConfig(dir.absolutePath).apply { maxParallelDownloads = 1 }.save()
        dropRaisedFlag()
        assertEquals(4, load().maxParallelDownloads)
    }

    @Test
    fun oldConfigWithAnotherValue_isKept() {
        AppConfig(dir.absolutePath).apply { maxParallelDownloads = 2 }.save()
        dropRaisedFlag()
        assertEquals(2, load().maxParallelDownloads)
    }

    @Test
    fun oneDownloadChosenAfterTheUpdate_isKept() {
        AppConfig(dir.absolutePath).apply { maxParallelDownloads = 1 }.save()
        assertEquals(1, load().maxParallelDownloads)
    }
}
