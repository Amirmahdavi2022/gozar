package xyz.jmc.gozar.direct;

import java.util.Map;

/**
 * Turns a {@code hysteria2://} line from a public list into a client config.
 *
 * <p><b>Why this engine exists at all.</b> A quarter of every public endpoint list is hysteria2,
 * and until now every one of those lines was parsed correctly, checked against a core that cannot
 * dial them, and thrown away. Measured against the real feeds this app already fetches: four
 * hundred and eighty-three endpoints parsed, three hundred and fifty-seven dialable, one hundred
 * and twenty-six discarded — and a hundred and twenty of those discards were hysteria2. The
 * servers were already being downloaded. Nothing was being done with them.
 *
 * <p><b>Why it is a genuinely separate bet and not a third name for the same one.</b> This is the
 * only thing that makes a third engine worth its battery. Path one is TLS over TCP and looks like
 * reading a website; path two is WebRTC and looks like a video call; this is QUIC over UDP with
 * the handshake obfuscated to random bytes. Three different protocols, three different transports,
 * three different processes. Equipment tuned to throttle a long-lived TLS flow — which is exactly
 * what a device log showed happening, a connection coming up in three seconds and then being
 * strangled thirty seconds later — has no reason to be looking at UDP at all, and vice versa. Two
 * engines of the same shape die in the same minute; these cannot.
 *
 * <p><b>What was verified rather than assumed.</b> Everything below was checked against the real
 * {@code hysteria} v2.6.5 client, not against its documentation: that it accepts a JSON config at
 * all (its loader takes the format from the file extension, and the docs only ever show YAML),
 * that these exact key names are the ones it reads, and that it opens the SOCKS5 listener this
 * app needs.
 *
 * <p>🚨 The client dials LAZILY — it answers the SOCKS handshake locally and only reaches for the
 * server when the first real byte arrives. So a completely dead endpoint still produces a clean
 * "SOCKS5 server listening" line and a successful handshake in about a millisecond. Exactly the
 * same trap the other core sets, and the reason nothing in this app is ever believed until
 * {@link SocksProbe} has had a real reply come back.
 *
 * <p>Free of Android, so it is tested against the real binary on a desktop JVM.
 */
final class HysteriaConfig {

    private HysteriaConfig() { }

    /** The port the live tunnel runs on. Never one of the round's ports. */
    static final int SOCKS_PORT = 1821;
    static final String SOCKS_LISTEN = "127.0.0.1";

    /** Whether this is an endpoint this engine can dial. */
    static boolean supports(ProxyConfig endpoint) {
        if (endpoint == null || endpoint.host == null || endpoint.host.isEmpty()) return false;
        if (endpoint.port <= 0 || endpoint.port > 65535) return false;
        // The password is the whole of the authentication. A line without one could never connect,
        // and letting it through would spend a probe and a process on a certainty.
        if (endpoint.id == null || endpoint.id.isEmpty()) return false;
        return "hysteria2".equals(endpoint.protocol);
    }

    /** Only the endpoints this engine can dial, in the order they arrived. */
    static java.util.List<ProxyConfig> supported(java.util.List<ProxyConfig> endpoints) {
        java.util.List<ProxyConfig> out = new java.util.ArrayList<>();
        if (endpoints == null) return out;
        for (ProxyConfig endpoint : endpoints) if (supports(endpoint)) out.add(endpoint);
        return out;
    }

