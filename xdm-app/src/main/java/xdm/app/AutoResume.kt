package xdm.app

import xdm.core.util.Logger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Resumes downloads that failed because the connection dropped, once the network is back.
 *
 * The engine already retries a broken connection a few times ([IAppConfig.maxRetries]); when those
 * run out the download fails with a network error. [DownloadManager] hands such downloads to
 * [watch], and a background thread probes the download's server (or the proxy, when one is set)
 * with a plain TCP connect. As soon as it answers, the download is resumed from where it stopped.
 *
 * Each download gets at most [MAX_ATTEMPTS] automatic resumes in a row, with a growing wait
 * between probes, so a server that accepts connections but keeps dropping them is not hammered
 * forever. Pausing, resuming or deleting a download by hand ends its watch ([forget]).
 */
class AutoResume(
    private val resume: (Long) -> Unit,
    private val urlOf: (Long) -> String?,
    private val enabled: () -> Boolean = { AppContext.config.autoResumeOnReconnect },
    private val probe: (host: String, port: Int) -> Boolean = ::canConnect,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Probe from a background thread; tests turn it off and call [checkNow] themselves. */
    private val background: Boolean = true,
    /** Where the waiting downloads are remembered across restarts (null: not remembered). */
    private val store: java.io.File? = null,
) {
    private class Watch(val attempts: Int, val nextCheckAt: Long)

    private val watches = ConcurrentHashMap<Long, Watch>()

    @Volatile
    private var thread: Thread? = null

    /** Number of downloads currently waiting for the network. */
    val waitingCount: Int
        get() = watches.size

    fun isWaiting(id: Long) = watches.containsKey(id)

    /** Starts watching [id], which just failed with a network error. */
    fun watch(id: Long) {
        if (!enabled()) return
        val attempts = (watches[id]?.attempts ?: attemptsSoFar[id] ?: 0) + 1
        if (attempts > MAX_ATTEMPTS) {
            Logger.info("AutoResume", "Giving up on $id after $MAX_ATTEMPTS automatic resumes")
            forget(id)
            return
        }
        attemptsSoFar[id] = attempts
        watches[id] = Watch(attempts, clock() + delayFor(attempts))
        Logger.info("AutoResume", "Download $id lost its connection, waiting for the network (attempt $attempts)")
        persist()
        if (background) ensureThread()
    }

    /**
     * After a restart, keeps waiting for the downloads that were waiting when the app closed
     * (the internet was still down), as long as they are still paused.
     */
    fun restore(stillPaused: (Long) -> Boolean) {
        val ids = runCatching { store?.readLines()?.mapNotNull { it.trim().toLongOrNull() } }.getOrNull() ?: return
        ids.filter(stillPaused).forEach { watch(it) }
        persist()
    }

    private fun persist() {
        val file = store ?: return
        runCatching { file.writeText(watches.keys.joinToString("\n")) }
            .onFailure { Logger.error("AutoResume", "Unable to save $file", it) }
    }

    /** Stops watching [id] and resets its attempt count (the user took over, or it finished). */
    fun forget(id: Long) {
        val wasWatched = watches.remove(id) != null
        attemptsSoFar.remove(id)
        if (wasWatched) persist()
    }

    /** One probe round; resumes every due download whose server is reachable again. */
    fun checkNow() {
        if (!enabled()) {
            watches.clear()
            persist()
            return
        }
        val before = watches.size
        val now = clock()
        for ((id, w) in watches.entries.toList()) {
            if (w.nextCheckAt > now) continue
            val target = probeTarget(id)
            if (target == null) {
                watches.remove(id)
                continue
            }
            if (probe(target.first, target.second)) {
                watches.remove(id)
                Logger.info("AutoResume", "Network is back for $id, resuming")
                resume(id)
            } else {
                watches[id] = Watch(w.attempts, now + PROBE_INTERVAL_MS)
            }
        }
        if (watches.size != before) persist()
    }

    /** Attempts are remembered after a resume so a download that keeps failing still gives up. */
    private val attemptsSoFar = ConcurrentHashMap<Long, Int>()

    private fun probeTarget(id: Long): Pair<String, Int>? {
        val config = AppContext.config
        if (config.useProxy && config.proxyHost.isNotBlank()) return config.proxyHost to config.proxyPort
        val uri = urlOf(id)?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val host = uri.host ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> 443
        }
        return host to port
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        synchronized(this) {
            if (thread?.isAlive == true) return
            thread = Thread {
                while (watches.isNotEmpty()) {
                    try {
                        Thread.sleep(TICK_MS)
                        checkNow()
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (e: Exception) {
                        Logger.error(e)
                    }
                }
            }.apply {
                name = "auto-resume"
                isDaemon = true
                start()
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 30
        private const val TICK_MS = 2_000L
        private const val PROBE_INTERVAL_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 4_000

        /** First probe after 5 s, then a little later each time, capped at 2 minutes. */
        fun delayFor(attempt: Int): Long = (5_000L * attempt).coerceAtMost(120_000L)

        fun canConnect(host: String, port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
