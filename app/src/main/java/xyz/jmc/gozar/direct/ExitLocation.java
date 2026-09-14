package xyz.jmc.gozar.direct;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Where the tunnel actually comes out.
 *
 * <p>The app dials endpoints from a public list and cannot know in advance where any of them sit —
 * a line in that list is a host and a port, not a place. So the only honest way to answer "which
 * country am I in right now" is to ask something on the other side of the tunnel, through the
 * tunnel, and report what it says.
 *
 * <p><b>Why it asks several different services.</b> Each provider here is one request that returns
 * the caller's own address and country together, and they are tried in order until one answers:
 *
 * <ul>
 *   <li>The first speaks plain HTTP, which matters more than it looks — it needs no TLS handshake
 *       through the tunnel, so it is the cheapest and the least likely to be refused for arriving
 *       from somewhere unexpected.
 *   <li>🚨 Cloudflare's is LAST, and always must be. A large share of every public endpoint pool
 *       is served by Cloudflare Workers, and a Worker cannot open a connection to a Cloudflare
 *       address. Asking it first would report "unavailable" for exactly the endpoints most likely
 *       to be carrying the tunnel.
 * </ul>
 *
 * <p>Wrong is worse than unknown here. Someone deciding whether it is safe to sign into something
 * is reading this line, so a provider that answers strangely is discarded rather than guessed at,
 * and the whole chain failing reports nothing rather than a stale or invented country.
 *
 * <p>Free of Android and of any JSON library, so it runs and is tested on a desktop JVM.
 */
public final class ExitLocation {

    private ExitLocation() { }

    /** A place the tunnel came out. Never partially filled: no country means no answer. */
    public static final class Place {
        public final String country;
        public final String code;
        public final String ip;

        Place(String country, String code, String ip) {
            this.country = country;
            this.code = code == null ? "" : code.toUpperCase(Locale.ROOT);
            this.ip = ip == null ? "" : ip;
        }

        /**
         * The country's flag, built from its two letters.
         *
         * <p>A country code is two ASCII letters and a flag emoji is those same two letters as
         * regional indicator symbols, so every flag on earth comes out of arithmetic and the app
         * ships no images and no lookup table that could fall behind.
         */
        public String flag() {
            if (code.length() != 2) return "";
            int first = Character.codePointAt(code, 0);
            int second = Character.codePointAt(code, 1);
            if (first < 'A' || first > 'Z' || second < 'A' || second > 'Z') return "";
            return new String(Character.toChars(0x1F1E6 + first - 'A'))
                + new String(Character.toChars(0x1F1E6 + second - 'A'));
        }

        @Override public String toString() {
            String flag = flag();
            return (flag.isEmpty() ? "" : flag + " ") + country;
        }
    }

    private static final int HTTP = 80;
    private static final int HTTPS = 443;

    /**
     * Asks, through the tunnel, until something answers.
     *
     * @return where it came out, or null if nothing could tell us
     */
    public static Place lookup(String proxyHost, int proxyPort, int timeoutMs) {
        Place place = viaIpApi(proxyHost, proxyPort, timeoutMs);
        if (place != null) return place;

        place = viaIpWho(proxyHost, proxyPort, timeoutMs);
        if (place != null) return place;

        return viaTrace(proxyHost, proxyPort, timeoutMs);
    }

    /** Plain HTTP, so no handshake is paid through the tunnel to ask a one-line question. */
    static Place viaIpApi(String proxyHost, int proxyPort, int timeoutMs) {
        String body = fetch(proxyHost, proxyPort, "ip-api.com", HTTP, false,
            "/json/?fields=status,country,countryCode,query", timeoutMs);
        if (body == null || !"success".equals(field(body, "status"))) return null;
        return place(field(body, "country"), field(body, "countryCode"), field(body, "query"));
    }

    static Place viaIpWho(String proxyHost, int proxyPort, int timeoutMs) {
        String body = fetch(proxyHost, proxyPort, "ipwho.is", HTTPS, true, "/", timeoutMs);
        if (body == null) return null;
        return place(field(body, "country"), field(body, "country_code"), field(body, "ip"));
    }

