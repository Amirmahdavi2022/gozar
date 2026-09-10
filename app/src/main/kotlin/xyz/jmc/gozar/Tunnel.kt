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
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.HttpProbe
import xyz.jmc.gozar.core.Racer
import xyz.jmc.gozar.core.Scoreboard
import xyz.jmc.gozar.engines.BridgeStore
import xyz.jmc.gozar.engines.TorAndroidDriver
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
    private var tun2socks: Tun2Socks? = null
    private var watcher: NetworkWatcher? = null

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

        val engines = engines(context)
        val racer = racer(context, engines)
        val network = (watcher ?: NetworkWatcher(context).also { watcher = it }).current()

        val session = racer.connect(network)
        if (session == null) {
            _phase.value = Phase.FAILED
            return false
        }

        val tunnel = tun2socks ?: Tun2Socks(context.filesDir).also { tun2socks = it }
        val wired = tunnel.start(tunFd, session.socksPort)

        _phase.value = if (wired) Phase.UP else Phase.FAILED
        return wired
    }

    fun tearDown() {
        tun2socks?.stop()
        val current = racer
        scope.launch { current?.stop() }
        _phase.value = Phase.DOWN
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
        return defaultEngines(ipt, TorAndroidDriver(context)) { bridges.linesFor(it) }
    }

    private fun racer(context: Context, engines: List<Engine>): Racer =
        racer ?: Racer(
            engines = engines,
            board = Scoreboard(FileScoreStore(File(context.filesDir, "scoreboard.json"))),
            prober = HttpProbe(),
            scope = scope,
            log = { message -> android.util.Log.i("gozar", message) },
        ).also { racer = it }
}
