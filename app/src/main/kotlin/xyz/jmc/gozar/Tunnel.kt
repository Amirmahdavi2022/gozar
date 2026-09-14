package xyz.jmc.gozar

import android.content.Context
import IPtProxy.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.jmc.gozar.core.Diary
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.HttpProbe
import xyz.jmc.gozar.core.Racer
import xyz.jmc.gozar.core.Scoreboard
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.engines.BridgeStore
import xyz.jmc.gozar.direct.FilePoolStore
import xyz.jmc.gozar.direct.PoolStore
import xyz.jmc.gozar.direct.ExitLocation
import xyz.jmc.gozar.direct.Provisioner
import xyz.jmc.gozar.direct.count
import xyz.jmc.gozar.engines.defaultEngines
import java.io.File

/**
 * One place that knows how the pieces fit.
 *
 * A singleton because two of the things it owns insist on it: IPtProxy is
 * explicit that a second Controller must never exist, and the native tunnel
 * keeps its state in process globals. Fighting that with dependency injection
 * would buy nothing and risk a second instance quietly breaking both.
 */
object Tunnel {

    enum class Phase { DOWN, WORKING, UP, FAILED }

    private val _phase = MutableStateFlow(Phase.DOWN)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /**
     * Where the tunnel comes out, once something on the far side has told us.
     *
     * Empty until it is known, and empty again the moment the tunnel moves. It is deliberately
     * not remembered across a switch: the app dials endpoints from a public list and has no idea
     * where any of them sit, so the country on screen is only ever the answer to a question asked
     * through the connection that is live right now. A stale flag left over from the previous
     * endpoint is worse than no flag, because someone is reading that line to decide whether it
     * is safe to sign into something.
     */
    private val _exit = MutableStateFlow("")
    val exit: StateFlow<String> = _exit.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var controller: Controller? = null
    private var racer: Racer? = null
    private var board: Scoreboard? = null
    private var pool: PoolStore? = null
    private var tun2socks: Tun2Socks? = null
    private var watcher: NetworkWatcher? = null

    /**
     * The tun we were handed. Kept because a failover has to re-attach the same
     * device to a different SOCKS port, and the alternative — tearing the tun
     * down and building a new one — is a visible drop on the user's screen,
     * which is the exact thing the standby engine exists to avoid.
     */
    @Volatile private var tunFd: Int = -1

    /**
     * The SOCKS port the tun is attached to right now, or -1 when nothing is up.
     *
     * 🔑 This is what makes the paths cooperate instead of merely taking turns. Tor on its own
     * bootstraps on this owner's networks and then carries nothing, so when one of the other
     * paths is already working, Tor is pointed at it and goes out through it. The endpoint-list
     * refresh uses the same number for the same reason: the lists live exactly where they are
     * blocked.
     */
    @Volatile private var livePort: Int = -1

    /** What the screen reads its counters from. Zeroes while nothing is up. */
    val traffic: TrafficSource get() = tun2socks ?: NoTraffic

    /**
     * Brings everything up around an established tun.
     *
     * Order matters: the race has to finish before the tun is wired to
     * anything, because pointing tun2socks at a port that is not yet carrying
     * traffic would send every packet into a hole.
     */
    suspend fun bringUp(context: Context, tunFd: Int): Boolean {
        _phase.value = Phase.WORKING
        this.tunFd = tunFd
        livePort = -1
        note("looking for a way out")

        val engines = engines(context)
        val racer = racer(context, engines)
        val network = (watcher ?: NetworkWatcher(context).also { watcher = it }).current()
        note("network reads as ${network.value}")

        val session = racer.connect(network)
        if (session == null) {
            note("nothing got through")
            _phase.value = Phase.FAILED
            return false
        }

        val wired = wire(context, session)
        _phase.value = if (wired) Phase.UP else Phase.FAILED
        if (wired) {
            locate(session.socksPort)
            provision(session.socksPort)
        }
        return wired
    }

    /**
     * Tops the endpoint list up through the tunnel that is already carrying traffic.
     *
     * Runs after the connection is up and never blocks it. The point is that the two engines feed
     * each other: the lists the fast engine dials are hosted where they are blocked, so on a bad
     * network Tor is what reaches them, and after one such fetch the fast engine has somewhere to
     * go and usually wins the next race outright.
     *
     * Only when the pool is thin. Eight files through three volunteer hops is not something to
     * spend on every connect for a list that is already good enough.
     */
    private fun provision(socksPort: Int) {
        val store = pool ?: return
        val current = store.count()
        if (current >= HEALTHY_POOL) return

        scope.launch {
            note("topping up the endpoint list through the tunnel")
            val size = Provisioner.refresh(store, socksPort, ::note)
            if (size > 0) {
                board?.markBootstrapped("quic")
                note("endpoint list now holds $size")
            }
        }
    }

