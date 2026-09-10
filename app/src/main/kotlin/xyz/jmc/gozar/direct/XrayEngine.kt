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

        Session(socksPort = XrayConfig.SOCKS_PORT, engine = name, shape = shape)
    }

    override fun stop() {
        stopProcess()
        spoof.stop()
    }

    /**
     * Works through the pool a round at a time, best-scoring endpoints first.
     *
     * Rounds rather than one enormous config because a config of four hundred attempts is eight
     * hundred listening sockets, and because the ranking is worth respecting: if the endpoints
     * that worked here last week still work, the first round ends it.
     */
    private fun search(binary: File, modes: IntArray): Live? {
        val now = System.currentTimeMillis()
        val tried = mutableSetOf<String>()
        log("pool holds ${pool.size()} endpoints")

        for (round in 0 until MAX_ROUNDS) {
            val candidates = nextCandidates(tried, now, StealthBatch.candidatesFor(modes))
            if (candidates.isEmpty()) {
                log("no candidates left after ${round} rounds")
                return null
            }

            val attempts = StealthBatch.plan(candidates, modes)
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
            log("round ${round + 1}: ${candidates.size} endpoints on ${attempts.size} ports, ${answered.size} came back")

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
            for (index in latencies.indices
                .filter { latencies[it] >= 0 }
                .sortedBy { latencies[it] }
                .take(ESTABLISH_TRIES)) {
                val attempt = attempts[index]
                val latency = establish(binary, attempt)
                if (latency >= 0) return Live(attempt, latency)
                log("an endpoint answered the round and then would not carry the tunnel")
                pool.recordFailure(attempt.endpoint.key(), now)
            }
        }

        save()
        return null
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
        const val ROUND_BUDGET_MS = 20_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val LISTENER_WAIT_MS = 8_000L
    }
}
