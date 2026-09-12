package xyz.jmc.gozar.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Redact
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape
import java.io.BufferedReader
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A way out that carries no list of servers, because it does not dial servers.
 *
 * 🚨 Read this before changing anything here. Every other way out in this app shares one weakness
 * and it is not a coding mistake: they dial endpoints taken from public lists, and on a filtered
 * network almost none of those endpoints are alive. A device log measured it — rounds coming back
 * with one or two answers out of forty, and a tunnel that did come up carrying six hundred and
 * seventy bytes a second. Ranking, probing, racing and scoring were all working correctly, and all
 * of it was rearranging a list of dead addresses. No amount of cleverness above that layer raises
 * the ceiling, because the ceiling is the list.
 *
 * This one has no list. It speaks MASQUE over HTTP/3 to Cloudflare's own edge, which is anycast —
 * so the address is not a server somebody put on a list that somebody else then blocked, it is the
 * same address family carrying an enormous share of ordinary web traffic. Blocking it wholesale
 * costs the blocker far more than it costs us.
 *
 * 🔑 Why this program and not the one that was here before. The previous core was a reasonable
 * choice on paper and it failed on this phone for reasons that took three releases to find: it
 * panicked at startup over a TLS fork it linked but never used, and underneath that it could not
 * have registered an account anyway, because a program on Android has no name server to ask. This
 * one was chosen on evidence instead of on paper — it is the core already connecting on the
 * owner's own device in another app — and it sidesteps that second trap by design: when direct
 * registration fails it retries over a Cloudflare edge address dialled with no DNS lookup at all.
 *
 * Three rungs, tried in order, with the winning rung remembered:
 *
 *  - **turbo** stops at the first gateway that answers. On a good night it is up in seconds.
 *  - **thorough** sweeps whole ranges instead of sampling, and adds the obfuscation profile meant
 *    for heavily filtered networks plus a split client hello. Slower, and it is what survives when
 *    the easy addresses are interfered with.
 *  - **warp-in-warp** runs one tunnel inside another. Slower again by a hop, and it gets through
 *    shaping that reads the outer connection, because the outer connection is all there is to read.
 *
 * 🔑 Readiness is taken from the program's own words, and those words are a measurement rather
 * than a claim: it says the tunnel is validated only after data has actually travelled end to end.
 * Waiting on the loopback port instead would report success while the tunnel was still being
 * built, which is precisely the failure this whole app exists to avoid.
 *
 * ⚖️ The program is AGPL-3.0 and this app is MIT. It stays a separate process reached over SOCKS,
 * which keeps them separate works; NOTICE carries the offer of its source. Never link it in.
 *
 * Shipped as libaether.so even though it is a program, because since Android 10 an app may only
 * execute a binary out of the installer's native library directory.
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
     * the network it is already on, so it is the only way out that can win on a fresh install, on
     * an operator the app has never seen, or after the endpoint list has gone stale — which is to
     * say, in all three situations where the app was previously useless.
     */
    override val needsBootstrap: Boolean = false

    /**
     * Long, because the ladder is inside [start] rather than spread over three races.
     *
     * The racer stops at the first way out that proves itself, so a long deadline here costs
     * nothing when something else wins sooner. What it buys is the case that matters: when this is
     * the only thing that can get out, a sweep worth a minute beats failing in ten seconds.
     */
    override val deadlineMs: Long = 165_000L

    /**
     * 🚨 Held back on purpose, and it is the difference between the app being usable and not.
     *
     * This one comes up in three or four seconds almost every time, which meant it won every
     * single race — and what it wins with is a tunnel that comes out in the SAME country the
     * phone is in, because the network behind it is location-preserving by design. So the app
     * would settle on the one way out that unblocks nothing, while a path that exits abroad was
     * two seconds from proving itself. Measured on the owner's own device: this won at 3.6s and
     * the quic path reached its endpoint at 4.0s.
     *
     * Six seconds is enough for anything with a live endpoint to get in first, and short enough
     * that when nothing else has one - a fresh install, a stale list, a network where the public
     * servers are all dead - this is still up inside ten seconds. It stays the thing that always
     * works; it stops being the thing that always wins.
     */
    override val launchDelayMs: Long = 6_000L

    private val binary = File(context.applicationInfo.nativeLibraryDir, LIBRARY)

    /**
     * Where the program keeps its account and its last known good gateway.
     *
     * Kept across runs deliberately: registration is the slowest part of a cold start, and the
     * saved gateway is what turns the second connection of the day into a quick one.
     */
    private val cache = File(context.filesDir, "edge").apply { mkdirs() }

    /** Which rung worked last time. Survives restarts; it is one word in one file. */
    private val memory = File(context.filesDir, "edge-mode")

    @Volatile private var process: Process? = null

    /**
     * The last complaint the program made before it stopped.
     *
     * 🚨 Kept because not keeping it cost an entire release. The output was being read for one
     * success line and otherwise thrown away, so when the program died on its first breath the app
     * could only report that nothing answered — with the actual reason, which the program had
     * printed, discarded a few microseconds earlier.
     */
    @Volatile private var lastWords: String = ""

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
        error(lastWords.ifBlank { "no edge gateway answered" })
    }

    /**
     * Yes, and cheaply.
     *
     * Cloudflare's edge is anycast: the address that stopped answering and the address that will
     * answer next are frequently the same address routed somewhere else. Restarting behind the
     * same loopback port re-selects a gateway, re-handshakes and re-validates, and the tun above
     * it never notices — the difference between a stalled second and a visible drop.
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

    /**
     * 🚨 Every rung must answer all four of the program's questions — protocol, scan mode, IP
     * version, and whether to reuse the last gateway. It is interactive by default: leave one
     * unanswered and it stops at a prompt nobody will ever type into, which from out here is
     * indistinguishable from a network that went silent.
     */
    private enum class Mode(val flags: List<String>, val windowMs: Long) {
        /** First gateway that answers. Up in seconds when the network allows it. */
        TURBO(
            listOf("--masque", "--turbo", "-4", "--quick-reconnect"),
            30_000L,
        ),

        /**
         * Whole ranges rather than a sample, with the obfuscation profile built for heavily
         * filtered networks and the client hello split across packets. The saved gateway is
         * deliberately ignored here — had the easy route worked, turbo would already have won.
         */
        THOROUGH(
            listOf(
                "--masque", "--thorough", "-4", "--no-quick-reconnect",
                "--noize", "gfw", "--fragment",
            ),
            60_000L,
        ),

        /** One tunnel inside another, for equipment that reads the outer one. */
        GOOL(
            listOf("--gool", "--thorough", "-4", "--no-quick-reconnect", "--noize", "gfw"),
            60_000L,
        ),
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
     * @return true when the tunnel is validated and the proxy is serving
     */
    private fun run(mode: Mode): Boolean {
        val command = listOf(
            binary.absolutePath,
            "--bind", "$LOOPBACK:$PORT",
            "--config", File(cache, CONFIG).absolutePath,
            "--dns", DNS,
            "--log-level", "info",
        ) + mode.flags

        val started = runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                // Somewhere to put whatever it writes beside its config, and a home that exists.
                // A program that cannot save its account has to register again on every launch,
                // which is both slow and a very recognisable thing to be doing.
                .apply { environment()["HOME"] = cache.absolutePath }
                .directory(cache)
                .start()
        }.getOrNull()

        if (started == null) {
            log("$label could not be started")
            return false
        }
        process = started

        val deadline = System.currentTimeMillis() + mode.windowMs
        val reader = started.inputStream.bufferedReader()

        // 🚨 The output has to be drained whatever happens. A process whose pipe fills up blocks on
        // its next write and stops making progress, and from out here that is indistinguishable
        // from a network that went quiet — the tunnel would simply never come up, with no error
        // anywhere to say why.
        val ready = drainUntilReady(reader, deadline)

        if (!ready) {
            val reason = lastWords.ifBlank { "it said nothing at all" }
            log("$label found nothing on the ${mode.name.lowercase()} route: $reason")
            stop()
            return false
        }

        if (!waitForPort()) {
            log("$label validated a tunnel and the port never opened")
            stop()
            return false
        }

        // Keep draining in the background for the same pipe-fills-up reason as above. Nothing
        // reads what it prints from here on; it is thrown away deliberately, because those lines
        // carry gateway addresses and a pasted log should not tell anyone where this app goes.
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

            // Only complaints are kept. The program narrates its progress cheerfully, and the last
            // cheerful line before a timeout explains nothing. Redacted on the way in rather than
            // on the way out, so an address it printed is never held here in the clear waiting to
            // be logged later.
            if (line.contains(TROUBLE) || line.contains(FAILURE)) {
                lastWords = Redact.line(line.substringAfter("] ", line)).take(MAX_REASON)
            }
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
        private const val LIBRARY = "libaether.so"
        private const val LOOPBACK = "127.0.0.1"
        private const val CONFIG = "aether.toml"

        /**
         * Its own loopback port, shared with nothing. A stale process left behind by a previous
         * run would otherwise be mistaken for this one and reported as a working tunnel.
         */
        const val PORT = 18086

        /** Resolvers used inside the tunnel, once it exists. */
        private const val DNS = "1.1.1.1,1.0.0.1"

        /**
         * 🔑 The program prints this once, and only after data has travelled end to end through
         * the finished tunnel. Matching on it rather than on the port opening is the difference
         * between knowing and hoping. Both transports share this wording.
         */
        private const val READY = "tunnel validated (end-to-end data confirmed)"

        /** How the program marks a warning and an outright failure, respectively. */
        private const val TROUBLE = "[!]"
        private const val FAILURE = "Error:"

        private const val MAX_REASON = 160
        private const val PORT_WAIT_MS = 4_000L
    }
}
