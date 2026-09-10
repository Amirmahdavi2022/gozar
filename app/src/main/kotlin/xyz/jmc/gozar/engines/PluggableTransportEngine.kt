package xyz.jmc.gozar.engines

import IPtProxy.Controller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.jmc.gozar.core.Engine
import xyz.jmc.gozar.core.Session
import xyz.jmc.gozar.core.Shape

/**
 * Transport names as IPtProxy knows them. These strings are the API — they are
 * matched inside the Go library, so they are not ours to prettify.
 */
object Transport {
    const val OBFS4 = "obfs4"
    const val MEEK_LITE = "meek_lite"
    const val WEBTUNNEL = "webtunnel"
    const val SNOWFLAKE = "snowflake"
    const val DNSTT = "dnstt"
}

/**
 * Anything that can turn a pluggable transport into a usable SOCKS port.
 *
 * A pluggable transport on its own is not a way out. It speaks the PT protocol
 * and expects Tor on the other side of it, driving it and passing bridge lines
 * through SOCKS auth. So an engine here starts the transport, then hands it to
 * whatever is running Tor, and the port that comes back is Tor's, not the
 * transport's.
 */
interface TorDriver {
    /**
     * Starts Tor pointed at a transport already listening on [ptPort], with
     * [bridges] as its bridge lines. Returns Tor's SOCKS port.
     */
    suspend fun start(transport: String, ptPort: Int, bridges: List<String>): Int

    fun stop()
}

/**
 * One transport, wrapped as an engine the racer can start.
 *
 * Instances share a single [Controller], because IPtProxy is explicit that you
 * must not create more than one.
 */
class PluggableTransportEngine(
    private val transport: String,
    override val shape: Shape,
    private val controller: Controller,
    private val tor: TorDriver,
    private val bridges: () -> List<String>,
) : Engine {

    override val name: String = transport

    /**
     * An engine with no bridge lines has nothing to dial, so it is not worth
     * a place on the starting line. Asked every time rather than fixed at
     * construction, because lines can arrive at runtime through a tunnel that
     * is already up.
     */
    override val needsBootstrap: Boolean get() = bridges().isEmpty()

    @Volatile private var started = false

    override suspend fun start(): Session = withContext(Dispatchers.IO) {
        controller.start(transport, "")
        started = true

        val ptPort = controller.port(transport).toInt()
        check(ptPort > 0) { "$transport reported no port" }

        val socksPort = tor.start(transport, ptPort, bridges())
        Session(socksPort = socksPort, engine = name, shape = shape)
    }

    override fun stop() {
        tor.stop()
        if (started) {
            runCatching { controller.stop(transport) }
            started = false
        }
    }
}

/**
 * Builds the engine list.
 *
 * Order here decides nothing — the scoreboard does that at launch — but the
 * shapes do. Four engines that look the same on the wire would be one engine
 * wearing four hats, and would all die to the same rule on the same evening.
 */
fun defaultEngines(
    controller: Controller,
    tor: TorDriver,
    bridges: (String) -> List<String>,
): List<Engine> = listOf(
    Transport.SNOWFLAKE to Shape.WEBRTC,
    Transport.WEBTUNNEL to Shape.HTTPS,
    Transport.MEEK_LITE to Shape.HTTPS,
    Transport.OBFS4 to Shape.RANDOM,
    Transport.DNSTT to Shape.DNS,
).map { (transport, shape) ->
    PluggableTransportEngine(transport, shape, controller, tor) { bridges(transport) }
}
