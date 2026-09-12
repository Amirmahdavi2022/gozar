package xyz.jmc.gozar.engines

import IPtProxy.Controller
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.torproject.jni.TorService
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape
import xyz.jmc.gozar.direct.HysteriaEngine
import xyz.jmc.gozar.direct.PoolStore
import xyz.jmc.gozar.direct.SocksProbe
import java.io.File

/**
 * Transport names as IPtProxy knows them. These strings are the API — they are
 * matched inside the Go library, so they are not ours to prettify.
 */
object Transport {
    const val SNOWFLAKE = "snowflake"
    const val WEBTUNNEL = "webtunnel"
    const val MEEK_LITE = "meek_lite"
    const val OBFS4 = "obfs4"
    const val DNSTT = "dnstt"

    val ALL = listOf(SNOWFLAKE, WEBTUNNEL, MEEK_LITE, OBFS4, DNSTT)
}

/**
 * Tor, driven through every pluggable transport we have bridge lines for.
 *
 * This is deliberately ONE engine rather than one per transport, and that is a
 * correction rather than a simplification. A transport is not a way out on its
 * own: it speaks the PT protocol and expects Tor on the other side of it. There
 * is exactly one Tor — it is an Android Service, it reads one torrc, and it
 * publishes one SOCKS port — so five "engines" that each wrote that same file
 * and each called startService on that same service were never five bets. They
 * were one Tor with five labels, and the second one to give up called
 * stopService and took the winner down with it.
 *
 * What Tor is genuinely good at is being handed several bridges at once and
 * choosing between them itself, which is what Tor Browser does. So every
 * transport that starts gets a ClientTransportPlugin line and every bridge line
 * we hold goes in the same file, and Tor races them internally.
 *
 * A second bet has to be a different program with its own socket. That is the
 * next engine, not another name for this one.
 *
 * <b>Two routes out, and the second one is why the paths are worth having together.</b> On this
 * owner's own network Tor bootstrapped to a hundred percent, twice, on two different operators,
 * published its port — and then carried nothing at all. That is not a broken bootstrap and no
 * amount of waiting fixes it: the bridge answered, the circuit was built, and the streams through
 * it went nowhere. So when another path is already carrying traffic, this one can be told to make
 * its own connections through that path instead of out of this network directly: plain Tor, no
 * bridges, no transports, one line of configuration. Tor is then three hops behind a tunnel that
 * already works, which is slower and completely different to block.
 *
 * 🚨 Worth being honest about what that costs. Chained, this stops being an independent bet — if
 * the path underneath it dies, this dies with it. So the direct route with bridges is always
 * tried first, and the chained route is what happens when the direct one has proved, on this
 * network, that it comes up and carries nothing. Which route won is remembered per network, so
 * the next connect does not pay for the discovery again.
 */
