package xyz.jmc.gozar.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape
import java.io.BufferedReader
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A way out that carries no list of servers, because it does not dial servers.
 *
 * 🚨 Read this before changing anything here. Every other engine in this app shares one weakness
 * and it is not a coding mistake: they dial endpoints taken from public lists, and on a filtered
 * network almost none of those endpoints are alive. A device log measured it — rounds coming back
 * with one or two answers out of forty, and a tunnel that did come up carrying six hundred and
 * seventy bytes a second. Ranking, probing, racing and scoring were all working correctly and all
 * of it was rearranging a list of dead addresses. No amount of cleverness above that layer raises
 * the ceiling, because the ceiling is the list.
 *
 * This engine has no list. It speaks WireGuard to Cloudflare's own edge, which is anycast, which
 * means the address is not a server somebody put on a list that somebody else then blocked — it is
 * the same address family that carries an enormous share of ordinary web traffic. Blocking it
 * wholesale costs the blocker far more than it costs us. That is the entire reason it is here.
 *
 * Three modes, tried in order, all from the same program:
 *
 *  - **plain** picks an edge address and connects. Cheapest, and on a good night it is up in a
 *    handful of seconds.
 *  - **scan** sweeps Cloudflare's ranges with real WireGuard handshakes and keeps the ones that
 *    answer fastest. This is what survives when the well-known addresses are interfered with, and
 *    it costs the sweep.
 *  - **gool** runs one of those tunnels inside another one. Slower by a hop, and it gets through
 *    shaping that reads the outer connection, because the outer connection is the only one there
 *    is to read.
 *
 * The mode that worked is remembered, so the ladder is paid once and afterwards this usually
 * comes up on the first rung.
 *
 * 🔑 Readiness is taken from the program's own words rather than from the port being open. It
 * prints `serving proxy` only after it has completed a handshake AND fetched something through
 * the tunnel, so that line is a measurement, not a claim. Waiting on the port instead would report
 * success about a second before the tunnel existed, which is precisely the failure this whole app
 * is built to avoid.
 *
 * Shipped as libwarp.so even though it is a program, for the same reason as the other two: since
 * Android 10 an app may only execute a binary from the installer's native library directory.
 */
