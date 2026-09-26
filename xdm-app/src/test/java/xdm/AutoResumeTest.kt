package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.AutoResume
import java.nio.file.Files

class AutoResumeTest {
    private var now = 1_000_000L
    private var online = false
    private var enabled = true
    private val resumed = mutableListOf<Long>()
    private val probed = mutableListOf<Pair<String, Int>>()
    private lateinit var auto: AutoResume

    @Before
    fun setup() {
        AppContext.config = AppConfig(Files.createTempDirectory("xdm-auto-resume").toFile().absolutePath)
        auto = AutoResume(
            resume = { resumed.add(it) },
            urlOf = { id -> if (id == 404L) null else "https://files.example.com/big-$id.iso" },
            enabled = { enabled },
            probe = { host, port -> probed.add(host to port); online },
            clock = { now },
            background = false,
        )
    }

    @Test
    fun resumesOnceTheServerIsReachableAgain() {
        auto.watch(1)
        assertTrue(auto.isWaiting(1))

        now += AutoResume.delayFor(1)
        auto.checkNow()
        assertEquals(listOf("files.example.com" to 443), probed)
        assertTrue("still offline, nothing resumed", resumed.isEmpty())

        online = true
        now += 60_000
        auto.checkNow()
        assertEquals(listOf(1L), resumed)
        assertFalse(auto.isWaiting(1))
    }

    @Test
    fun doesNotProbeBeforeTheDelay() {
        online = true
        auto.watch(2)
        auto.checkNow()
        assertTrue(probed.isEmpty())
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun forgetStopsTheWatch() {
        online = true
        auto.watch(3)
        auto.forget(3)
        now += 600_000
        auto.checkNow()
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun disabledSettingWatchesNothing() {
        enabled = false
        auto.watch(4)
        assertFalse(auto.isWaiting(4))
    }

    @Test
    fun givesUpAfterTooManyAttempts() {
        online = true
        repeat(AutoResume.MAX_ATTEMPTS) {
            auto.watch(5)
            now += 600_000
            auto.checkNow()
        }
        assertEquals(AutoResume.MAX_ATTEMPTS, resumed.size)
        auto.watch(5)
        assertFalse("the attempt after the limit is not watched", auto.isWaiting(5))
    }

    @Test
    fun waitingDownloadsSurviveARestart() {
        val store = Files.createTempFile("auto-resume", ".txt").toFile()
        fun make() = AutoResume(
            resume = { resumed.add(it) }, urlOf = { "https://files.example.com/$it.iso" },
            enabled = { true }, probe = { _, _ -> online }, clock = { now }, background = false, store = store,
        )
        make().apply { watch(7); watch(8); forget(8) }

        val afterRestart = make()
        afterRestart.restore { id -> id != 9L }
        assertTrue(afterRestart.isWaiting(7))
        assertFalse("forgotten before the restart", afterRestart.isWaiting(8))

        online = true
        now += 600_000
        afterRestart.checkNow()
        assertEquals(listOf(7L), resumed)
        assertTrue("nothing left to restore", store.readText().isBlank())
    }

    @Test
    fun downloadWithoutAddressIsDropped() {
        auto.watch(404)
        now += 600_000
        auto.checkNow()
        assertFalse(auto.isWaiting(404))
        assertTrue(resumed.isEmpty())
    }
}