    /**
     * Cloudflare's own trace endpoint. Plain {@code key=value} lines rather than JSON, and it
     * gives a country code but no country name, so the name is filled in from the code.
     */
    static Place viaTrace(String proxyHost, int proxyPort, int timeoutMs) {
        String body = fetch(proxyHost, proxyPort, "www.cloudflare.com", HTTPS, true,
            "/cdn-cgi/trace", timeoutMs);
        if (body == null) return null;

        String code = null, ip = null;
        for (String line : body.split("\n")) {
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if ("loc".equals(key)) code = value;
            else if ("ip".equals(key)) ip = value;
        }
        if (code == null || code.length() != 2) return null;
        String name = new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH);
        return place(name.isEmpty() ? code : name, code, ip);
    }

    /**
     * A country name and an address are the whole point, so an answer missing the name is no
     * answer. "XX" and an empty string both come back from providers that did not recognise the
     * exit, and passing those on would put a blank flag on the user's screen.
     */
    private static Place place(String country, String code, String ip) {
        if (country == null || country.isEmpty()) return null;
        if (code == null || code.length() != 2) code = "";
        return new Place(country, code, ip);
    }

    /**
     * One request through the tunnel, returning the body.
     *
     * <p>The destination is sent to the proxy as a NAME, never an address resolved here. That is
     * not a detail: resolving it on this device would hand the name to whatever resolver the phone
     * is using, outside the tunnel, which is the exact leak the tunnel exists to prevent.
     */
    private static String fetch(String proxyHost, int proxyPort, String host, int port,
                                boolean secure, String path, int timeoutMs) {
        Socket socket = null;
        try {
            socket = SocksProbe.connect(proxyHost, proxyPort, host, port, timeoutMs);
            socket.setSoTimeout(timeoutMs);

            if (secure) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, port, true);
                // Without this the certificate is never checked against the name, and a tunnel is
                // exactly the place where that matters: the whole path is someone else's server.
                tls.setSSLParameters(withHostnameCheck(tls));
                tls.startHandshake();
                socket = tls;
                socket.setSoTimeout(timeoutMs);
            }

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    // Some of these refuse a request with no user agent outright, with a status
                    // that reads exactly like the tunnel being blocked.
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder status = new StringBuilder(64);
            while (status.length() < 64) {
                int c = in.read();
                if (c < 0) return null;
                if (c == '\n') break;
                if (c != '\r') status.append((char) c);
            }
            if (status.indexOf(" 200") < 0) return null;

            int blank = 0;
            while (blank < 2) {
                int c = in.read();
                if (c < 0) return null;
                if (c == '\n') blank++;
                else if (c != '\r') blank = 0;
            }

            // Capped, because this is a short answer and an unbounded read through a tunnel is a
            // way for a misbehaving host to hold a thread open indefinitely.
            StringBuilder body = new StringBuilder(512);
            byte[] buffer = new byte[512];
            while (body.length() < 8192) {
                int read = in.read(buffer);
                if (read < 0) break;
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return body.toString();
        } catch (Exception unreachable) {
            return null;
        } finally {
            SocksProbe.closeQuietly(socket);
        }
    }

    private static javax.net.ssl.SSLParameters withHostnameCheck(SSLSocket socket) {
        javax.net.ssl.SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    /**
     * Pulls one field out of a flat JSON object.
     *
     * <p>Hand-rolled on purpose. org.json exists on Android but not on a desktop JVM, and the
     * thing that makes this class testable at all is that it runs in both. The shape being read
     * is three known keys out of a flat object, which does not need a parser.
     */
    static String field(String json, String name) {
        String key = "\"" + name + "\"";
        int at = json.indexOf(key);
        if (at < 0) return null;

        int cursor = at + key.length();
        while (cursor < json.length() && (json.charAt(cursor) == ' ' || json.charAt(cursor) == ':')) {
            cursor++;
        }
        if (cursor >= json.length()) return null;

        if (json.charAt(cursor) == '"') {
            int end = cursor + 1;
            StringBuilder value = new StringBuilder();
            while (end < json.length() && json.charAt(end) != '"') {
                // A backslash escape must not be read as the closing quote, which is how a
                // country name with a quote in it would truncate every field after it.
                if (json.charAt(end) == '\\' && end + 1 < json.length()) end++;
                value.append(json.charAt(end));
                end++;
            }
            return value.toString();
        }

        int end = cursor;
        while (end < json.length() && ",}] \n\r\t".indexOf(json.charAt(end)) < 0) end++;
        String value = json.substring(cursor, end);
        return value.isEmpty() ? null : value;
    }
}