class EdgeEngine(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : Engine {

    override val name: String = "edge"

    /** Numbered like the others. What it is made of is nobody's business but ours. */
    override val label: String = "path 4"

    override val shape: Shape = Shape.WIREGUARD

    /**
     * False, and this is the point of the engine.
     *
     * Every other fast path in this app has to fetch something before it can dial anything, and
     * the things it fetches are hosted exactly where they are blocked. This one needs nothing but
     * the network it is already on, so it is the only engine that can win on a fresh install, on
     * a new operator, or after the endpoint list has gone stale — which is to say, in all three
     * situations where the app was previously useless.
     */
    override val needsBootstrap: Boolean = false

    /**
     * Long, because the ladder is inside [start] rather than spread over three races.
     *
     * The racer stops at the first engine that proves itself, so a long deadline here costs
     * nothing when something else wins sooner. What it buys is the case that matters: when this is
     * the only thing that can get out, a sweep worth thirty seconds beats failing in ten.
     */
    override val deadlineMs: Long = 100_000L

    private val binary = File(context.applicationInfo.nativeLibraryDir, LIBRARY)

    /** Where the registered identity is kept, so registration is paid once and never again. */
    private val cache = File(context.filesDir, "edge").apply { mkdirs() }

    /** Which rung worked last time. Survives restarts; it is one word in one file. */
    private val memory = File(context.filesDir, "edge-mode")

    @Volatile private var process: Process? = null

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        check(binary.isFile && binary.canExecute()) { "no edge program in this build" }

        for (mode in ladder()) {
            stop()
            if (run(mode)) {
                remember(mode)
                return@withContext Session(PORT, name, shape)
            }
        }
        stop()
        error("no edge address answered")
    }

    /**
     * Yes, and cheaply.
     *
     * Cloudflare's edge is anycast: the address that stopped answering and the address that will
     * answer next are frequently the same address routed somewhere else. Restarting the program
     * behind the same loopback port re-picks the edge, re-handshakes and re-tests, and the tun
     * above it never notices, which is the difference between a stalled second and a visible drop.
     */
    override suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        // Deliberately not the remembered mode. Whatever was remembered is what just died, so
        // going back to it first would spend the cheap recovery on the one rung known to be
        // failing right now.
        for (mode in Mode.entries) {
            stop()
            if (run(mode)) {
                remember(mode)
                return@withContext true
            }
        }
        stop()
        false
    }

    override fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            running.waitFor()
        }
    }

    // -- the ladder ---------------------------------------------------------------------------

    private enum class Mode(val flags: List<String>, val windowMs: Long) {
        /** An edge address and nothing else. Up in seconds when the network allows it. */
        PLAIN(emptyList(), 20_000L),

        /** Real handshakes across the ranges, keeping whatever answers fastest. */
        SCAN(listOf("--scan", "--rtt", "1200ms"), 45_000L),

        /** One tunnel inside another, for equipment that reads the outer one. */
        GOOL(listOf("--gool", "--scan", "--rtt", "1500ms"), 60_000L),
    }

    private fun ladder(): List<Mode> {
        val remembered = runCatching { memory.readText().trim() }.getOrNull()
        val first = Mode.entries.firstOrNull { it.name == remembered } ?: return Mode.entries
        return listOf(first) + Mode.entries.filter { it != first }
    }

    private fun remember(mode: Mode) {
        runCatching { memory.writeText(mode.name) }
    }

    /**
     * Starts one rung and waits for the program to say it has a working tunnel.
     *
     * @return true when the proxy is serving and has proved itself, false otherwise
     */
    private fun run(mode: Mode): Boolean {
        val command = listOf(
            binary.absolutePath,
            "--bind", "$LOOPBACK:$PORT",
            "--cache-dir", cache.absolutePath,
            "--dns", DNS,
        ) + mode.flags

        val started = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull()
        if (started == null) {
            log("$label could not be started")
            return false
        }
        process = started

        val deadline = System.currentTimeMillis() + mode.windowMs
        val reader = started.inputStream.bufferedReader()

        // 🚨 The output has to be drained whatever happens. A process whose pipe fills up blocks
        // on its next write and stops making progress, and from out here that is indistinguishable
        // from a network that went quiet — the tunnel would simply never come up, with no error
        // anywhere to say why.
        val ready = drainUntilReady(reader, deadline)

        if (!ready) {
            log("$label found nothing on the ${mode.name.lowercase()} route")
            stop()
            return false
        }

        // Said it is serving. Confirm the socket agrees before handing it to the racer, which
        // will immediately try to speak SOCKS through it.
        if (!waitForPort()) {
            log("$label said it was serving and the port never opened")
            stop()
            return false
        }

        // Keep draining in the background for the same pipe-fills-up reason as above. Nothing
        // reads what it prints from here on; it is thrown away deliberately, because the lines
        // carry edge addresses and a pasted log should not tell anyone where this app goes.
        Thread {
            runCatching { while (reader.readLine() != null) Unit }
            runCatching { reader.close() }
        }.apply { isDaemon = true }.start()

        return true
    }

    private fun drainUntilReady(reader: BufferedReader, deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            val running = process ?: return false
            if (!running.isAlive && !reader.ready()) return false

            val line = runCatching { reader.readLine() }.getOrNull() ?: return false
            if (line.contains(READY)) return true
        }
        return false
    }

    private fun waitForPort(): Boolean {
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process == null) return false
            Socket().use { probe ->
                runCatching {
                    probe.connect(InetSocketAddress(LOOPBACK, PORT), 300)
                    return true
                }
            }
            runCatching { Thread.sleep(50) }.onFailure { return false }
        }
        return false
    }

    companion object {
        private const val LIBRARY = "libwarp.so"
        private const val LOOPBACK = "127.0.0.1"

        /**
         * Its own loopback port, shared with nothing. A stale process left behind by a previous
         * run would otherwise be mistaken for this one and reported as a working tunnel.
         */
        const val PORT = 18086

        private const val DNS = "1.1.1.1"

        /**
         * The program prints this once, and only after a handshake has completed and a request
         * has come back through the tunnel. Matching on it rather than on the port opening is the
         * difference between knowing and hoping.
         */
        private const val READY = "serving proxy"

        private const val PORT_WAIT_MS = 4_000L
    }
}
