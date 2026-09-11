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
 * The third way out: QUIC over UDP, with the handshake obfuscated to random bytes.
 *
 * <b>Why a third engine is worth its battery, when a fourth would not be.</b> The only thing that
 * makes an extra engine anything other than decoration is that it fails for different reasons than
 * the ones already there. Path one is TLS over TCP and looks like reading a website. Path two is
 * WebRTC and looks like a video call. This is UDP, and with salamander turned on the packets have
 * no structure at all to match against. Equipment tuned to spot a long-lived TLS flow and throttle
 * it — which is exactly what a device log showed happening, a tunnel coming up in three seconds
 * and being strangled thirty seconds later — is not looking at UDP, and equipment blocking UDP is
 * not what killed the TLS one. Three engines of the same shape would die in the same minute.
 * These cannot.
 *
 * <b>Why it costs almost nothing to have.</b> It dials the SAME endpoints the app already fetches.
 * A quarter of every public list is hysteria2 — measured against the live feeds, a hundred and
 * twenty out of four hundred and eighty-three — and every one of those lines was already being
 * downloaded, parsed correctly, checked against a core that cannot dial them, and thrown away. No
 * new source, no server, no account, no infrastructure. Only a second binary and the endpoints
 * that were already going in the bin.
 *
 * <b>How it differs from path one's search, and why.</b> That engine puts forty-eight attempts
 * inside ONE core process, because its core will hold as many inbounds as it is given. This
 * client will not: it is one server per process, so a round here is a handful of real processes.
 * They are small, but they are not free, so this searches narrow and deep rather than wide — a
 * few of the best-scoring endpoints at a time. That is the right trade for a third bet anyway.
 * It does not need to find the best endpoint in the pool; it needs to find one that works on a
 * day when nothing else does, and the racer only ever waits on it when the others have failed.
 */
