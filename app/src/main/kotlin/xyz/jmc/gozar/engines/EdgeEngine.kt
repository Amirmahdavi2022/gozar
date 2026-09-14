package xyz.jmc.gozar.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     *
     * 🚨 It must stay larger than the rungs added together, and that is a real constraint rather
     * than a margin: the ladder is cut off wherever this expires, so a rung whose window falls
     * past it can never run at all, on any network, and nothing says so. The windows below come
     * to 300 seconds, plus each rung's port allowance on top. Change one and change this.
     */
    override val deadlineMs: Long = 360_000L

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

    /**
     * Which rung worked last time. Survives restarts; it is one word in one file.
     *
     * 🚨 The name carries a version and it has to change whenever the ladder does. A phone that
     * has been running this app already has a rung remembered here, and [ladder] puts the
     * remembered one first — so a device upgrading into a new rung would have gone on happily
     * using the old winner and never once tried it. Two releases have already been lost to
     * exactly this shape of bug: code that only ever runs on an upgraded device, and so never
     * runs anywhere it can be seen failing. Bumping the name costs one slower connect, once.
     */
    private val memory = File(context.filesDir, "edge-mode-2")

    /** Held for the whole walk down the rungs. See the note in [start]. */
    private val ladderLock = Mutex()

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

    /** The last thing it said before a rung ran out of time without ever complaining. */
    @Volatile private var furthest: String = ""

    /**
     * How long the winning rung took to open its port, printed with the win.
     *
     * Kept because the allowance that broke the two-hop rung was picked without one of these ever
     * having been measured. The next person to change it should be reading a number, not guessing.
     */
    @Volatile private var lastOpenMs: Long = -1

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        check(binary.isFile && binary.canExecute()) { "no edge program in this build" }

        // 🚨 One ladder at a time, and this is not defensive tidiness — it is a defect seen in a
        // log. The racer starts this engine to race with, and the standby keeper starts it again
        // to hold in reserve; when the second call arrived while the first was still stepping
        // down the rungs, the two walked the ladder together on one fixed loopback port. The
        // first process kept the port, and every rung the second tried died instantly with
        // "Address already in use" — so the whole ladder below the first rung was wiped out, and
        // the message said nothing about there being two of us.
        ladderLock.withLock {
            for (mode in ladder()) {
                stop()
                if (run(mode)) {
                    // Named, because "up on path 4" was true for four builds running and told
                    // nobody which of four very different rungs had actually carried it.
                    log("path 4 is out via ${mode.described}, port open in ${lastOpenMs}ms")
                    remember(mode)
                    return@withContext Session(PORT, name, shape)
                }
            }
            stop()
        }
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
        ladderLock.withLock {
            for (mode in ladder()) {
                stop()
                if (run(mode)) {
                    log("path 4 is back via ${mode.described}, port open in ${lastOpenMs}ms")
                    remember(mode)
                    return@withContext true
                }
            }
            stop()
        }
        false
    }

    override fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            running.waitFor()
        }
        // Waiting for the child to exit is not the same as waiting for its listener to go. The
        // next rung binds the same address within milliseconds of this returning, and a socket
        // the kernel has not finished releasing is indistinguishable, from out here, from a
        // second copy of us holding it.
        waitForPortFree()
    }

    /** Blocks until nothing answers on the loopback port, or the short grace runs out. */
    private fun waitForPortFree() {
        val deadline = System.currentTimeMillis() + PORT_FREE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val busy = Socket().use { probe ->
                runCatching {
                    probe.connect(InetSocketAddress(LOOPBACK, PORT), 200)
                    true
                }.getOrDefault(false)
            }
            if (!busy) return
            runCatching { Thread.sleep(100) }.onFailure { return }
        }
    }

    // -- the ladder ---------------------------------------------------------------------------

    /**
     * 🚨 Every rung must answer all four of the program's questions — protocol, scan mode, IP
     * version, and whether to reuse the last gateway. It is interactive by default: leave one
     * unanswered and it stops at a prompt nobody will ever type into, which from out here is
     * indistinguishable from a network that went silent.
     */
    private enum class Mode(
        val flags: List<String>,
        val windowMs: Long,
        /** What to call this rung in the log, where "MIM" would mean nothing to a reader. */
        val described: String,
        /**
         * How long to let the program open its local port after it says the tunnel is good.
         *
         * 🚨 Per rung, because one number for all of them threw away a working tunnel. Four
         * seconds was measured as plenty for a single hop and was quietly applied to the two-hop
         * rung as well — which validated its tunnel in thirteen seconds, was given four to start
         * listening, missed, and was killed and written off as a failure. The rung worked. The
         * allowance did not.
         */
        val portWaitMs: Long = 8_000L,
        /**
         * The line that means this rung is actually serving.
         *
         * 🚨 Per rung, because the shared one is a lie on the two-hop rung. Read in the core's own
         * source: "tunnel validated (end-to-end data confirmed)" is printed by the OUTER hop, and
         * the two-hop path then goes hunting for an inner edge — up to six candidates at twelve
         * seconds each — and binds its local port only once one of them answers. Watching for the
         * outer line there means calling the rung ready roughly a minute before it can be, then
         * killing it for not having opened a port it had not reached the code to open yet.
         */
        val readyMarker: String = READY,
    ) {
        /**
         * 🚨 First on purpose, and the reason is the complaint that got this whole path held back.
         *
         * Every other rung here exits in the SAME country the phone is in, because the network
         * behind them is location-preserving by design — which is why this path kept winning
         * races with a tunnel that unblocked nothing. Masque-in-masque runs a second hop inside
         * the first, on the same carrier, and the address you come out of is the inner hop's, not
         * the outer one's. That is the one setting here that changes the answer to "where does
         * this come out".
         *
         * It costs a second hop to build, so it is given a longer window than turbo and is tried
         * first rather than fastest-first: a slower way out that lands abroad is worth more than
         * a quick one that lands next door. If it fails, the ladder below is exactly what it was.
         */
        MIM(
            // 🚨 The quick sweep, not the balanced one, and this was measured rather than chosen.
            // With the balanced sweep this rung died on the core's own "scan deadline reached"
            // after two minutes — and then the very next rung, on the same network seconds later,
            // found a gateway in four seconds with the quick sweep. The gateway was never the
            // problem; the way of looking for it was. A sweep the network will not sit still for
            // is worth nothing twice over here, because this rung has to do it before it can even
            // start on the hop that matters.
            listOf("--mim", "--turbo", "-4", "--quick-reconnect"),
            // 🚨 Sized from the core's source rather than guessed at, which is what the last four
            // numbers here were. After the outer hop is up the inner hunt alone can take six
            // candidates at twelve seconds each — seventy two seconds in which the rung is
            // working and silent — and the scan and the outer hop come before any of that.
            150_000L,
            described = "two hops",
            // Printed immediately after the socks listener is bound, so by the time this is seen
            // the port is already open and the default allowance below is ample.
            readyMarker = "masque-in-masque ready",
        ),

        /** First gateway that answers. Up in seconds when the network allows it. */
        TURBO(
            listOf("--masque", "--turbo", "-4", "--quick-reconnect"),
            30_000L,
            described = "one hop, quick sweep",
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
            described = "one hop, full sweep",
        ),

        /** One tunnel inside another, for equipment that reads the outer one. */
        GOOL(
            listOf("--gool", "--thorough", "-4", "--no-quick-reconnect", "--noize", "gfw"),
            60_000L,
            described = "tunnel in tunnel",
        ),
    }

    /**
     * The rungs to try, in order: the two-hop one, then the remembered winner, then the rest.
     *
     * 🚨 [MIM] is pinned to the front and the memory is never allowed to move it, and that is the
     * whole point of this function. The memory exists to make the next connect faster by starting
     * at whatever worked last time — which is right for every rung here except one. MIM is not a
     * faster way to the same place, it is the only rung that changes which country the tunnel
     * comes out of; a single-hop rung beating it on speed is exactly what it is supposed to lose
     * to on merit and win against on purpose.
     *
     * This cost four builds to find. MIM failed once, the next rung succeeded and was written
     * down as the winner, and from then on it was tried first, came up in under four seconds, and
     * MIM was never executed again on that device — so every later change to it shipped, ran, and
     * did nothing, while the log said only "up on path 4" and named no rung at all. The exit
     * stayed in the country it had always been in, and nothing anywhere said why.
     */
    private fun ladder(): List<Mode> {
        val rest = Mode.entries.filter { it != Mode.MIM }
        val remembered = runCatching { memory.readText().trim() }.getOrNull()
        val first = rest.firstOrNull { it.name == remembered } ?: return Mode.entries
        return listOf(Mode.MIM, first) + rest.filter { it != first }
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
        furthest = ""
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

        // 🚨 Without this the window is a suggestion. The drain below checks the clock once per
        // line, so a core that goes quiet is never cut off at all — it is cut off whenever it
        // next feels like speaking. A rung with a seventy-five second window was measured running
        // for a hundred and twenty-six, and it ended when the core gave up on itself rather than
        // when we did. Killing the process is what makes the read return.
        val watchdog = Thread {
            runCatching {
                val left = deadline - System.currentTimeMillis()
                if (left > 0) Thread.sleep(left)
                if (process === started && started.isAlive) started.destroy()
            }
        }.apply { isDaemon = true }.also { it.start() }

        // 🚨 The output has to be drained whatever happens. A process whose pipe fills up blocks on
        // its next write and stops making progress, and from out here that is indistinguishable
        // from a network that went quiet — the tunnel would simply never come up, with no error
        // anywhere to say why.
        val ready = drainUntilReady(reader, deadline, mode.readyMarker)
        watchdog.interrupt()

        if (!ready) {
            val reason = lastWords.ifBlank {
                if (furthest.isBlank()) "it said nothing at all" else "it got as far as: $furthest"
            }
            log("$label found nothing on the ${mode.name.lowercase()} route: $reason")
            stop()
            return false
        }

        val opened = waitForPort(mode.portWaitMs)
        lastOpenMs = opened
        if (opened < 0) {
            log("$label validated a tunnel and the port never opened in ${mode.portWaitMs / 1000}s")
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

    private fun drainUntilReady(reader: BufferedReader, deadline: Long, marker: String): Boolean {
        var complained = false
        while (System.currentTimeMillis() < deadline) {
            val running = process ?: return false
            if (!running.isAlive && !reader.ready()) return false

            val line = runCatching { reader.readLine() }.getOrNull() ?: return false
            if (line.contains(marker)) return true

            // Only complaints are kept. The program narrates its progress cheerfully, and the last
            // cheerful line before a timeout explains nothing. Redacted on the way in rather than
            // on the way out, so an address it printed is never held here in the clear waiting to
            // be logged later.
            if (line.contains(TROUBLE) || line.contains(FAILURE)) {
                lastWords = Redact.line(line.substringAfter("] ", line)).take(MAX_REASON)
                complained = true
            } else if (!complained) {
                // 🚨 Kept because "it said nothing at all" is what the log actually printed when
                // the two-hop rung timed out, and it was worth nothing at all to read. The
                // program narrates every stage it reaches; when it never complains and simply
                // runs out of time, the stage it had reached is the entire diagnosis. Overwritten
                // the moment a real complaint arrives, and redacted on the way in like one.
                furthest = Redact.line(line.substringAfter("] ", line)).take(MAX_REASON)
            }
        }
        return false
    }

    /** @return how long the port took to answer, or -1 if it never did. */
    private fun waitForPort(allowanceMs: Long): Long {
        val began = System.currentTimeMillis()
        val deadline = began + allowanceMs
        while (System.currentTimeMillis() < deadline) {
            if (process == null) return -1
            Socket().use { probe ->
                runCatching {
                    probe.connect(InetSocketAddress(LOOPBACK, PORT), 300)
                    return System.currentTimeMillis() - began
                }
            }
            runCatching { Thread.sleep(50) }.onFailure { return -1 }
        }
        return -1
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

        /** Grace for the kernel to release the listener after the child is gone. */
        private const val PORT_FREE_WAIT_MS = 3_000L
    }
}
