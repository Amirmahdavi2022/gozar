package xyz.jmc.gozar.direct

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * The fast way out: a proxy core dialling a public endpoint directly.
 *
 * This is the engine Tor cannot be. Tor is three hops of volunteer relays and will never be
 * quick; this is one hop to a server that is usually a few tens of milliseconds away, speaking
 * something that looks like an ordinary TLS session to an ordinary website.
 *
 * It is also a genuinely separate bet, which is the whole point of having two. Different program,
 * different process, different socket, different shape on the wire. When one is blocked the other
 * has no reason to be, and neither can take the other down.
 *
 * <b>How a round works, and why it is not a loop.</b> The obvious way to find a working endpoint
 * is to try one, wait, try the next. Measured on a real phone that costs about six seconds per
 * endpoint — stop the core, start a fresh one, wait for its listener, probe, kill it — so a whole
 * connect budget buys evidence about six servers out of four hundred. The core was never the
 * limit: it will hold as many inbounds and outbounds as it is given, and a routing rule per pair
 * keeps them from mixing. So one process start buys dozens of simultaneous probes, and the route
 * travels with the candidate rather than being a nested retry: the same server sits on two ports
 * at once, plain and shaped, and the first port to answer says both which server works and which
 * way it works.
 */
internal class XrayEngine(
    private val context: Context,
    private val store: PoolStore,
    private val log: (String) -> Unit = {},
) : Engine {

    override val name: String = "direct"

    /** Tried first, so it is the first path. See Engine.label for why this is not the name. */
    override val label: String = "path 1"

    /** TLS to what looks like a website. Nothing like Tor's shape, which is the point. */
    override val shape: Shape = Shape.HTTPS

    override val deadlineMs: Long = ROUND_BUDGET_MS * MAX_ROUNDS + 15_000L

    /**
     * With no pool there is nothing to dial, and the lists live behind the same filtering this
     * engine exists to get past. So on a first run it stands down, Tor brings the tunnel up, and
     * the pool is fetched through that. After that this engine usually wins, because it is faster.
     */
    override val needsBootstrap: Boolean
        get() = store.load().size() == 0

    private val spoof = SpoofProxy(context)
    private var process: Process? = null
    private var pool: EndpointPool = EndpointPool()

    /**
     * Endpoints that answered the winning round and were not needed, best first.
     *
     * <p>The round already proved these carried a real request, seconds ago, and they cost
     * nothing to keep — they are a handful of lines of text. When the live endpoint dies this is
     * the difference between moving to the next one in about a second and starting the whole
     * search again from nothing, which is what the log was showing: the core gone, the tun still
     * posting packets at its port, and half a minute before anything noticed.
     */
    private val warm = java.util.concurrent.ConcurrentLinkedQueue<XrayConfig.Attempt>()

    /** What is on the live SOCKS port right now, so a watchdog knows what died. */
    @Volatile private var live: XrayConfig.Attempt? = null

    /** Set while stop() or a search is deliberately killing the core, so the watchdog stays out. */
    @Volatile private var expected: Boolean = true

    /** One recovery at a time, whoever notices first. */
    private val healing = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
        check(binary.isFile) { "the proxy core is missing for this device architecture" }

        pool = store.load()
        check(pool.size() > 0) { "no endpoints known yet" }

        val shaped = spoof.start()
        log(if (shaped) "shaping proxy is up" else "shaping proxy unavailable, plain route only")
        val modes = if (shaped) intArrayOf(DialMode.DIRECT, DialMode.SPOOF) else intArrayOf(DialMode.DIRECT)

        val live = search(binary, modes) ?: run {
            spoof.stop()
            error("nothing in the pool answered")
        }

        pool.recordSuccess(live.attempt.endpoint.key(), live.latencyMs, System.currentTimeMillis())
        save()
        log("path 1 reached its endpoint ${DialMode.label(live.attempt.mode)} in ${live.latencyMs}ms")
        if (warm.isNotEmpty()) log("${warm.size} more proven and held in reserve")

        // From here on the core dying is news, not housekeeping.
        this@XrayEngine.live = live.attempt
        expected = false
        watchProcess(process)

        Session(socksPort = XrayConfig.SOCKS_PORT, engine = name, shape = shape)
    }

    override fun stop() {
        expected = true
        warm.clear()
        live = null
        stopProcess()
        spoof.stop()
    }

    /**
     * Moves to another endpoint on the SAME local port, without the tunnel being torn down.
     *
     * <b>🔑 The port is the whole trick.</b> tun2socks is pointed at one address and nothing above
     * it — not the tun device, not the routes, not a single app on the phone — knows or cares
     * which server is on the far side of it. So replacing the endpoint underneath costs a second
     * of stalled sockets and nothing else, while the alternative the app used to take, tearing the
     * engine down and racing again, costs the tun being re-attached and every connection in flight
     * dropped, which is what someone feels as "it keeps disconnecting".
     *
     * Only ever moves to endpoints the winning round already proved, so this is not a search —
     * it is picking up something that was measured a minute ago and set aside.
     *
     * @return whether something is now listening on the live port again
     */
    override suspend fun recover(): Boolean = withContext(Dispatchers.IO) { heal() }

    private fun heal(): Boolean {
        if (!healing.compareAndSet(false, true)) return false
        try {
            val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
            if (!binary.isFile) return false

            val now = System.currentTimeMillis()
            while (true) {
                val next = warm.poll() ?: run {
                    log("nothing left that was proven, path 1 has to look again")
                    return false
                }
                expected = true
                val latency = establish(binary, next)
                if (latency >= 0) {
                    live = next
                    expected = false
                    watchProcess(process)
                    pool.recordSuccess(next.endpoint.key(), latency, now)
                    save()
                    log("path 1 moved to another endpoint in ${latency}ms without dropping the tunnel")
                    return true
                }
                pool.recordFailure(next.endpoint.key(), now)
            }
        } catch (failure: Exception) {
            log("path 1 could not move over: ${failure.message}")
            return false
        } finally {
            healing.set(false)
        }
    }

    /**
     * Watches the core process and moves over the moment it exits.
     *
     * <p>🚨 Written for a specific line in a real device log: `failed to connect to /127.0.0.1
     * (port 1820) ... ECONNREFUSED`. The core had died, and the only thing that could notice was
     * a health check on a fifteen-second timer whose own probe walks three hosts at six seconds
     * each — so the phone had no internet for the better part of a minute while the app showed a
     * green, connected screen. Nothing was watching the one thing that had actually failed.
     *
     * <p>A process that exits is not ambiguous and needs no probe to confirm it.
     */
    private fun watchProcess(started: Process?) {
        val target = started ?: return
        Thread({
            runCatching { target.waitFor() }
            if (expected || process !== target) return@Thread
            log("the core stopped on its own, moving to another endpoint")
            heal()
        }, "core-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * Works through the pool a round at a time, best-scoring endpoints first.
     *
     * Rounds rather than one enormous config because a config of four hundred attempts is eight
     * hundred listening sockets, and because the ranking is worth respecting: if the endpoints
     * that worked here last week still work, the first round ends it.
     */
    private fun search(binary: File, modes: IntArray): Live? {
        expected = true
        warm.clear()
        val now = System.currentTimeMillis()
        val tried = mutableSetOf<String>()
        log("pool holds ${pool.size()} endpoints")

        for (round in 0 until MAX_ROUNDS) {
            val candidates = nextCandidates(tried, now, StealthBatch.spreadCandidatesFor(modes))
            if (candidates.isEmpty()) {
                log("no candidates left after ${round} rounds")
                return null
            }

            val attempts = StealthBatch.spread(candidates, modes)
            val config = XrayConfig.buildFanout(
                attempts, StealthBatch.BASE_PORT, "warning", null,
                if (modes.contains(DialMode.SPOOF)) SpoofProxy.address() else null,
            )

            stopProcess()
            launch(binary, write("round.json", config))
            if (!waitForListener(StealthBatch.portFor(attempts.size - 1))) {
                log("the core did not come up for this round")
                continue
            }

            val latencies = probeRound(attempts.size)
            val answered = latencies.indices.filter { latencies[it] >= 0 }.toSet()
            val reached = attempts.map { it.endpoint.key() }.distinct().size
            log("round ${round + 1}: $reached endpoints on ${attempts.size} ports, ${answered.size} came back")

            // An endpoint is only written down as failed when every route to it failed. Otherwise
            // a filtered network benches healthy servers one connect at a time.
            for (key in StealthBatch.failedEverywhere(attempts, answered)) {
                pool.recordFailure(key, now)
            }

            // 🚨 Every endpoint that answered, best first - not just the best one. The round and
            // the real connection are two different cores, and an endpoint that answered a probe
            // a second ago can still refuse the next connection: these are free public servers
            // under load, and some of them accept one session at a time. Giving up on the whole
            // engine at that point is what made the app need a second tap on Connect, with a
            // round full of proven endpoints thrown away for one that went quiet.
            val order = rank(latencies)

            // What is not tried now is not thrown away. These are endpoints that answered a real
            // request seconds ago, and they are what [recover] moves to when the live one dies -
            // the difference between a hiccup and a reconnect.
            warm.clear()
            order.drop(1).take(WARM_KEPT).forEach { warm.add(attempts[it]) }

            for (index in order.take(ESTABLISH_TRIES)) {
                val attempt = attempts[index]
                val latency = establish(binary, attempt)
                if (latency >= 0) {
                    warm.remove(attempt)
                    return Live(attempt, latency)
                }
                log("an endpoint answered the round and then would not carry the tunnel")
                warm.remove(attempt)
                pool.recordFailure(attempt.endpoint.key(), now)
            }
        }

        save()
        return null
    }

    /**
     * Puts the round's answers in the order worth trying them, fastest first.
     *
     * <b>🚨 This is the fix for a connection that came up in three seconds and then crawled.</b>
     * Everything here used to be ordered by [latencies] alone — the time to fetch a two-hundred-
     * and-four with no body. That measures how near a server is and whether it is alive. It does
     * not measure whether it will carry anything, and on a pool of free public endpoints the two
     * are close to opposite: the nearest endpoints are the popular ones, the popular ones are the
     * saturated ones, and a server sharing its uplink with four hundred people still answers a
     * two-hundred-and-four instantly. Measured against the real core with endpoints throttled on
     * purpose, latency picked a ninety-kilobyte-per-second server over a four-megabyte one,
     * because it was fifteen milliseconds nearer.
     *
     * So the shortlist is ranked by latency — which is free, it was already measured — and then
     * the top few are asked to actually move some bytes, all at once on the ports they are
     * already listening on. That costs one short window, not one per candidate.
     *
     * Anything unmeasurable keeps its old latency ordering behind the measured ones, so a network
     * where no speed host is reachable is left exactly where it was before this existed rather
     * than worse.
     */
    private fun rank(latencies: LongArray): List<Int> {
        val answered = latencies.indices.filter { latencies[it] >= 0 }.sortedBy { latencies[it] }
        if (answered.size < 2) return answered

        val shortlist = answered.take(SPEED_TRIES)
        val rates = SpeedProbe.ratesWithFallback(
            XrayConfig.SOCKS_LISTEN,
            shortlist.map { StealthBatch.portFor(it) }.toIntArray(),
            PROBE_TIMEOUT_MS,
        )

        val measured = shortlist.indices.filter { rates[it] > 0 }
        if (measured.isEmpty()) {
            log("no speed host answered, going on response time alone")
            return answered
        }

        val fastest = measured.maxOf { rates[it] }
        log("measured ${measured.size} of ${shortlist.size}, best ${humanRate(fastest)}")

        val ranked = measured.sortedByDescending { rates[it] }.map { shortlist[it] }
        return ranked + answered.filterNot { it in ranked }
    }

    private fun humanRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_000_000 -> "${bytesPerSecond / 100_000 / 10.0} MB/s"
        else -> "${bytesPerSecond / 1024} KB/s"
    }

    /** An endpoint that answered on the real port, and how long it took. */
    private class Live(val attempt: XrayConfig.Attempt, val latencyMs: Long)

    /**
     * Puts one endpoint on the real SOCKS port and proves it there.
     *
     * The round only filters. The connection the user's traffic rides is established here, by a
     * config with a single endpoint in it, so the winning path is not the same socket that was
     * being probed a moment ago.
     *
     * @return its latency, or negative if it did not answer on the real port
     */
    private fun establish(binary: File, attempt: XrayConfig.Attempt): Long {
        stopProcess()
        val config = XrayConfig.build(
            attempt.endpoint,
            XrayConfig.SOCKS_PORT,
            "warning",
            null,
            if (attempt.mode == DialMode.SPOOF) SpoofProxy.address() else null,
        )
        launch(binary, write("core.json", config))
        if (!waitForListener(XrayConfig.SOCKS_PORT)) return -1
        return SocksProbe.latencyMillis(XrayConfig.SOCKS_LISTEN, XrayConfig.SOCKS_PORT, PROBE_TIMEOUT_MS)
    }

    private fun nextCandidates(tried: MutableSet<String>, now: Long, limit: Int): List<ProxyConfig> {
        val out = ArrayList<ProxyConfig>(limit)
        for (entry in pool.ranked(now)) {
            if (out.size >= limit) break
            val candidate = entry.config
            if (entry.score(now) < 0) continue                 // benched
            if (!XrayConfig.supports(candidate)) continue
            if (!tried.add(candidate.key())) continue
            out.add(candidate)
        }
        return out
    }

    /**
     * Probes every port in the round at once. Negative means that attempt did not answer.
     *
     * The round ends shortly after the first answer rather than after the last timeout - see
     * [RoundProbe]. A pool is mostly dead endpoints, and a dead endpoint costs the whole timeout,
     * so waiting for all of them meant every round cost six seconds even when the winner had
     * already answered.
     */
    private fun probeRound(count: Int): LongArray =
        RoundProbe.run(count, PROBE_TIMEOUT_MS, RoundProbe.GRACE_MS) { index ->
            SocksProbe.latencyMillis(
                XrayConfig.SOCKS_LISTEN, StealthBatch.portFor(index), PROBE_TIMEOUT_MS,
            )
        }

    private fun launch(binary: File, config: File) {
        val directory = context.filesDir
        val builder = ProcessBuilder(binary.absolutePath, "run", "-c", config.absolutePath)
            .directory(directory)
            .redirectErrorStream(true)
        // The core looks for geo databases through these. Nothing we write references a geoip or
        // geosite rule so they are never opened, but pointing them somewhere writable keeps the
        // core from complaining about a read-only path.
        builder.environment()["XRAY_LOCATION_ASSET"] = directory.absolutePath
        builder.environment()["XRAY_LOCATION_CONFIG"] = directory.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath

        val started = builder.start()
        process = started
        drain(started)
    }

    /**
     * Reads the core's output on a background thread.
     *
     * Not for the logging: a process whose output nobody reads fills its pipe buffer and then
     * blocks forever, which looks exactly like a hung engine.
     */
    private fun drain(started: Process) {
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).use { lines ->
                    while (true) {
                        val line = lines.readLine() ?: break
                        if (line.isNotEmpty()) log("core: $line")
                    }
                }
            }
        }, "core-output").apply { isDaemon = true }.start()
    }

    private fun stopProcess() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            running.waitFor()
        }
    }

    private fun waitForListener(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + LISTENER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            if (SocksProbe.opens(XrayConfig.SOCKS_LISTEN, port, 200)) return true
            runCatching { Thread.sleep(50) }.onFailure { return false }
        }
        return false
    }

    private fun write(name: String, body: String): File =
        File(context.filesDir, name).apply { writeText(body) }

    private fun save() {
        pool.prune(System.currentTimeMillis())
        store.save(pool)
    }

    private companion object {
        const val EXECUTABLE = "libxray.so"
        const val MAX_ROUNDS = 4

        /** How many of a round's answers are given a real connection before moving to the next. */
        const val ESTABLISH_TRIES = 3

        /**
         * How many of a round's answers are actually timed.
         *
         * Four rather than all of them because every one costs bandwidth on someone's mobile
         * plan, and because they are measured together — past a handful they start competing for
         * the phone's link hard enough that the ordering stops meaning anything.
         */
        const val SPEED_TRIES = 4

        /** Proven endpoints set aside for [recover]. Text, so keeping them costs nothing. */
        const val WARM_KEPT = 6
        const val ROUND_BUDGET_MS = 20_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val LISTENER_WAIT_MS = 8_000L
    }
}
