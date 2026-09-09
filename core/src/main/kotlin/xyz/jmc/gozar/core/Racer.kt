package xyz.jmc.gozar.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope

/**
 * Starts several engines at once and keeps the first one that proves it carries
 * traffic.
 *
 * Two things here are worth more than the rest of the app put together:
 *
 * Starting is not connecting. An engine can bring up a local proxy and still be
 * dead on the wire, which is how apps end up saying "connected" while nothing
 * loads. Nothing wins here until a probe has come back through it.
 *
 * A second engine is held warm behind the winner. That is the whole reason a
 * drop feels like a hiccup instead of a reconnect: there is already a live
 * tunnel to move to.
 */
class Racer(
    private val engines: List<Engine>,
    private val board: Scoreboard,
    private val prober: Prober,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    private class Runner(val engine: Engine, val session: Session, val tookMs: Long)

    private val lock = Mutex()
    private var active: Runner? = null
    private var standby: Runner? = null
    private var watcher: Job? = null
    private var stopping = false

    companion object {
        /**
         * Gap between launches. Firing everything at once floods a weak mobile
         * link and makes every engine look slower than it is, so they go off in
         * a ladder instead.
         */
        const val LAUNCH_STAGGER_MS = 1_200L

        /** How long a single engine gets before it is written off this round. */
        const val ENGINE_DEADLINE_MS = 25_000L

        /** How often the live tunnel is rechecked. */
        const val HEALTH_EVERY_MS = 15_000L

        /** Failed probes in a row before the active engine is abandoned. */
        const val HEALTH_TOLERANCE = 2

        /** A win older than this stops counting as recent. */
        const val RECENT_WIN_MS = 72L * 60 * 60 * 1000
    }

    /**
     * Brings up a tunnel and returns once traffic is flowing, or null if
     * nothing worked.
     *
     * Order comes from the scoreboard, so on any launch after the first the
     * engine that worked here last time goes off the line first and this
     * usually finishes in one round trip rather than a race.
     */
    suspend fun connect(network: NetworkId): Session? {
        val order = board.order(network, engines).filter { engine ->
            val skip = engine.needsBootstrap && !board.isBootstrapped(engine.name)
            if (skip) log("skipping ${engine.name}, nothing to bootstrap from yet")
            !skip
        }
        if (order.isEmpty()) return null

        val finished = Channel<Runner?>(capacity = order.size)

        coroutineScope {
            order.forEachIndexed { index, engine ->
                launch {
                    delay(index * LAUNCH_STAGGER_MS)

                    val startedAt = clock()
                    val runner = withTimeoutOrNull(ENGINE_DEADLINE_MS) {
                        val session = try {
                            engine.start()
                        } catch (e: Exception) {
                            log("${engine.name} failed to start: ${e.message}")
                            return@withTimeoutOrNull null
                        }

                        // Up is not the same as working.
                        if (!prober.through(session.socksPort)) {
                            log("${engine.name} started but carried nothing")
                            engine.stop()
                            return@withTimeoutOrNull null
                        }

                        Runner(engine, session, clock() - startedAt)
                    }

                    if (runner == null) {
                        engine.stop()
                        board.record(network, engine.name, ok = false, tookMs = 0)
                    } else {
                        board.record(network, engine.name, ok = true, tookMs = runner.tookMs)
                    }
                    finished.send(runner)
                }
            }

            var seen = 0
            var winner: Runner? = null

            while (seen < order.size) {
                val runner = finished.receive()
                seen++
                if (runner == null) continue

                if (winner == null) {
                    winner = runner
                    log("up on ${runner.engine.name} in ${runner.tookMs}ms")
                    continue
                }

                // Someone else came up after we already had a winner. Keep it
                // warm only if it fails differently — a standby of the same
                // shape dies alongside the thing it is meant to replace.
                val wanted = lock.withLock {
                    standby == null && runner.engine.shape != winner!!.engine.shape
                }
                if (wanted) {
                    lock.withLock { standby = runner }
                    log("holding ${runner.engine.name} warm behind it")
                } else {
                    runner.engine.stop()
                }
            }

            lock.withLock { active = winner }
        }

        val result = lock.withLock { active } ?: return null
        startWatching(network)
        return result.session
    }

    private fun startWatching(network: NetworkId) {
        watcher?.cancel()
        watcher = scope.launch {
            var misses = 0
            while (isActive) {
                delay(HEALTH_EVERY_MS)

                val current = lock.withLock { if (stopping) null else active } ?: return@launch

                if (prober.through(current.session.socksPort)) {
                    misses = 0
                    continue
                }

                misses++
                if (misses < HEALTH_TOLERANCE) continue
                misses = 0

                log("${current.engine.name} stopped answering, moving over")
                board.record(network, current.engine.name, ok = false, tookMs = 0)

                if (!promoteStandby()) {
                    // Nothing warm to fall into. Race again rather than leave
                    // the user sitting on a dead tunnel.
                    if (connect(network) == null) log("no way out right now")
                    return@launch
                }
            }
        }
    }

    private suspend fun promoteStandby(): Boolean = lock.withLock {
        val next = standby ?: return@withLock false
        val old = active
        active = next
        standby = null
        old?.engine?.stop()
        log("now on ${next.engine.name}")
        true
    }

    suspend fun activeSession(): Session? = lock.withLock { active?.session }

    suspend fun stop() {
        watcher?.cancel()
        val (a, s) = lock.withLock {
            stopping = true
            val pair = active to standby
            active = null
            standby = null
            pair
        }
        a?.engine?.stop()
        s?.engine?.stop()
    }
}
