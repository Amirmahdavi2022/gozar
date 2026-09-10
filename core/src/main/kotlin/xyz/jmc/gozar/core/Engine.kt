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

    /**
     * Several shapes at once, decided further down than we can see.
     *
     * Tor is the case: it is handed every transport we managed to start and
     * picks between them itself, so the shape on the wire is whichever bridge
     * answered. Honest to say so rather than to claim one.
     */
    MIXED,

    /** Whatever the user brought. */
    USER,
}

/** How long an engine gets to come up and prove itself, unless it says otherwise. */
const val DEFAULT_DEADLINE_MS = 30_000L

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
 *
 * They must also be genuinely independent of one another. An "engine" that
 * shares a process, a port or a config file with another one is not a second
 * bet — it is the first bet wearing a different name, and it will fail in the
 * same second.
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

    /**
     * How long this engine gets before it is written off for this round.
     *
     * Per engine on purpose. A direct proxy that has not answered in ten
     * seconds is dead, while Tor over Snowflake has to find a volunteer through
     * a broker and then build a circuit, and on a bad night two minutes is slow
     * rather than broken. One shared deadline means either the fast engines
     * waste a minute each or the slow one is killed before it could ever win.
     */
    val deadlineMs: Long get() = DEFAULT_DEADLINE_MS

    /** Brings it up and returns once a local proxy is listening. Suspends. */
    suspend fun start(): Session

    /** Tears it down. Must be safe to call more than once. */
    fun stop()
}

/** Answers the only question that counts: does traffic come back through this. */
interface Prober {
    suspend fun through(socksPort: Int): Boolean
}
