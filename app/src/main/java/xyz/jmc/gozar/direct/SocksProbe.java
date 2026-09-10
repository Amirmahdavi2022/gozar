package xyz.jmc.gozar.direct;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asks a local SOCKS5 proxy to reach somewhere, and reports whether it could.
 *
 * <p>This exists because of how the Stealth engine's core reports itself. Starting the core only
 * means the core loaded its configuration and began listening; it says nothing about whether the
 * server on the far end answers. A pool endpoint that died an hour ago starts exactly as cleanly
 * as one that works. Without a probe the app would show "connected" over a tunnel that carries
 * nothing, which is worse than showing a failure.
 *
 * <p>So the engine dials an endpoint, then proves it here before believing it, and moves to the
 * next candidate when the proof fails.
 *
 * <p>Deliberately free of Android and of any HTTP library, so it runs on a desktop JVM and is
 * tested against a real socket rather than a mock.
 */
final class SocksProbe {

    /**
     * Captive-portal check endpoints: each answers a tiny reply and nothing else, so a whole
     * exchange is a few hundred bytes.
     *
     * <p>There is more than one on purpose. Every endpoint this probe judges is judged by whether
     * a reply comes back from ONE host, so a host that a given endpoint cannot reach makes that
     * endpoint look dead when it is not.
     *
     * <p>🚨 Cloudflare's check is deliberately LAST, and this is the single most expensive lesson
     * in this file. A large share of every public pool is served by Cloudflare Workers, and a
     * Worker cannot open a connection to a Cloudflare address - the platform blocks it, by design,
     * to stop traffic looping back through itself. So a Worker endpoint that carries YouTube and
     * Telegram perfectly will fail a probe aimed at cp.cloudflare.com every single time, and the
     * app will condemn it and move on to the next one, which is also a Worker, which also fails.
     * That is not a network problem and no amount of retrying gets past it: it is the probe target
     * disqualifying the endpoints it is supposed to be measuring.
     *
     * <p>{@link #chooseTarget} cannot catch it either, because it picks a target through the
     * carrier, and the carrier reaches Cloudflare fine. Cloudflare's host stays in the list only
     * as a last resort for a network where the other two are unreachable.
     */
    static final String[][] PROBE_TARGETS = {
        { "www.gstatic.com", "/generate_204" },
        { "detectportal.firefox.com", "/success.txt" },
        { "cp.cloudflare.com", "/generate_204" },
    };
    static final int PROBE_PORT = 80;

    /** Which target the probes are currently using. Set by {@link #chooseTarget}. */
    private static volatile int target = 0;

    static String PROBE_HOST() { return PROBE_TARGETS[target][0]; }

    /**
     * Picks a probe target that this proxy can actually reach, and reports whether any could.
     *
     * <p>Meant to be handed a proxy already known to work - the carrier tunnel. If none of the
     * targets answer through a tunnel that is definitely up, then the probe itself is what is
     * broken on this network, and every "endpoint did not answer" verdict it produces afterwards
     * is worthless. That is worth knowing before condemning four hundred servers.
     *
     * @return the index chosen, or -1 if nothing answered.
     */
    static int chooseTarget(String proxyHost, int proxyPort, int timeoutMs) {
        for (int index = 0; index < PROBE_TARGETS.length; index++) {
            if (exchangeMillis(proxyHost, proxyPort, timeoutMs, index) >= 0) {
                target = index;
                return index;
            }
        }
        return -1;
    }

    private SocksProbe() { }

    /**
     * Opens a connection through the proxy to {@code host:port}.
     *
     * <p>The caller owns the returned socket and must close it.
     *
     * <p>🚨 A "succeeded" reply here does NOT mean the far connection exists. The engine's core
     * answers this handshake locally and only dials the real server when the first byte arrives.
     * Pointed at a black-holed address it still reports success in about a millisecond. Anything
     * that needs to know whether the tunnel actually works must use {@link #carriesTraffic}
     * instead, which is the only reason that method exists.
     *
     * @throws java.io.IOException          if the proxy cannot be reached
     * @throws IllegalStateException if the proxy refuses or the far side is unreachable
     */
    static Socket connect(String proxyHost, int proxyPort, String host, int port, int timeoutMs)
            throws Exception {
        Socket socket = new Socket();
        boolean handed = false;
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Greeting: version 5, one method offered, "no authentication".
            out.write(new byte[] { 0x05, 0x01, 0x00 });
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting[0] != 0x05 || greeting[1] != 0x00) {
                throw new IllegalStateException("The proxy refused an unauthenticated session");
            }

            // Request: CONNECT, address given as a name so the far end resolves it, not this
            // device. Resolving locally would leak the destination to whatever resolver the phone
            // is using, which is the thing the tunnel exists to avoid.
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            if (name.length > 255) throw new IllegalArgumentException("Host name is too long");
            byte[] request = new byte[7 + name.length];
            request[0] = 0x05;                 // version
            request[1] = 0x01;                 // connect
            request[2] = 0x00;                 // reserved
            request[3] = 0x03;                 // address is a domain name
            request[4] = (byte) name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[5 + name.length] = (byte) ((port >> 8) & 0xFF);
            request[6 + name.length] = (byte) (port & 0xFF);
            out.write(request);
            out.flush();

