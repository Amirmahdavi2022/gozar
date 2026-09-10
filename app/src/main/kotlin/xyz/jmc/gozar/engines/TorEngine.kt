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
import xyz.jmc.gozar.direct.PoolStore
import xyz.jmc.gozar.direct.XrayEngine

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
 */
class TorEngine(
    private val context: Context,
    private val controller: Controller,
    private val bridges: (String) -> List<String>,
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
    override val deadlineMs: Long = BOOTSTRAP_TIMEOUT_MS + 90_000L

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

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        val plugins = startTransports()
        check(plugins.isNotEmpty()) { "no transport came up, so tor has nothing to dial through" }
        log("path 2 has ${plugins.size} transports listening")

        writeTorrc(plugins)

        launchService()

        val up = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) { awaitBootstrap() }
        check(up == true) {
            "tor got no further than ${lastPhase.ifEmpty { "not started" }} " +
                "in ${BOOTSTRAP_TIMEOUT_MS / 1000}s"
        }

        val port = socksPort()
        log("path 2 is up, socks on $port")
        Session(socksPort = port, engine = name, shape = shape)
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
    private fun writeTorrc(plugins: Map<String, Int>) {
        val lines = buildList {
            add("UseBridges 1")
            add("ClientOnly 1")
            add("AvoidDiskWrites 1")
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
        /**
         * Generous on purpose. Snowflake has to find a volunteer through a
         * broker before Tor can even begin, and on a bad night that is slow
         * without being broken.
         */
        const val BOOTSTRAP_TIMEOUT_MS = 120_000L

        const val SOCKS_PORT_TRIES = 20
        const val SOCKS_PORT_WAIT_MS = 250L

        /** What TorService uses when the port is free, which it almost always is. */
        const val FALLBACK_SOCKS_PORT = 9050

        const val PHASE_POLL_MS = 750L

        val PROGRESS = Regex("PROGRESS=(\\d+)")
    }
}

/**
 * Builds the engine list.
 *
 * Two bets, and they are bets on different things rather than two names for one.
 *
 * Tor is slow and gets through when nothing else does: three hops of volunteer relays, reached by
 * whichever pluggable transport answered, and no fixed address for anyone to block. The direct
 * engine is one hop to a public endpoint speaking what looks like an ordinary TLS session, which
 * is fast and, being ordinary, is also the first thing a censor learns to spot.
 *
 * Nothing is shared between them. Different program, different process, different socket,
 * different shape on the wire — so a rule that kills one has no reason to touch the other, and
 * neither can pull the other down. That is what makes holding the loser warm behind the winner
 * worth anything at all.
 */
internal fun defaultEngines(
    context: Context,
    controller: Controller,
    bridges: (String) -> List<String>,
    pool: PoolStore,
    log: (String) -> Unit = {},
): List<Engine> = listOf(
    XrayEngine(context, pool, log),
    TorEngine(context, controller, bridges, log),
)
