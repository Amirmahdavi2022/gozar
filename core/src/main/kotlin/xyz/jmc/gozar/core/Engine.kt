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

/** How long one request through a tunnel may take, unless the engine asks for more. */
const val DEFAULT_PROBE_TIMEOUT_MS = 8_000

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
    /**
     * The key this engine is scored and remembered under. Never printed.
     *
     * 🚨 Keep it stable. It is what the scoreboard, the bootstrap flag and the saved route are all
     * filed against, so renaming it silently discards everything the device has learned.
     */
    val name: String

    /**
     * What the log calls it.
     *
     * Separate from [name] on purpose: the log is written to be pasted, and naming the machinery
     * tells whoever reads it exactly which technique to look for. A number says as much as anyone
     * debugging needs and nothing more.
     */
    val label: String get() = name

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

    /**
     * How long the first request through this engine may take before it counts as dead.
     *
     * Separate from [deadlineMs] because coming up and carrying are separate costs. Tor reaching
     * 100% means its first circuit exists, not that a stream to a new host is cheap: that stream
     * still has to be opened through three relays and the name resolved at the far end, and on a
     * bridge from a filtered network the first one routinely takes half a minute. Eight seconds —
     * a fair limit for a direct proxy one hop away — condemns a working Tor every time.
     */
    val probeTimeoutMs: Int get() = DEFAULT_PROBE_TIMEOUT_MS

    /** Brings it up and returns once a local proxy is listening. Suspends. */
    suspend fun start(): Session

    /**
     * Asked before this engine is written off: can you get yourself back without being restarted?
     *
     * An engine that dials one of many interchangeable servers can, and the difference matters
     * more than it sounds. Replacing a dead server behind the SAME local port is invisible —
     * the tun stays attached, the routes stay put, and the phone sees a second of stalled
     * sockets. Tearing the engine down and racing again is a visible drop, every connection in
     * flight lost, and on a bad network the better part of a minute with no way out at all.
     *
     * Returning true is a claim that something is listening on the same port and carrying
     * traffic again; the caller re-probes rather than taking it on trust.
     *
     * Defaults to false, which is the honest answer for an engine with one way out.
     */
    suspend fun recover(): Boolean = false

    /** Tears it down. Must be safe to call more than once. */
    fun stop()
}

/** Answers the only question that counts: does traffic come back through this. */
interface Prober {
    suspend fun through(socksPort: Int, timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS): Boolean
}