class TorEngine(
    private val context: Context,
    private val controller: Controller,
    private val bridges: (String) -> List<String>,
    /**
     * The SOCKS port of whichever path is carrying traffic right now, or -1 when nothing is.
     *
     * Read at the moment it is needed rather than captured, because the answer changes every time
     * the racer moves the tunnel, and a number captured at construction would point at a process
     * that is long gone.
     */
    private val carrier: () -> Int = { -1 },
    /** What the network calls itself, so the winning route is remembered against the right one. */
    private val network: () -> String = { "unknown" },
    private val log: (String) -> Unit = {},
) : Engine {

    override val name: String = "tor"

    override val shape: Shape = Shape.MIXED

    /**
     * Comfortably longer than the bootstrap timeout below, so that when Tor
     * runs out of time it is Tor's own message that comes back rather than a
     * bare cancellation from the racer. The previous 25s was shorter than a
     * Snowflake rendezvous, which is why nothing ever won.
     */
    /**
     * One bootstrap plus one real request through it.
     *
     * Only ever one attempt now - see [start] for why a second one inside the same process is not
     * survivable - so this is two minutes of bootstrap and forty seconds to prove the circuit
     * carries, and not a second more.
     */
    override val deadlineMs: Long = 180_000L

    /**
     * Measured on a real phone: a full bootstrap took 57 seconds and the first request after it
     * still had not returned in 8. Nothing was wrong — the circuit existed, the stream through it
     * was simply being opened for the first time. Half a minute is the honest cost of Tor's first
     * request from a filtered network, and refusing to wait it out means throwing away the one
     * engine that got through.
     */
    // 🚨 Was forty-five seconds, and the probe tries three targets in turn, so a tunnel that came
    // up and carried nothing took over two minutes to be declared dead. That is survivable now
    // that the race no longer waits for it, but it is still a long time to hold an engine open on
    // the chance it answers. A circuit that is going to carry a two hundred byte reply carries it
    // well inside this.
    override val probeTimeoutMs: Int = 15_000

    /** The fallback, and the second thing tried. See Engine.label for why this is not the name. */
    override val label: String = "path 2"

    override val needsBootstrap: Boolean
        get() = Transport.ALL.none { bridges(it).isNotEmpty() }

    private val started = mutableListOf<String>()

    /** The hop chosen by [chooseRoute], so the check and the torrc cannot disagree. */
    @Volatile private var carrierPort: Int = -1

    /** See [start]: one Tor per process, because its shutdown takes the process with it. */
    @Volatile private var launchedOnce: Boolean = false

    @Volatile private var bound = false
    @Volatile private var binder: TorService.LocalBinder? = null
    @Volatile private var died = false
    @Volatile private var lastPhase = ""
    private var receiver: BroadcastReceiver? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName?, service: IBinder?) {
            binder = service as? TorService.LocalBinder
        }

        override fun onServiceDisconnected(component: ComponentName?) {
            binder = null
        }
    }

    /** How this engine reaches the rest of the world. */
    private enum class Route(val bootstrapMs: Long) {
        /**
         * Out of this network directly, through whichever pluggable transports started.
         *
         * Two minutes, and it is generous on purpose: snowflake has to find a volunteer through a
         * broker before tor can begin, and on a bad night that is slow without being broken.
         */
        BRIDGES(120_000L),

        /**
         * Out through the path that is already carrying traffic. Plain tor, no transports, so a
         * rendezvous with a volunteer is not part of the wait and this is the quicker of the two.
         */
        CARRIER(75_000L),
    }

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        // 🚨 Once per process, and this is not a tidy-up. Tor is not a program we launch, it is a
        // native library linked into this process, and its shutdown path ends the process rather
        // than returning. Two device logs showed exactly that: the engine tore Tor down, started
        // it again for its second route, and the whole app was gone within a second - the next
        // launch reported "the last run ended in a crash in native code". So this asks for one
        // Tor per run of the app, ever, and if that one did not work the route memory below is
        // what makes the next launch try the other way instead.
        check(!launchedOnce) { "tor has already had its turn this run" }

        val route = chooseRoute()
        launchedOnce = true

        when (route) {
            Route.BRIDGES -> {
                val plugins = startTransports()
                check(plugins.isNotEmpty()) { "no transport came up, so tor has nothing to dial through" }
                log("path 2 has ${plugins.size} transports listening")
                writeTorrc(plugins, hop = -1)
            }

            Route.CARRIER -> {
                log("path 2 is going out through the path that already works")
                writeTorrc(emptyMap(), hop = carrierPort)
            }
        }

        launchService()

        val up = withTimeoutOrNull(route.bootstrapMs) { awaitBootstrap() }
        check(up == true) {
            remember(other(route))
            "tor got no further than ${lastPhase.ifEmpty { "not started" }} " +
                "in ${route.bootstrapMs / 1000}s"
        }

        val port = socksPort()

        // 🚨 Asked here rather than left to the racer, because the answer decides what the NEXT
        // launch does. A device log showed this twice on two networks: bootstrapped to a hundred
        // percent, published its port, and then nothing came back through it. That is not a slow
        // bootstrap and waiting longer does not fix it - the bridge answered and the streams
        // through the circuit went nowhere. Writing the other route down here is the whole
        // mechanism by which the app gets out of it.
        if (!carries(port)) {
            remember(other(route))
            error("path 2 came up on $port and nothing came back through it")
        }

        remember(route)
        log("path 2 is up, socks on $port")
        Session(socksPort = port, engine = name, shape = shape)
    }

    /**
     * Which way out to try, decided before anything is started.
     *
     * There is only one attempt in a process, so this is the whole decision. The remembered
     * route leads, and the chained one is silently skipped when there is nothing live to chain
     * onto - which on a cold race is always, since if something were already carrying traffic
     * the racer would not still be looking.
     */
    private fun chooseRoute(): Route {
        val remembered = runCatching { memory().readText().trim() }.getOrNull()
        carrierPort = usableCarrier() ?: -1
        if (remembered == Route.CARRIER.name && carrierPort > 0) return Route.CARRIER
        return Route.BRIDGES
    }

    private fun other(route: Route): Route =
        if (route == Route.BRIDGES) Route.CARRIER else Route.BRIDGES

    /** Whether a real request through this port comes back. */
    private suspend fun carries(port: Int): Boolean = withContext(Dispatchers.IO) {
        SocksProbe.latencyMillis(LOOPBACK, port, SELF_CHECK_MS) >= 0
    }

    /**
     * The live path's port, if there is one and it is not this engine's own.
     *
     * Checked for real rather than trusted: after a failure the racer tears the old path down and
     * races again, and for those few seconds the remembered port belongs to a process that has
     * already exited. Chaining onto it would cost a whole bootstrap to discover that.
     */
    private fun usableCarrier(): Int? {
        val hop = runCatching { carrier() }.getOrNull() ?: return null
        if (hop <= 0) return null
        if (hop == binder?.service?.socksPort) return null
        if (!SocksProbe.opens(LOOPBACK, hop, CARRIER_CHECK_MS)) return null
        return hop
    }

    private fun remember(route: Route) {
        runCatching { memory().writeText(route.name) }
    }

    /** One file per network: a route that works on wifi says nothing about a mobile operator. */
    private fun memory(): File {
        val slug = network().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return File(context.filesDir, "tor-route-${slug.ifEmpty { "unknown" }}")
    }

    override fun stop() {
        receiver?.let { registered ->
            runCatching { LocalBroadcastManager.getInstance(context).unregisterReceiver(registered) }
        }
        receiver = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        binder = null
        runCatching { context.stopService(Intent(context, TorService::class.java)) }

        // Transports go down after Tor, not before: pulling the listener out
        // from under a live circuit is a harder failure than letting it close.
        started.toList().forEach { transport -> runCatching { controller.stop(transport) } }
        started.clear()
    }

    /**
     * Starts every transport we hold bridge lines for and reports where each
     * one is listening.
     *
     * One transport failing is not the engine failing. Snowflake alone is
     * enough for Tor to work, and obfs4 refusing to start should not take the
     * others with it.
     */
    private fun startTransports(): Map<String, Int> = buildMap {
        for (transport in Transport.ALL) {
            if (bridges(transport).isEmpty()) continue

            val port = runCatching {
                controller.start(transport, "")
                started += transport
                controller.port(transport).toInt()
            }.getOrElse { e ->
                log("one of path 2's transports did not start: ${e.message}")
                0
            }

            if (port > 0) put(transport, port) else log("one of path 2's transports reported no port")
        }
    }

    /**
     * The one place that decides what Tor does.
     *
     * "exec" is absent on purpose: the transports are already running as part
     * of this process, so Tor is told to talk to a socket rather than to launch
     * anything. SocksPort is absent on purpose too — TorService picks it and
     * tells us over its control port, and guessing it is how you end up
     * probing a port nothing is listening on.
     */
    private fun writeTorrc(plugins: Map<String, Int>, hop: Int) {
        val lines = buildList {
            add("ClientOnly 1")
            add("AvoidDiskWrites 1")

            if (hop > 0) {
                // 🔑 Plain Tor through a proxy, and deliberately with no bridges and no transports
                // at all. A bridge exists to hide that this is Tor from whoever is watching this
                // network - and on this route nobody on this network can see anything but the
                // tunnel underneath. Keeping the transports would add a hop, a second thing to
                // fail, and minutes to the bootstrap, in exchange for nothing.
                add("Socks5Proxy 127.0.0.1:$hop")
                return@buildList
            }

            add("UseBridges 1")
            plugins.forEach { (transport, port) ->
                add("ClientTransportPlugin $transport socks5 127.0.0.1:$port")
            }
            plugins.keys.forEach { transport ->
                bridges(transport).forEach { line -> add("Bridge $line") }
            }
        }

        runCatching { TorService.getTorrc(context).writeText(lines.joinToString("\n") + "\n") }
            .onFailure { log("could not write torrc: ${it.message}") }
    }

    /**
     * Starts the service and keeps a handle on it.
     *
     * The receiver goes on before the service does. The statuses that say it gave up are
     * transitions, and a transition missed is a transition gone.
     */
    private fun launchService() {
        died = false
        lastPhase = ""

        val manager = LocalBroadcastManager.getInstance(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_OFF, TorService.STATUS_STOPPING -> died = true
                }
            }
        }
        manager.registerReceiver(receiver, IntentFilter(TorService.ACTION_STATUS))
        this.receiver = receiver

        val intent = Intent(context, TorService::class.java)
        context.startService(intent)
        runCatching { context.bindService(intent, connection, 0) }.onSuccess { bound = true }
    }

    /**
     * Waits until Tor says it is fully bootstrapped, logging every step on the way.
     *
     * 🚨 Deliberately NOT the service's own ON status. That status was measured firing two seconds
     * in, while Tor's own bootstrap phase still read 20% — an encrypted directory connection, no
     * circuit, nothing that could carry a byte. Acting on it meant handing the racer a SOCKS port
     * that was not going to work for another minute, which the racer then correctly wrote off as a
     * dead engine. Tor's bootstrap phase is Tor's own answer about Tor's own state, so that is what
     * is waited on.
     *
     * The percentage is also the diagnosis when it fails. Stuck at 5 means no bridge answered at
     * all; stuck around 10-14 means one answered and the connection was cut during the handshake;
     * stuck in the 20s or 50s means the bridge works and the directory fetch behind it does not.
     */
    private suspend fun awaitBootstrap(): Boolean {
        while (true) {
            if (died) {
                log("path 2 stopped at ${lastPhase.ifEmpty { "the very beginning" }}")
                return false
            }

            val phase = runCatching { binder?.service?.getInfo("status/bootstrap-phase") }.getOrNull()
            if (phase != null) {
                val percent = PROGRESS.find(phase)?.groupValues?.get(1) ?: "?"
                val summary = phase.substringAfter("SUMMARY=\"", "").substringBefore("\"")
                    .ifEmpty { phase.trim() }
                val line = "$percent% $summary"
                if (line != lastPhase) {
                    lastPhase = line
                    log("path 2: $line")
                }
                if (percent == "100") return true
            }

            delay(PHASE_POLL_MS)
        }
    }

    /**
     * Asks Tor which port it actually opened.
     *
     * TorService reads this off its own control port right after authenticating
     * and before any status event could fire, so by the time a circuit is up
     * the number is there. The wait is only for the service binding to land.
     */
    private suspend fun socksPort(): Int {
        repeat(SOCKS_PORT_TRIES) {
            val port = binder?.service?.socksPort ?: 0
            if (port > 0) return port
            delay(SOCKS_PORT_WAIT_MS)
        }
        log("path 2 never reported its socks port, assuming $FALLBACK_SOCKS_PORT")
        return FALLBACK_SOCKS_PORT
    }

    private companion object {
        const val SOCKS_PORT_TRIES = 20
        const val SOCKS_PORT_WAIT_MS = 250L

        /** What TorService uses when the port is free, which it almost always is. */
        const val FALLBACK_SOCKS_PORT = 9050

        const val PHASE_POLL_MS = 750L

        const val LOOPBACK = "127.0.0.1"

        /**
         * How long the engine gives its own first request before calling the route dead.
         *
         * Longer than the racer's probe on purpose. This one is asked once, at the moment a fresh
         * circuit opens its very first stream, which is the slowest request Tor will ever make -
         * measured on a real phone at over eight seconds and sometimes over thirty. Getting this
         * wrong in the tight direction throws away a route that works.
         */
        const val SELF_CHECK_MS = 40_000

        /** Long enough to learn whether a loopback port has a process behind it. */
        const val CARRIER_CHECK_MS = 800

        val PROGRESS = Regex("PROGRESS=(\\d+)")
    }
}