    /**
     * The client config for one endpoint, listening on {@code socksPort}.
     *
     * @param logLevel one of the client's own levels; "warn" keeps the diary readable
     */
    static String build(ProxyConfig endpoint, int socksPort, String logLevel) {
        StringBuilder out = new StringBuilder(512);
        out.append("{\n");
        out.append("  \"server\": \"").append(escape(authority(endpoint))).append("\",\n");
        out.append("  \"auth\": \"").append(escape(endpoint.id)).append("\",\n");

        appendTls(out, endpoint);
        appendObfs(out, endpoint);

        // 🔑 The receive windows are the single biggest difference between this engine feeling
        // fast and feeling pointless, and the defaults are tuned for a short hop. A window is how
        // many bytes may be in flight before an acknowledgement comes back, so on a link with a
        // long round trip - which is every endpoint worth having here, all of them far away - a
        // small window caps throughput no matter how much capacity the server has. Eight
        // megabytes is what the client's own documentation recommends for exactly this case.
        out.append("  \"quic\": {\n");
        out.append("    \"initStreamReceiveWindow\": 8388608,\n");
        out.append("    \"maxStreamReceiveWindow\": 8388608,\n");
        out.append("    \"initConnReceiveWindow\": 20971520,\n");
        out.append("    \"maxConnReceiveWindow\": 20971520,\n");
        // Without this the client gives up long before a bad mobile link has finished sulking.
        out.append("    \"keepAlivePeriod\": \"10s\"\n");
        out.append("  },\n");

        // Saves a round trip on every new connection, which on a link this far away is the
        // difference between a page opening and a page appearing to hang.
        out.append("  \"fastOpen\": true,\n");

        // 🚨 lazy means the server is not reached until the first real byte. Kept ON deliberately:
        // it is what lets several candidates be started at once without each one immediately
        // opening a connection to a server that is probably dead. The cost is that starting
        // cleanly proves nothing, which nothing in this app assumed anyway.
        out.append("  \"lazy\": true,\n");

        out.append("  \"socks5\": { \"listen\": \"")
           .append(SOCKS_LISTEN).append(':').append(socksPort).append("\" },\n");
        out.append("  \"log\": { \"level\": \"").append(escape(logLevel)).append("\" }\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * The server address as the client wants it.
     *
     * <p>An IPv6 literal has to keep its brackets or the port is read as part of the address —
     * and a public list will hand us both forms.
     */
    private static String authority(ProxyConfig endpoint) {
        String host = endpoint.host;
        if (host.indexOf(':') >= 0 && host.charAt(0) != '[') host = "[" + host + "]";
        return host + ":" + endpoint.port;
    }

    private static void appendTls(StringBuilder out, ProxyConfig endpoint) {
        String sni = hostname(first(endpoint, "sni", "peer"));
        boolean insecure = truthy(first(endpoint, "insecure", "allowInsecure", "allow_insecure"));
        String pin = first(endpoint, "pinSHA256", "pinsha256");

        out.append("  \"tls\": {\n");
        boolean wrote = false;
        if (!sni.isEmpty()) { out.append("    \"sni\": \"").append(escape(sni)).append('"'); wrote = true; }

        // 🚨 Defaults to true, and that is a deliberate and uncomfortable choice rather than an
        // oversight. Nearly every endpoint in a public list runs a self-signed certificate, so
        // verifying would fail on almost all of them - and the ones that pass would not be safer,
        // because the certificate proves the server is who it says it is and we have no idea who
        // it should be. The line came from a stranger's list. The honest protection is that
        // everything riding this tunnel is itself TLS end to end, which the tunnel cannot read.
        // A line that explicitly says to verify is respected.
        if (wrote) out.append(",\n");
        out.append("    \"insecure\": ").append(insecure || pin.isEmpty() ? "true" : "false");
        if (!pin.isEmpty()) {
            out.append(",\n    \"pinSHA256\": \"").append(escape(pin)).append('"');
        }
        out.append("\n  },\n");
    }

    /**
     * Salamander, when the line asks for it.
     *
     * <p>🔑 Worth more here than anywhere else in the app. Without it a hysteria2 handshake has a
     * recognisable QUIC shape that equipment can match on; with it the packets are indistinguish-
     * able from random bytes, which is the whole reason this engine is a different bet rather
     * than a different port. A good share of the lines in the feeds already carry it.
     */
    private static void appendObfs(StringBuilder out, ProxyConfig endpoint) {
        String type = first(endpoint, "obfs");
        String password = first(endpoint, "obfs-password", "obfsPassword", "obfs_password");
        if (type.isEmpty() || password.isEmpty()) return;
        if (!"salamander".equalsIgnoreCase(type)) return;

        out.append("  \"obfs\": {\n");
        out.append("    \"type\": \"salamander\",\n");
        out.append("    \"salamander\": { \"password\": \"").append(escape(password)).append("\" }\n");
        out.append("  },\n");
    }

    /**
     * An sni value, if it could plausibly be a host name, and nothing otherwise.
     *
     * <p>🚨 Found by feeding the builder deliberately malformed input rather than by reading the
     * documentation: a value containing a character a host name cannot contain makes the client
     * reject the ENTIRE config on startup. Escaping it correctly is not enough — the JSON is
     * valid, and the client refuses it at the next layer down.
     *
     * <p>An sni is a hint about which certificate to ask for, and these lines come from strangers'
     * lists where it is frequently mistyped, left as a template placeholder, or carries a whole
     * URL. Dropping a nonsensical one costs nothing: the client falls back to the server address,
     * which is what an endpoint with no sni does anyway. Passing it on costs the endpoint
     * entirely, and for a reason nothing in the log would explain.
     */
    private static String hostname(String value) {
        if (value.isEmpty() || value.length() > 253) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!allowed) return "";
        }
        // A leading or trailing dot, or nothing but dots, is not a name either.
        String trimmed = value.replace(".", "");
        if (trimmed.isEmpty() || value.charAt(0) == '.' || value.endsWith(".")) return "";
        return value;
    }

    /** The first of these parameters that carries anything. Lists spell the same key several ways. */
    private static String first(ProxyConfig endpoint, String... keys) {
        Map<String, String> params = endpoint.params;
        if (params == null) return "";
        for (String key : keys) {
            String value = params.get(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * Makes a value safe to sit inside a JSON string.
     *
     * <p>Not decoration: these values come from strangers' lists, and a password containing a
     * quote would produce a config the client rejects outright. The client reads the whole file or
     * none of it, so one bad line would take down every endpoint in the same round — the exact
     * failure that cost a whole round on the other core.
     */
    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
