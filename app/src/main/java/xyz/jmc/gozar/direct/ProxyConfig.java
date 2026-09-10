package xyz.jmc.gozar.direct;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One proxy endpoint, parsed out of the URI formats the public config pools publish.
 *
 * The pools hand out plain text: thousands of lines like vless://... and hysteria2://..., often
 * base64 wrapped. This turns those lines into something we can deduplicate, filter and score.
 * Nothing here talks to the network or to Android, so it is all exercisable on a desktop JVM.
 *
 * Deliberately tolerant: a pool that changes its formatting slightly must cost us the lines that
 * changed, never the whole fetch.
 */
final class ProxyConfig {

    final String protocol;   // vless, trojan, ss, hysteria2, tuic, vmess
    final String host;
    final int port;
    final String id;         // uuid or password, whatever the protocol calls its credential
    final String label;      // the #name fragment, purely cosmetic
    final Map<String, String> params;
    final String raw;

    private ProxyConfig(String protocol, String host, int port, String id, String label,
                        Map<String, String> params, String raw) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.id = id;
        this.label = label;
        this.params = params;
        this.raw = raw;
    }

    /** What makes two entries the same server, ignoring cosmetic differences. */
    String key() {
        return protocol + "|" + host.toLowerCase(Locale.US) + "|" + port + "|" + id;
    }

    /** The transport in use, when the URI declares one. Useful for filtering and for scoring. */
    String transport() {
        String type = params.get("type");
        if (type == null) type = params.get("headerType");
        return type == null ? "" : type;
    }

    /** True when the entry carries the TLS flavour that survives DPI best. */
    boolean isReality() { return "reality".equalsIgnoreCase(params.get("security")); }

    @Override public String toString() { return protocol + "://" + host + ":" + port; }

    /**
     * Parses one line. Returns null for anything unrecognised rather than throwing, because a
     * pool of ten thousand lines will always contain some garbage and one bad line must not stop
     * the other nine thousand from being usable.
     */
    static ProxyConfig parse(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("//")) return null;

        int scheme = text.indexOf("://");
        if (scheme <= 0) return null;
        String protocol = text.substring(0, scheme).toLowerCase(Locale.US);
        String rest = text.substring(scheme + 3);
        if (rest.isEmpty()) return null;

        switch (protocol) {
            case "vless":
            case "trojan":
            case "hysteria2":
            case "hy2":
            case "tuic":
                return parseUserInfoStyle("hy2".equals(protocol) ? "hysteria2" : protocol, rest, text);
            case "ss":
                return parseShadowsocks(rest, text);
            default:
                // vmess is base64-wrapped JSON and needs a JSON parser we deliberately do not pull
                // in here; the pools carry plenty of the formats above.
                return null;
        }
    }

    /** vless / trojan / hysteria2 / tuic all share the credential@host:port?params#label shape. */
    private static ProxyConfig parseUserInfoStyle(String protocol, String rest, String raw) {
        String label = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            label = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }

        Map<String, String> params = new LinkedHashMap<>();
        int question = rest.indexOf('?');
        if (question >= 0) {
            parseQuery(rest.substring(question + 1), params);
            rest = rest.substring(0, question);
        }

        int at = rest.lastIndexOf('@');
        if (at <= 0) return null;
        String id = decode(rest.substring(0, at));
        String hostPort = rest.substring(at + 1);
        if (id.isEmpty() || hostPort.isEmpty()) return null;

        // IPv6 literals arrive bracketed: [2001:db8::1]:443
        String host;
        int port;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) return null;
            host = hostPort.substring(1, close);
            port = readPort(hostPort.substring(close + 1));
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0) return null;
            host = hostPort.substring(0, colon);
            port = readPort(hostPort.substring(colon));
        }
        if (host.isEmpty() || port <= 0 || port > 65535) return null;
        return new ProxyConfig(protocol, host, port, id, label, params, raw);
    }

    /** ss:// comes in two shapes: base64(method:password)@host:port, or fully base64. */
    private static ProxyConfig parseShadowsocks(String rest, String raw) {
        String label = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            label = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        Map<String, String> params = new LinkedHashMap<>();
        int question = rest.indexOf('?');
        if (question >= 0) {
            parseQuery(rest.substring(question + 1), params);
            rest = rest.substring(0, question);
        }

        if (rest.indexOf('@') < 0) {
            String decoded = base64(rest);
            if (decoded == null || decoded.indexOf('@') < 0) return null;
            rest = decoded;
        }

        int at = rest.lastIndexOf('@');
        String credential = rest.substring(0, at);
        // Some pools percent-encode the base64 padding, so the credential arrives as
        // ...Mg%3D%3D. Decoding is only safe when there is actually a percent sign in it: base64
        // uses '+' as a character, and URLDecoder would turn that into a space and quietly corrupt
        // the password. A corrupted password is worse than a skipped line - it produces an endpoint
        // that looks fine and can never authenticate.
        if (credential.indexOf('%') >= 0) credential = decode(credential);
        String decodedCredential = base64(credential);
        if (decodedCredential != null) credential = decodedCredential;

        String hostPort = rest.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) return null;
        String host = hostPort.substring(0, colon);
        int port = readPort(hostPort.substring(colon));
        if (host.isEmpty() || port <= 0 || port > 65535) return null;
        return new ProxyConfig("ss", host, port, credential, label, params, raw);
    }

    private static int readPort(String withColon) {
        try {
            String digits = withColon.startsWith(":") ? withColon.substring(1) : withColon;
            int slash = digits.indexOf('/');
            if (slash >= 0) digits = digits.substring(0, slash);
            return Integer.parseInt(digits.trim());
        } catch (Exception malformed) {
            return -1;
        }
    }

    private static void parseQuery(String query, Map<String, String> into) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            if (equals < 0) into.put(decode(pair), "");
            else into.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException notEncoded) {
            return value;
        }
    }

    /** Returns null when the text is not base64, so callers can fall back to treating it as plain. */
    static String base64(String value) {
        try {
            String padded = value.replace('-', '+').replace('_', '/').trim();
            int remainder = padded.length() % 4;
            if (remainder == 2) padded += "==";
            else if (remainder == 3) padded += "=";
            else if (remainder == 1) return null;
            byte[] bytes = Base64.getDecoder().decode(padded);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            // Reject binary noise that happens to decode: real payloads here are printable.
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 9 || (c > 13 && c < 32)) return null;
            }
            return text;
        } catch (Exception notBase64) {
            return null;
        }
    }

    /**
     * Turns a whole fetched document into configs. Handles both plain line-per-config lists and
     * the base64-wrapped subscription format, deduplicating as it goes and preserving the order
     * the source published, which is usually its own quality ranking.
     */
    static List<ProxyConfig> parseDocument(String body) {
        List<ProxyConfig> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return out;

        String text = body;
        if (text.indexOf("://") < 0) {
            String decoded = base64(text.replaceAll("\\s", ""));
            if (decoded != null) text = decoded;
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            ProxyConfig config = parse(line);
            if (config == null) continue;
            if (seen.put(config.key(), Boolean.TRUE) != null) continue;
            out.add(config);
        }
        return out;
    }
}