/**
 * Builds the engine list.
 *
 * Three ways out, and they are bets on three different things rather than three names for one.
 *
 * **path 4** speaks MASQUE over HTTP/3 to Cloudflare's own anycast edge. It carries no list of
 * servers, so it is the only one that can win on a fresh install, on an operator the app has
 * never seen, or after every list has gone stale.
 *
 * **path 3** is QUIC over UDP with its handshake obfuscated to random bytes, dialling public
 * hysteria2 servers. One hop, and the fastest thing here when it lands on a good server.
 *
 * **path 2** is Tor: three hops of volunteer relays, reached either through a pluggable transport
 * or - when the other two are already up and Tor on its own carries nothing - through the tunnel
 * one of them is holding. Slow, and it gets through things nothing else does.
 *
 * 🚨 The engine that used to sit in front of these is gone, and it was deleted on the owner's own
 * measurements rather than on taste. It dialled vless and trojan endpoints out of mixed public
 * dumps and its rounds came back with one or two answers out of forty, over and over, on two
 * different operators. As a warm standby it went quiet about once a minute and paid for a full
 * forty-eight-port search each time, behind a tunnel that was working. Ranking, probing, scoring
 * and shaping were all correct; all of it was rearranging dead addresses, and the cost of doing
 * so came out of the connection the user was actually on. Removing it also took a TLS core and a
 * shaping proxy out of the build.
 *
 * Nothing is shared between the three. Different program, different process, different socket,
 * different shape on the wire - so a rule that kills one has no reason to touch the others.
 *
 * 🚨 Order matters on a first launch only: the scoreboard reorders them by what has actually
 * worked on this network, so after one success this list stops deciding anything.
 */
internal fun defaultEngines(
    context: Context,
    controller: Controller,
    bridges: (String) -> List<String>,
    pool: PoolStore,
    carrier: () -> Int,
    network: () -> String,
    log: (String) -> Unit = {},
): List<Engine> = listOf(
    // First, and for a reason rather than for tidiness: it is the only engine here that needs
    // nothing fetched before it can try, so on a fresh install it is the only one that can win at
    // all.
    EdgeEngine(context, log),
    HysteriaEngine(context, pool, log),
    TorEngine(context, controller, bridges, carrier, network, log),
)