            byte[] reply = readFully(in, 4);
            if (reply[0] != 0x05) throw new IllegalStateException("The proxy spoke an unknown dialect");
            if (reply[1] != 0x00) {
                throw new IllegalStateException("The proxy could not reach the destination (code "
                        + (reply[1] & 0xFF) + ")");
            }
            // The bound address trails the reply and has to be drained, or it would be read as the
            // first bytes of the caller's own stream.
            switch (reply[3]) {
                case 0x01: readFully(in, 4 + 2); break;                       // IPv4 + port
                case 0x04: readFully(in, 16 + 2); break;                      // IPv6 + port
                case 0x03: readFully(in, (readFully(in, 1)[0] & 0xFF) + 2); break; // name + port
                default: throw new IllegalStateException("The proxy returned an unknown address type");
            }

            handed = true;
            return socket;
        } finally {
            if (!handed) closeQuietly(socket);
        }
    }

    /**
     * Whether the proxy's handshake completes. Says nothing about the tunnel behind it - see the
     * warning on {@link #connect}. Useful only for checking that something is listening.
     */
    /**
     * Whether a SOCKS hop is accepting connections at all.
     *
     * <p>Deliberately weaker than {@link #reaches}: this asks only whether the hop is still there,
     * which is the question worth asking about a carrier that may have died under a round in
     * flight. Sending real traffic through it would measure the tunnel beyond it as well.
     */
    static boolean opens(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return socket.isConnected();
        } catch (Exception unreachable) {
            return false;
        }
    }

    static boolean reaches(String proxyHost, int proxyPort, String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(proxyHost, proxyPort, host, port, timeoutMs);
            return true;
        } catch (Exception unreachable) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Whether the tunnel actually carries bytes end to end.
     *
     * <p>Sends a real request through the proxy and requires a real reply. Nothing short of that
     * distinguishes a working endpoint from one that died an hour ago, because the handshake alone
     * succeeds either way.
     *
     * <p>Returns false rather than throwing: every failure mode here means the same thing to the
     * caller, which is "try the next endpoint".
     */
    static boolean carriesTraffic(String proxyHost, int proxyPort, int timeoutMs) {
        return exchangeMillis(proxyHost, proxyPort, timeoutMs) >= 0;
    }

    /** The standard check: does this tunnel actually work right now? */
    static boolean isUsable(String proxyHost, int proxyPort, int timeoutMs) {
        return carriesTraffic(proxyHost, proxyPort, timeoutMs);
    }

    /**
     * The same question, asked of a second host before the answer is believed to be "no".
     *
     * <p>For judging a candidate, one host is the right trade: a wrong "no" costs one endpoint out
     * of hundreds and the loop moves on. For judging a tunnel the user is already on, a wrong "no"
     * costs them the connection they are using, so it is worth a second opinion from a different
     * operator - one host being unreachable through an endpoint says nothing about whether the
     * endpoint carries traffic.
     *
     * <p>Only ever pays for the extra request when the first one failed, which on a healthy tunnel
     * is never.
     */
    static boolean carriesTrafficConfirmed(String proxyHost, int proxyPort, int timeoutMs) {
        if (exchangeMillis(proxyHost, proxyPort, timeoutMs) >= 0) return true;
        for (int index = 0; index < PROBE_TARGETS.length; index++) {
            if (index == target) continue;
            if (exchangeMillis(proxyHost, proxyPort, timeoutMs, index) >= 0) return true;
        }
        return false;
    }

    /**
     * Round trip for a complete request and reply through the tunnel, or -1 if it never came back.
     *
     * <p>This is the number worth scoring an endpoint on. A handshake time would be the time to
     * talk to loopback, which is the same one millisecond for every endpoint, working or dead.
     */
    static long latencyMillis(String proxyHost, int proxyPort, int timeoutMs) {
        return exchangeMillis(proxyHost, proxyPort, timeoutMs);
    }

    private static long exchangeMillis(String proxyHost, int proxyPort, int timeoutMs) {
        return exchangeMillis(proxyHost, proxyPort, timeoutMs, target);
    }

    private static long exchangeMillis(String proxyHost, int proxyPort, int timeoutMs, int which) {
        final String host = PROBE_TARGETS[which][0];
        final String path = PROBE_TARGETS[which][1];
        long started = System.nanoTime();
        Socket socket = null;
        try {
            socket = connect(proxyHost, proxyPort, host, PROBE_PORT, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Only the status line is needed, and reading byte by byte keeps us from blocking on a
            // body that may never come.
            InputStream in = socket.getInputStream();
            StringBuilder line = new StringBuilder(64);
            while (line.length() < 64) {
                int c = in.read();
                if (c < 0) break;
                if (c == '\n') break;
                if (c != '\r') line.append((char) c);
            }
            if (line.length() < 8 || !line.toString().startsWith("HTTP/")) return -1L;
            return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        } catch (Exception unreachable) {
            return -1L;
        } finally {
            closeQuietly(socket);
        }
    }

    /** Reads exactly this many bytes or fails; a short read here would be misread as a reply. */
    private static byte[] readFully(InputStream in, int count) throws Exception {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int step = in.read(buffer, read, count - read);
            if (step < 0) throw new IllegalStateException("The proxy closed the connection early");
            read += step;
        }
        return buffer;
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Exception ignored) { }
    }
}
