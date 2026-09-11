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
    /**
     * Running totals from the tun, as (sent, received).
     *
     * 🚨 Passed in rather than measured here, and it is what closes the worst gap this app has
     * had. A probe asking for an empty two-hundred-and-four succeeds over a tunnel that is barely
     * moving, so the watcher below used to keep a crawling connection alive indefinitely while
     * every check came back green. The counters are the only thing in the process that knows the
     * difference between working and technically connected.
     *
     * Defaults to zeroes, which reads as a permanently idle tunnel and therefore never condemns
     * anything — the safe default for tests and for any caller that has no counters to offer.
     */
    private val traffic: () -> Pair<Long, Long> = { 0L to 0L },
) {

    private class Runner(val engine: Engine, val session: Session, val tookMs: Long)

    private val lock = Mutex()
    private var active: Runner? = null
    private var standby: Runner? = null
    private var watcher: Job? = null
    private var refiller: Job? = null
    private var stopping = false

    /**
     * The network the live tunnel was raced on.
     *
     * Kept because rebuilding a standby happens long after [connect] returned, and the scoreboard
     * has to be asked about the same network the session belongs to. Reading the current network
     * again at that moment would be wrong in the one case that matters — the user walked from
     * wifi onto mobile data, which is its own event with its own reconnect.
     */
    @Volatile private var lastNetwork: NetworkId = NetworkId.UNKNOWN

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

        /**
         * Health ticks between one look at the standby and the next. Four ticks is about a
         * minute, which is often enough to catch a reserve that has died and rare enough that
         * nobody pays for it.
         */
        const val STANDBY_EVERY_TICKS = 4L

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
        lastNetwork = network

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

        order.forEachIndexed { index, engine ->
            scope.launch {
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

        // 🚨 The loop stops at the FIRST engine that proves itself, and that is the whole point of
        // it. It used to wait for every engine to report before handing the tunnel over, so a
        // proven engine sat idle while a losing one worked through its own timeouts. A device log
        // measured the cost: the fast path was up and verified at twenty-two seconds, the other
        // engine did not finish failing until a hundred and fifty-three, and the user waited the
        // whole two and a half minutes staring at a connection that already worked.
        //
        // The engines that have not reported yet are not abandoned - [gather] picks them up off
        // the same channel afterwards, so a genuine standby is still kept warm. It just happens
        // behind a tunnel that is already carrying traffic instead of in front of it.
        var seen = 0
        var winner: Runner? = null
        while (seen < order.size) {
            val runner = finished.receive()
            seen++
            if (runner != null) {
                winner = runner
                break
            }
        }

        if (winner == null) return null

        log("up on ${winner.engine.label} in ${winner.tookMs}ms")
        lock.withLock { active = winner }

        val stillRacing = order.size - seen
        if (stillRacing > 0) scope.launch { gather(stillRacing, finished) }

        return winner.session
    }

    /**
     * Takes the engines that were still racing when the winner was decided.
     *
     * One of them is kept warm only if it fails differently - a standby of the same shape dies
     * alongside the thing it is meant to replace - and the rest are shut down rather than left
     * burning battery behind a tunnel nobody is going to move to.
     */
    private suspend fun gather(count: Int, finished: Channel<Runner?>) {
        repeat(count) {
            val runner = finished.receive()
            if (runner == null) return@repeat

            val wanted = lock.withLock {
                val current = active
                !stopping && current != null && standby == null &&
                    runner.engine.shape != current.engine.shape
            }

            if (wanted) {
                lock.withLock { standby = runner }
                log("holding ${runner.engine.label} warm behind it")
            } else {
                runner.engine.stop()
            }
        }

        // 🚨 The race may well have ended with nothing warm behind the winner — every runner-up
        // shared its shape, or every one of them failed. That used to be the end of it, and it is
        // the reason a second failure was always a full re-race with the tunnel down while it
        // looked. Ask for one to be built now, quietly, behind a connection that already works.
        ensureStandby()
    }

    /**
     * Makes sure something different is warm behind the live tunnel, starting one if not.
     *
     * 🔑 The point of a standby is not that one exists at connect time — it is that one exists at
     * FAILURE time, which may be hours later. Three things empty the slot: the race ended without
     * a suitable runner-up, the standby was promoted when the winner died, or the standby itself
     * quietly died while sitting there. All three used to leave the app with a single point of
     * failure and no sign of it. This is called after every one of them.
     *
     * Runs at most once at a time and always in the background, because it starts an engine and
     * waits on a real request through it — neither of which the health watcher can afford to
     * block on.
     */
    private fun ensureStandby() {
        if (refiller?.isActive == true) return
        refiller = scope.launch {
            verifyStandby()
            fillStandby()
        }
    }

    /**
     * Checks that the warm engine is still warm, and drops it if it is not.
     *
     * A standby is started once and then sits untouched, possibly for hours. Nothing about being
     * started keeps it alive: its server can be withdrawn, its circuit can expire, the network can
     * change underneath it. Finding that out at the moment of promotion is the worst possible
     * time, because the tun has already been re-pointed at it and the user is already offline.
     * Finding it out on a quiet timer costs one small request and nothing else.
     */
    private suspend fun verifyStandby() {
        val warm = lock.withLock { if (stopping) null else standby } ?: return
        if (prober.through(warm.session.socksPort, warm.engine.probeTimeoutMs)) return

        log("the one held in reserve went quiet, finding another")
        lock.withLock { if (standby === warm) standby = null }
        warm.engine.stop()
    }

    private suspend fun fillStandby() {
        val current = lock.withLock {
            if (stopping || standby != null) null else active
        } ?: return

        val network = lastNetwork
        val candidates = board.order(network, engines).filter { engine ->
            engine.name != current.engine.name &&
                // Same reasoning as in [gather]: a standby of the same shape dies alongside the
                // thing it is there to replace, so it is not a second bet.
                engine.shape != current.engine.shape &&
                !(engine.needsBootstrap && !board.isBootstrapped(engine.name))
        }

        for (engine in candidates) {
            if (lock.withLock { stopping }) return

            val session = withTimeoutOrNull(engine.deadlineMs) {
                val started = try {
                    engine.start()
                } catch (e: Exception) {
                    return@withTimeoutOrNull null
                }
                // Held to the same standard as a winner. A standby that was never proved is worse
                // than none, because it is discovered to be dead only after the tun has already
                // been pointed at it — a drop caused by the thing meant to prevent drops.
                if (!prober.through(started.socksPort, engine.probeTimeoutMs)) null else started
            }

            if (session == null) {
                engine.stop()
                continue
            }

            val kept = lock.withLock {
                if (stopping || active == null || standby != null) {
                    false
                } else {
                    standby = Runner(engine, session, 0)
                    true
                }
            }

            if (kept) {
                log("holding ${engine.label} warm behind it")
            } else {
                engine.stop()
            }
            return
        }
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
        var starved = 0
        var ticks = 0L
        val floor = Floor()

        while (currentCoroutineContext().isActive) {
            delay(HEALTH_EVERY_MS)

            val current = lock.withLock { if (stopping) null else active } ?: return

            val reachable = prober.through(current.session.socksPort, current.engine.probeTimeoutMs)

            // Two separate questions, and both have to be asked. "Can it reach anything" catches a
            // tunnel that died. "Is anything coming back" catches the far nastier case: a tunnel
            // that is alive, answers every probe, and delivers a few hundred bytes a second, so
            // the app looks connected and nothing loads. The second one was missing entirely and
            // it is the fault the user actually reported.
            if (reachable) {
                misses = 0
                val (sent, received) = traffic()
                if (floor.sample(sent, received) != Floor.Verdict.STARVED) {
                    starved = 0
                    // Not every tick: the standby is checked with a real request through a real
                    // tunnel, and paying that every fifteen seconds for hours would be a
                    // noticeable amount of battery and traffic for a question that changes slowly.
                    ticks++
                    if (ticks % STANDBY_EVERY_TICKS == 0L) ensureStandby()
                    continue
                }
                starved++
                if (starved < HEALTH_TOLERANCE) continue
                log("${current.engine.label} is up but barely carrying anything, moving over")
            } else {
                misses++
                if (misses < HEALTH_TOLERANCE) continue
            }

            misses = 0
            starved = 0
            floor.reset()

            // 🔑 Asked before anything is torn down, and it is the cheapest recovery there is.
            // An engine that dials one of hundreds of interchangeable servers can swap the dead
            // one out behind its own port, which nothing above it can even see. Only when it
            // says no does this get expensive: a standby promotion re-points the tun, and a
            // re-race drops the tunnel entirely while it looks.
            if (current.engine.recover() &&
                prober.through(current.session.socksPort, current.engine.probeTimeoutMs)
            ) {
                continue
            }

            // Only for the tunnel that genuinely went quiet. A starved one answered every probe
            // it was given, and has already said so a few lines up; printing that it stopped
            // answering would send whoever reads the log looking for a network fault that is not
            // there.
            if (!reachable) log("${current.engine.label} stopped answering, moving over")
            board.record(network, current.engine.name, ok = false, tookMs = 0)

            val moved = promoteStandby()
            if (moved != null) {
                onSwitch(moved)
                // The slot the promotion just emptied. Without this the app is one failure away
                // from a full re-race again, having spent the very thing that was protecting it.
                ensureStandby()
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
        refiller?.cancel()
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
