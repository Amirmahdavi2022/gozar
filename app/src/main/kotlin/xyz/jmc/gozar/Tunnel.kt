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
        if (wired) provision(session.socksPort)
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
                board?.markBootstrapped("direct")
                note("endpoint list now holds $size")
            }
        }
    }

    /** Below this the list is worth refreshing; above it, leave the tunnel alone. */
    private const val HEALTHY_POOL = 60

    fun tearDown() {

        tun2socks?.stop()
        tunFd = -1
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
        note(if (wired) "tun wired to ${session.engine} on ${session.socksPort}" else "tun would not attach")
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
        val store = pool ?: FilePoolStore(File(context.filesDir, "pool.txt")).also { pool = it }
        return defaultEngines(context, ipt, { bridges.linesFor(it) }, store, ::note)
    }

    private fun racer(context: Context, engines: List<Engine>): Racer =
        racer ?: Racer(
            engines = engines,
            board = Scoreboard(FileScoreStore(File(context.filesDir, "scoreboard.json")))
                .also { board = it },
            prober = HttpProbe(log = ::note),
            scope = scope,
            log = ::note,
            onSwitch = { session -> wire(context, session) },
        ).also { racer = it }

    private fun note(message: String) {
        Diary.write(message)
        android.util.Log.i("gozar", message)
    }
}