    /**
     * Asks the far side of the tunnel which country it is in.
     *
     * Never blocks the connection: the tunnel is already carrying traffic by the time this runs,
     * and if every provider refuses, the card simply stays blank. Somebody waiting an extra
     * second to connect so the app can decorate itself would be a bad trade.
     */
    private fun locate(socksPort: Int) {
        _exit.value = ""
        scope.launch {
            // 🚨 Retried, because asking once was not good enough and the log said so plainly:
            // "could not tell where the tunnel comes out", three seconds after connecting, on a
            // tunnel that had barely started moving bytes. A brand new tunnel to a distant public
            // endpoint is at its slowest in its first few seconds — that is when the route is
            // still settling and when whatever else the phone had queued is all going through it
            // at once. Judging it then and never asking again meant the card was blank for the
            // entire session even when the tunnel came good a moment later.
            //
            // The delays grow, so a healthy tunnel answers on the first try and costs nothing,
            // and a slow one gets a fair hearing without a request every few seconds forever.
            for ((attempt, waitMs) in ATTEMPT_WAITS.withIndex()) {
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                if (_phase.value != Phase.UP) return@launch

                val place = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ExitLocation.lookup(LOOPBACK, socksPort, LOCATE_TIMEOUT_MS)
                    }
                }.getOrNull()

                if (place != null) {
                    _exit.value = place.toString()
                    // 🚨 The address itself is deliberately NOT written to the diary. The point of
                    // scrubbing endpoints out of the log is that a pasted log should not tell
                    // anyone which servers this app uses, and an exit address is exactly that.
                    note("the tunnel comes out in ${place.country}")
                    return@launch
                }
                if (attempt == ATTEMPT_WAITS.lastIndex) {
                    note("could not tell where the tunnel comes out")
                }
            }
        }
    }

    /** How long to wait before each attempt at the location lookup. */
    private val ATTEMPT_WAITS = longArrayOf(0, 6_000, 15_000, 30_000).toList()

    private const val LOOPBACK = "127.0.0.1"
    private const val LOCATE_TIMEOUT_MS = 12_000

    /**
     * Below this the list is worth refreshing; above it, leave the tunnel alone.
     *
     * Raised when the sources were cut down to three hysteria2 files. A refresh used to mean half
     * a megabyte of mixed dumps through the tunnel, so it was worth avoiding; measured on the
     * live files it is now 82 KB, and the servers in it were republished within the last quarter
     * of an hour. At that price a top-up on most connects is a better trade than a pool slowly
     * going stale.
     */
    private const val HEALTHY_POOL = 120

    /**
     * The endpoint list shipped in the apk, written at build time by scripts/fetch-seed.sh.
     *
     * Stale by the hour it is installed, and that is expected: it only has to be good enough to
     * get one tunnel up, after which the app refreshes the pool itself through that tunnel.
     */
    private const val SEED_ASSET = "seed.txt"

    fun tearDown() {
        _exit.value = ""
        tun2socks?.stop()
        tunFd = -1
        livePort = -1
        val current = racer
        scope.launch { current?.stop() }
        _phase.value = Phase.DOWN
    }

    /**
     * Attaches the tun to whichever engine is carrying traffic right now.
     *
     * Called on the first connect and again on every switch. Without the second
     * call the standby engine is decoration: the racer moves over, the screen
     * stays green, and packets keep being posted to a port whose process has
     * already gone.
     */
    private fun wire(context: Context, session: Session): Boolean {
        val fd = tunFd
        if (fd < 0) return false

        val tunnel = tun2socks ?: Tun2Socks(context.filesDir).also { tun2socks = it }
        val wired = tunnel.start(fd, session.socksPort, ::note)
        livePort = if (wired) session.socksPort else -1
        note(if (wired) "tun wired on ${session.socksPort}" else "tun would not attach")
        return wired
    }

    private fun engines(context: Context): List<Engine> {
        val ipt = controller ?: Controller(
            File(context.cacheDir, "pt").apply { mkdirs() }.absolutePath,
            true,   // logging on: without it a failure here is invisible
            false,  // but scrubbed, so addresses never reach the log
            "INFO",
            null,
        ).also { controller = it }

        val bridges = BridgeStore(context)
        val store = pool ?: FilePoolStore(
            directory = context.filesDir,
            // Read at load and save time, not captured once, so changing network mid-session
            // moves to that network's own scores without anything having to be rebuilt.
            network = { (watcher ?: NetworkWatcher(context).also { watcher = it }).current().value },
            seed = {
                runCatching {
                    context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
                }.getOrNull()
            },
        ).also { pool = it }
        val network = { (watcher ?: NetworkWatcher(context).also { watcher = it }).current().value }
        return defaultEngines(
            context, ipt, { bridges.linesFor(it) }, store, { livePort }, network, ::note,
        )
    }

    private fun racer(context: Context, engines: List<Engine>): Racer =
        racer ?: Racer(
            engines = engines,
            board = Scoreboard(FileScoreStore(File(context.filesDir, "scoreboard.json")))
                .also { board = it },
            prober = HttpProbe(log = ::note),
            scope = scope,
            log = ::note,
            onSwitch = { session ->
                // A switch means a different server, and usually a different country. Asking
                // again is the only way the card can be true rather than left over.
                wire(context, session).also { if (it) locate(session.socksPort) }
            },
            // The tun's own counters. Read through the property rather than captured, because
            // the tun2socks behind it is replaced on every switch and a captured reference would
            // keep reporting the totals of a bridge that no longer exists.
            traffic = { traffic.sample().let { it.up to it.down } },
        ).also { racer = it }

    private fun note(message: String) {
        Diary.write(message)
        android.util.Log.i("gozar", message)
    }
}
