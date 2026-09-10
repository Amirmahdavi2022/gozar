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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.torproject.jni.TorService
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape
import kotlin.coroutines.resume

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
    override val deadlineMs: Long = BOOTSTRAP_TIMEOUT_MS + 20_000L

    override val needsBootstrap: Boolean
        get() = Transport.ALL.none { bridges(it).isNotEmpty() }

    private val started = mutableListOf<String>()

    @Volatile private var bound = false
    @Volatile private var binder: TorService.LocalBinder? = null

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
        log("transports listening: " + plugins.entries.joinToString { "${it.key}:${it.value}" })

        writeTorrc(plugins)

        val up = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) { awaitCircuit() }
        check(up == true) { "tor built no circuit in ${BOOTSTRAP_TIMEOUT_MS / 1000}s" }

        val port = socksPort()
        log("tor is up, socks on $port")
        Session(socksPort = port, engine = name, shape = shape)
    }

    override fun stop() {
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
                log("$transport did not start: ${e.message}")
                0
            }

            if (port > 0) put(transport, port) else log("$transport reported no port")
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
     * Waits for Tor's first completed circuit, which is what TorService reports
     * as ON. Registering before starting matters: the status that says we made
     * it is a transition, and a transition missed is a transition gone.
     */
    private suspend fun awaitCircuit(): Boolean = suspendCancellableCoroutine { cont ->
        val manager = LocalBroadcastManager.getInstance(context)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_ON -> finish(true)
                    TorService.STATUS_OFF, TorService.STATUS_STOPPING -> finish(false)
                }
            }

            private fun finish(ok: Boolean) {
                runCatching { manager.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(ok)
            }
        }

        manager.registerReceiver(receiver, IntentFilter(TorService.ACTION_STATUS))
        cont.invokeOnCancellation { runCatching { manager.unregisterReceiver(receiver) } }

        val intent = Intent(context, TorService::class.java)
        context.startService(intent)
        runCatching { context.bindService(intent, connection, 0) }.onSuccess { bound = true }
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
        log("tor never reported its socks port, assuming $FALLBACK_SOCKS_PORT")
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
    }
}

/**
 * Builds the engine list.
 *
 * One entry today, and the comment that used to sit here — that four engines
 * looking the same on the wire would be one engine wearing four hats — was
 * right about the danger and wrong about where it was. It was not the shapes
 * that collapsed into one, it was the process.
 */
fun defaultEngines(
    context: Context,
    controller: Controller,
    bridges: (String) -> List<String>,
    log: (String) -> Unit = {},
): List<Engine> = listOf(TorEngine(context, controller, bridges, log))