internal class HysteriaEngine(
    private val context: Context,
    private val store: PoolStore,
    private val log: (String) -> Unit = {},
) : Engine {

    override val name: String = "quic"

    /** See Engine.label: the log is written to be pasted, so it names a number, not a technique. */
    override val label: String = "path 3"

    /** Nothing like TLS and nothing like a video call, which is the entire point of it. */
    override val shape: Shape = Shape.RANDOM

    override val deadlineMs: Long = ROUND_BUDGET_MS * MAX_ROUNDS + 10_000L

    /**
     * Same reasoning as path one: the lists live behind the filtering this engine exists to get
     * past, so on a first ever launch there is nothing to dial and it stands down rather than
     * burning a slot. The seed shipped in the apk usually means this never happens.
     */
    override val needsBootstrap: Boolean
        get() = candidates(store.load(), 1).isEmpty()

    private val running = java.util.concurrent.ConcurrentHashMap<Int, Process>()
    private var pool: EndpointPool = EndpointPool()

    /** Proven endpoints held back for [recover], exactly as path one does. */
    private val warm = java.util.concurrent.ConcurrentLinkedQueue<ProxyConfig>()

    @Volatile private var expected: Boolean = true
    private val healing = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
        check(binary.isFile) { "the quic core is missing for this device architecture" }

        expected = true
        warm.clear()
        pool = store.load()

        val winner = search(binary) ?: error("no quic endpoint answered")

        pool.recordSuccess(winner.endpoint.key(), winner.latencyMs, System.currentTimeMillis())
        save()
        log("path 3 reached its endpoint in ${winner.latencyMs}ms")
        if (warm.isNotEmpty()) log("${warm.size} more proven and held in reserve")

        expected = false
        watchProcess(running[HysteriaConfig.SOCKS_PORT])

        Session(socksPort = HysteriaConfig.SOCKS_PORT, engine = name, shape = shape)
    }

    override fun stop() {
        expected = true
        warm.clear()
        stopAll()
    }

    /**
     * Swaps in another proven endpoint on the SAME local port.
     *
     * The tun is pointed at one address and knows nothing about which server is behind it, so
     * this costs a second of stalled sockets rather than a visible disconnection. See the same
     * method on path one — the reasoning is identical and so is the port trick.
     */
    override suspend fun recover(): Boolean = withContext(Dispatchers.IO) { heal() }

    private class Live(val endpoint: ProxyConfig, val latencyMs: Long)

    /**
     * Looks for an endpoint that carries traffic, a few at a time.
     *
     * Several at once rather than one after another for the same reason path one fans out: a dead
     * endpoint costs the whole probe timeout, most of a public pool is dead, and asking them in
     * sequence spends the entire budget learning about three servers.
     */
    private fun search(binary: File): Live? {
        val tried = mutableSetOf<String>()
        val now = System.currentTimeMillis()

        for (round in 0 until MAX_ROUNDS) {
            val batch = candidates(pool, ROUND_WIDTH).filterNot { it.key() in tried }
            if (batch.isEmpty()) {
                log("no quic candidates left")
                return null
            }
            batch.forEach { tried.add(it.key()) }

            stopAll()
            batch.forEachIndexed { index, endpoint ->
                launch(binary, endpoint, ROUND_BASE_PORT + index, "round$index.json")
            }

            val latencies = RoundProbe.run(batch.size, PROBE_TIMEOUT_MS, RoundProbe.GRACE_MS) { index ->
                val port = ROUND_BASE_PORT + index
                if (!waitForListener(port, LISTENER_WAIT_MS)) -1L
                else SocksProbe.latencyMillis(HysteriaConfig.SOCKS_LISTEN, port, PROBE_TIMEOUT_MS)
            }

            val answered = latencies.indices.filter { latencies[it] >= 0 }
            log("quic round ${round + 1}: ${batch.size} endpoints, ${answered.size} came back")

            for (index in latencies.indices) {
                if (latencies[index] < 0) pool.recordFailure(batch[index].key(), now)
            }
            if (answered.isEmpty()) continue

            // Ranked the same way path one ranks, and for the same reason: response time says a
            // server is alive and near, not that it will carry anything. On these endpoints in
            // particular the difference is large, because a hysteria2 server with a saturated
            // uplink still completes a handshake instantly.
            val order = rankBySpeed(answered, latencies)

            warm.clear()
            order.drop(1).take(WARM_KEPT).forEach { warm.add(batch[it]) }

            for (index in order.take(ESTABLISH_TRIES)) {
                val endpoint = batch[index]
                stopAll()
                val latency = establish(binary, endpoint)
                if (latency >= 0) {
                    warm.remove(endpoint)
                    return Live(endpoint, latency)
                }
                warm.remove(endpoint)
                pool.recordFailure(endpoint.key(), now)
            }
        }

        save()
        return null
    }

    /** Fastest first by measured throughput, with anything unmeasurable kept behind on latency. */
    private fun rankBySpeed(answered: List<Int>, latencies: LongArray): List<Int> {
        val byLatency = answered.sortedBy { latencies[it] }
        if (byLatency.size < 2) return byLatency

        val shortlist = byLatency.take(SPEED_TRIES)
        val rates = SpeedProbe.ratesWithFallback(
            HysteriaConfig.SOCKS_LISTEN,
            shortlist.map { ROUND_BASE_PORT + it }.toIntArray(),
            PROBE_TIMEOUT_MS,
        )

        val measured = shortlist.indices.filter { rates[it] > 0 }
        if (measured.isEmpty()) return byLatency

        val ranked = measured.sortedByDescending { rates[it] }.map { shortlist[it] }
        return ranked + byLatency.filterNot { it in ranked }
    }

    /** Puts one endpoint on the live port and proves it there, not on a scratch port. */
    private fun establish(binary: File, endpoint: ProxyConfig): Long {
        launch(binary, endpoint, HysteriaConfig.SOCKS_PORT, "quic.json")
        if (!waitForListener(HysteriaConfig.SOCKS_PORT, LISTENER_WAIT_MS)) return -1
        return SocksProbe.latencyMillis(
            HysteriaConfig.SOCKS_LISTEN, HysteriaConfig.SOCKS_PORT, PROBE_TIMEOUT_MS,
        )
    }

    private fun heal(): Boolean {
        if (!healing.compareAndSet(false, true)) return false
        try {
            val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
            if (!binary.isFile) return false

            val now = System.currentTimeMillis()
            while (true) {
                val next = warm.poll() ?: return false
                expected = true
                stopAll()
                val latency = establish(binary, next)
                if (latency >= 0) {
                    expected = false
                    watchProcess(running[HysteriaConfig.SOCKS_PORT])
                    pool.recordSuccess(next.key(), latency, now)
                    save()
                    log("path 3 moved to another endpoint in ${latency}ms without dropping the tunnel")
                    return true
                }
                pool.recordFailure(next.key(), now)
            }
        } catch (failure: Exception) {
            log("path 3 could not move over: ${failure.message}")
            return false
        } finally {
            healing.set(false)
        }
    }

    /** A core that exits is not ambiguous and needs no probe to confirm it. See path one. */
    private fun watchProcess(started: Process?) {
        val target = started ?: return
        Thread({
            runCatching { target.waitFor() }
            if (expected || running[HysteriaConfig.SOCKS_PORT] !== target) return@Thread
            log("the quic core stopped on its own, moving to another endpoint")
            heal()
        }, "quic-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * The best-scoring endpoints this engine can dial.
     *
     * 🚨 Filtered by [HysteriaConfig.supports], NOT by the other core's. The pool holds every
     * protocol now — that change is what made this engine possible — so each engine has to say
     * for itself which lines it can use. Reaching for the wrong filter here would hand this one a
     * list of vless endpoints it cannot speak to and report the whole pool as dead.
     */
    private fun candidates(from: EndpointPool, limit: Int): List<ProxyConfig> {
        val out = ArrayList<ProxyConfig>(limit)
        for (entry in from.ranked(System.currentTimeMillis())) {
            if (out.size >= limit) break
            if (entry.score(System.currentTimeMillis()) < 0) continue
            if (!HysteriaConfig.supports(entry.config)) continue
            out.add(entry.config)
        }
        return out
    }

    private fun launch(binary: File, endpoint: ProxyConfig, port: Int, fileName: String) {
        val config = File(context.filesDir, fileName)
            .apply { writeText(HysteriaConfig.build(endpoint, port, "warn")) }

        val builder = ProcessBuilder(binary.absolutePath, "client", "-c", config.absolutePath)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath

        val started = builder.start()
        running[port]?.let { runCatching { it.destroy() } }
        running[port] = started
        drain(started)
    }

    /**
     * Reads the core's output on a background thread.
     *
     * Not for the log: a process whose output nobody reads fills its pipe buffer and then blocks
     * for ever, which looks exactly like a hung engine.
     */
    private fun drain(started: Process) {
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).use { lines ->
                    while (true) {
                        val line = lines.readLine() ?: break
                        // Only the interesting ones. This client narrates every connection it
                        // opens, and at one line per socket the diary would be unreadable and
                        // would carry the addresses that are deliberately kept out of it.
                        if (line.contains("error", true) || line.contains("fail", true)) {
                            log("quic core: ${line.take(200)}")
                        }
                    }
                }
            }
        }, "quic-output").apply { isDaemon = true }.start()
    }

    private fun stopAll() {
        val processes = running.values.toList()
        running.clear()
        processes.forEach { runCatching { it.destroy() } }
        processes.forEach { runCatching { it.waitFor() } }
    }

    private fun waitForListener(port: Int, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (SocksProbe.opens(HysteriaConfig.SOCKS_LISTEN, port, 200)) return true
            runCatching { Thread.sleep(50) }.onFailure { return false }
        }
        return false
    }

    private fun save() {
        pool.prune(System.currentTimeMillis())
        store.save(pool)
    }

    private companion object {
        const val EXECUTABLE = "libquic.so"

        /**
         * Scratch ports, far from the live one and far from path one's range.
         *
         * A round must never bind the port the tunnel is riding on, or the search would be
         * fighting the connection it is trying to replace.
         */
        const val ROUND_BASE_PORT = 32_000

        /**
         * Endpoints tried at once.
         *
         * Small, and deliberately. Unlike the other core, every attempt here is a real operating
         * system process with its own QUIC stack, so this number is measured in tens of megabytes
         * on somebody's phone rather than in sockets. Six is enough to make a round worth running
         * and cheap enough to run four of them.
         */
        const val ROUND_WIDTH = 6
        const val MAX_ROUNDS = 4
        const val ESTABLISH_TRIES = 2
        const val SPEED_TRIES = 3
        const val WARM_KEPT = 4
        const val ROUND_BUDGET_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val LISTENER_WAIT_MS = 4_000L
    }
}
