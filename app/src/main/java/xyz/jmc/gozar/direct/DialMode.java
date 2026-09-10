package xyz.jmc.gozar.direct;

/**
 * The ways a single endpoint can be dialled.
 *
 * <p>An attempt is a pair — an endpoint and one of these — because the same server can be
 * reachable one way and not another, and treating "this server is dead" and "this route is dead"
 * as the same fact benches healthy servers one connect at a time.
 *
 * <p>The numbers are stable on purpose: they are written into the saved pool, so changing one
 * would silently reinterpret a file written by an older build.
 */
public final class DialMode {

    /** Straight out from this network. Fastest, and the first thing a censor sees. */
    public static final int DIRECT = 0;

    /** Through another local proxy that is already up. Reserved; nothing supplies one yet. */
    public static final int CHAINED = 1;

    /** Out from this network, with the TLS handshake reshaped on the way. */
    public static final int SPOOF = 2;

    private DialMode() {}

    public static String label(int mode) {
        switch (mode) {
            case CHAINED: return "carried";
            case SPOOF: return "shaped";
            default: return "directly";
        }
    }
}
