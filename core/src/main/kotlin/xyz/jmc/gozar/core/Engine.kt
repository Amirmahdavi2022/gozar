package xyz.jmc.gozar.core

/**
 * What the traffic looks like from outside.
 *
 * This is the axis that matters. Two engines with the same shape get caught by
 * the same DPI rule and die in the same minute, so the racer spreads its bets
 * across shapes rather than across names.
 */
enum class Shape {
    /** Indistinguishable from a video call. */
    WEBRTC,

    /** WebSocket upgrade over TLS. Looks like reading a website. */
    HTTPS,

    /** No structure at all, obfs4 style. */
    RANDOM,

    /** Tunnelled inside DNS queries. Slow, but survives things nothing else does. */
    DNS,

    /** Whatever the user brought. */
    USER,
}

/** A live way out: a local SOCKS5 port the VPN service can point its tun at. */
data class Session(
    val socksPort: Int,
    val engine: String,
    val shape: Shape,
)

/**
 * One way of getting out.
 *
 * Implementations must tolerate being started at the same time as others and
 * cancelled mid-start, because that is exactly what the racer does to them.
 */
interface Engine {
    /** For logs only. Never shown to the user. */
    val name: String

    val shape: Shape

    /**
     * True when this engine cannot start cold because something has to be
     * fetched first: bridge lines, a broker address. These are skipped on a
     * first ever launch and provisioned later through whatever did come up.
     */
    val needsBootstrap: Boolean

    /** Brings it up and returns once a local proxy is listening. Suspends. */
    suspend fun start(): Session

    /** Tears it down. Must be safe to call more than once. */
    fun stop()
}

/** Answers the only question that counts: does traffic come back through this. */
interface Prober {
    suspend fun through(socksPort: Int): Boolean
}
