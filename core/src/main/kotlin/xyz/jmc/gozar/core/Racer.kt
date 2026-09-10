package xyz.jmc.gozar.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
 * tunnel to move to. [onSwitch] is how the tun gets re-pointed at it — without
 * that call the standby is decoration, because packets keep going to the port
 * of the engine that just died.
 */
class Racer(
    private val engines: List<Engine>,
    private val board: Scoreboard,
    private val prober: Prober,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val onSwitch: (Session) -> Unit = {},
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
        val session = race(network) ?: return null
        startWatching(network)
        return session
    }

    private suspend fun race(network: NetworkId): Session? {
        stopping = false

        val order = board.order(network, engines).filter { engine ->
            val skip = engine.needsBootstrap && !board.isBootstrapped(engine.name)
            if (skip) log("skipping ${engine.label}, nothing to bootstrap from yet")
            !skip
        }
        if (order.isEmpty()) {
            log("no engine has anything to dial")
            return null
        }

        val finished = Channel<Runner?>(capacity = order.size)

        coroutineScope {
            order.forEachIndexed { index, engine ->
                launch {
                    delay(index * LAUNCH_STAGGER_MS)

                    val startedAt = clock()

                    // Carried out rather than logged inside, because a cancelled block cannot
                    // report anything and "it timed out" was being printed for every failure,
                    // including ones that had already been diagnosed a line earlier.
                    var reason = "ran out of time after ${engine.deadlineMs / 1000}s"

                    val runner = withTimeoutOrNull(engine.deadlineMs) {
                        val session = try {
                            engine.start()
                        } catch (e: Exception) {
                            reason = "could not start: ${e.message}"
                            return@withTimeoutOrNull null
                        }

                        // Up is not the same as working.
                        if (!prober.through(session.socksPort, engine.probeTimeoutMs)) {
                            reason = "came up on port ${session.socksPort} and carried nothing"
                            engine.stop()
                            return@withTimeoutOrNull null
                        }

                        Runner(engine, session, clock() - startedAt)
                    }

                    if (runner == null) {
                        log("${engine.label} ${reason}")
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
                    log("up on ${runner.engine.label} in ${runner.tookMs}ms")
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
                    log("holding ${runner.engine.label} warm behind it")
                } else {
                    runner.engine.stop()
                }
            }

            lock.withLock { active = winner }
        }

        return lock.withLock { active }?.session
    }

    private fun startWatching(network: NetworkId) {
        watcher?.cancel()
        watcher = scope.launch { watch(network) }
    }

    /**
     * Kept out of [connect] deliberately. The old version re-raced by calling
     * connect() from inside the watcher, and connect() begins by cancelling the
     * watcher — which was the coroutine doing the calling. It cancelled itself
     * halfway through its own recovery.
     */
    private suspend fun watch(network: NetworkId) {
        var misses = 0

        while (currentCoroutineContext().isActive) {
            delay(HEALTH_EVERY_MS)

            val current = lock.withLock { if (stopping) null else active } ?: return

            if (prober.through(current.session.socksPort, current.engine.probeTimeoutMs)) {
                misses = 0
                continue
            }

            misses++
            if (misses < HEALTH_TOLERANCE) continue
            misses = 0

            log("${current.engine.label} stopped answering, moving over")
            board.record(network, current.engine.name, ok = false, tookMs = 0)

            val moved = promoteStandby()
            if (moved != null) {
                onSwitch(moved)
                continue
            }

            // Nothing warm to fall into. Race again rather than leave the user
            // sitting on a dead tunnel.
            lock.withLock { active = null }
            current.engine.stop()

            val revived = race(network)
            if (revived == null) {
                log("no way out right now")
                return
            }
            onSwitch(revived)
        }
    }

    private suspend fun promoteStandby(): Session? = lock.withLock {
        val next = standby ?: return@withLock null
        val old = active
        active = next
        standby = null
        old?.engine?.stop()
        log("now on ${next.engine.label}")
        next.session
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
