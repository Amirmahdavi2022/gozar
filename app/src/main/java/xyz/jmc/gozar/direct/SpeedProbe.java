package xyz.jmc.gozar.direct;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Measures how fast a tunnel actually moves bytes, rather than how quickly it says hello.
 *
 * <p>🚨 This exists because of a defect that had nothing to do with any of the code around it.
 * Every endpoint in this app was chosen by {@link SocksProbe#latencyMillis}, which times a request
 * for a two-hundred-and-four with no body at all — a few hundred bytes, one round trip. That
 * number answers "is this server alive and near me". It says nothing whatsoever about whether the
 * server will then carry traffic at more than a trickle.
 *
 * <p>Those are not the same question, and on a pool of free public endpoints they are close to
 * opposite. The endpoints with the lowest latency are the popular ones, and the popular ones are
 * the saturated ones: four hundred other people are already on them, which is exactly why they are
 * well-known enough to be in a public list. A server forty milliseconds away sharing its uplink
 * with a crowd beat a server two hundred milliseconds away with room on it, every single time,
 * because the only thing ever being compared was the forty and the two hundred.
 *
 * <p>That is the whole story behind a connection that comes up in three seconds and then crawls.
 * Nothing was broken; the app was measuring the wrong thing and then faithfully picking the winner
 * of the race it had run.
 *
 * <p><b>What is measured.</b> A real body, read for a short window, counted. The clock starts at
 * the first byte of the body, not at the request — connection setup is latency, which is already
 * measured elsewhere, and mixing the two would just produce a worse version of both numbers.
 *
 * <p><b>What it costs.</b> Capped at {@link #MAX_BYTES} per candidate and {@link #WINDOW_MS} of
 * wall clock, whichever comes first, so a fast endpoint is judged in a fraction of a second and a
 * slow one cannot run up someone's mobile bill to prove it is slow. A candidate that delivers the
 * cap immediately has told us everything we needed; there is no reason to keep pulling.
 *
 * <p>Free of Android, so it runs against a real proxy on a desktop JVM.
 */
final class SpeedProbe {

    private SpeedProbe() { }

    /**
     * Hosts that will hand over a body worth timing, over plain HTTP.
     *
     * <p>Plain HTTP on purpose: the whole measurement is bytes per second, and wrapping it in TLS
     * would add a handshake to every sample and a second thing that can fail for reasons that are
     * not the endpoint's fault.
     *
     * <p>🚨 Cloudflare's is last, for the same reason it is last in {@link SocksProbe}: a large
     * share of every public pool is served by Cloudflare Workers, and a Worker cannot open a
     * connection to a Cloudflare address. Putting it first would score every Worker-backed
     * endpoint at zero and quietly hand the pool to whatever was left.
     *
     * <p>More than one because a speed test host that is down, or unreachable through one
     * particular endpoint, must not be able to condemn that endpoint. If none of them answer, the
     * caller falls back on latency and is no worse off than before this file existed.
     */
    static final String[][] SPEED_TARGETS = {
        { "speedtest.tele2.net", "/1MB.zip" },
        { "proof.ovh.net", "/files/1Mb.dat" },
        { "ipv4.download.thinkbroadband.com", "/1MB.zip" },
        { "speed.cloudflare.com", "/__down?bytes=2000000" },
    };

    static final int PORT = 80;

    /**
     * The longest a single candidate is timed for.
     *
     * <p>Short deliberately. This runs while someone is watching a connect spinner, and the
     * difference between a throttled endpoint and a healthy one shows up in the first second —
     * a server handing over four kilobytes in that second is not going to redeem itself in the
     * fifth.
     */
    static final int WINDOW_MS = 1_400;

    /** Enough bytes to be sure, so a fast endpoint stops early instead of buying the full window. */
    static final int MAX_BYTES = 512 * 1024;

    /**
     * Below this the sample is noise rather than a measurement, and is reported as unknown.
     *
     * <p>A few kilobytes can arrive purely because a TCP window opened, before anything about the
     * endpoint's sustained rate is visible. Treating that as a real figure would rank endpoints on
     * the shape of their first packet burst.
     */
    static final int MIN_BYTES = 24 * 1024;

    /** Which target the samples are currently using. Set by {@link #chooseTarget}. */
    private static volatile int target = 0;

    static String host() { return SPEED_TARGETS[target][0]; }

    /**
     * Picks a speed-test host reachable through a hop already known to work.
     *
     * <p>Meant to be handed the tunnel that just won, for the same reason {@link
     * SocksProbe#chooseTarget} is: if none of them answer through a tunnel that is definitely up,
     * then it is the measurement that is unavailable on this network, not the endpoints that are
     * slow, and every figure produced afterwards would be a lie about the pool.
     *
     * @return the index chosen, or -1 if nothing answered
     */
    static int chooseTarget(String proxyHost, int proxyPort, int timeoutMs) {
        for (int index = 0; index < SPEED_TARGETS.length; index++) {
            if (sample(proxyHost, proxyPort, timeoutMs, index) > 0) {
                target = index;
                return index;
            }
        }
        return -1;
    }

    /**
     * How fast this hop moves bytes, in bytes per second.
     *
     * @return the rate, or -1 when nothing measurable came back
     */
    static long bytesPerSecond(String proxyHost, int proxyPort, int timeoutMs) {
        return sample(proxyHost, proxyPort, timeoutMs, target);
    }

    /**
     * Measures several hops at once and returns a rate for each.
     *
     * <p>🔑 Together rather than one after another, and that is a deliberate trade. Run in
     * sequence this would add a window per candidate to every connect — several seconds of
     * someone watching a spinner for a connection that already works. Run together it costs one
     * window for all of them.
     *
     * <p>The cost of doing it this way is that the candidates share the phone's link, so every
     * figure comes out lower than it would alone. That is acceptable because nothing here needs
     * the absolute number — only the order — and the thing being looked for survives the
     * contention: an endpoint that is throttled at the far end is throttled whether or not
     * anything else is running, so it stays at the bottom. An endpoint that is merely sharing
     * this phone's link with two others still shows the shape of a healthy one.
     *
     * @param ports    the local SOCKS ports to measure, in candidate order
     * @return one rate per port, negative where nothing measurable came back
     */
    static long[] rates(String proxyHost, int[] ports, int timeoutMs) {
        long[] out = new long[ports.length];
        Thread[] workers = new Thread[ports.length];
        final long[] results = new long[ports.length];

        for (int i = 0; i < ports.length; i++) {
            final int index = i;
            workers[i] = new Thread(() -> {
                try {
                    results[index] = bytesPerSecond(proxyHost, ports[index], timeoutMs);
                } catch (RuntimeException failure) {
                    results[index] = -1L;
                }
            }, "speed-" + ports[i]);
            workers[i].setDaemon(true);
            workers[i].start();
        }

        // A worker that overruns is abandoned rather than waited on: it is a daemon writing into
        // a slot nobody reads again, and the alternative is letting one stuck socket hold up a
        // connection that every other candidate has already answered.
        long deadline = System.currentTimeMillis() + timeoutMs + WINDOW_MS + 2_000L;
        for (Thread worker : workers) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                worker.join(left);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.arraycopy(results, 0, out, 0, ports.length);
        for (int i = 0; i < out.length; i++) if (out[i] == 0L) out[i] = -1L;
        return out;
    }

    /**
     * {@link #rates} with one retry on a different speed host.
     *
     * <p>🚨 The failure this guards against is silent and would be very hard to see from a log.
     * If the current speed host happens to be unreachable — down, blocked on this network, or
     * simply not resolvable through these particular endpoints — then every candidate measures as
     * unknown, the caller falls back to ranking by latency, and the app quietly goes back to
     * choosing exactly the way it did before any of this was written. Nothing errors, nothing is
     * logged as broken, and the endpoint that is fast never gets picked.
     *
     * <p>So: when every single candidate comes back unmeasurable, that is treated as evidence
     * about the speed host rather than about the endpoints — the caller only ever passes ports
     * that just answered a probe, so they are not all dead — and one more host is tried. The
     * chosen host then sticks for the life of the process, so this costs a second window once on
     * a bad network and nothing at all afterwards.
     *
     * @param ports local SOCKS ports that have ALREADY answered a reachability probe
     */
    static long[] ratesWithFallback(String proxyHost, int[] ports, int timeoutMs) {
        long[] measured = rates(proxyHost, ports, timeoutMs);
        if (ports.length == 0 || anyMeasured(measured)) return measured;

        int started = target;
        for (int step = 1; step < SPEED_TARGETS.length; step++) {
            target = (started + step) % SPEED_TARGETS.length;
            measured = rates(proxyHost, ports, timeoutMs);
            if (anyMeasured(measured)) return measured;
        }
        target = started;
        return measured;
    }

    private static boolean anyMeasured(long[] values) {
        for (long value : values) if (value > 0) return true;
        return false;
    }

    private static long sample(String proxyHost, int proxyPort, int timeoutMs, int which) {
        final String host = SPEED_TARGETS[which][0];
        final String path = SPEED_TARGETS[which][1];

        Socket socket = null;
        try {
            socket = SocksProbe.connect(proxyHost, proxyPort, host, PORT, timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            if (!skipHeaders(in)) return -1L;

            byte[] buffer = new byte[16 * 1024];
            long counted = 0;
            long started = 0;

            while (counted < MAX_BYTES) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;

                // 🔑 The clock starts here, at the first byte of the body, not at the request.
                // Everything before this point is connection setup, which is latency and is
                // already measured by its own probe. Folding it in would make a distant but
                // roomy endpoint look throttled purely for being distant, which is the exact
                // mistake this whole file exists to undo.
                if (started == 0) started = System.nanoTime();
                counted += read;

                if ((System.nanoTime() - started) / 1_000_000L >= WINDOW_MS) break;
            }

            if (counted < MIN_BYTES || started == 0) return -1L;

            long elapsedMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            return (counted * 1000L) / elapsedMs;
        } catch (Exception unreachable) {
            return -1L;
        } finally {
            SocksProbe.closeQuietly(socket);
        }
    }

    /**
     * Reads past the response headers and stops on the blank line.
     *
     * <p>Byte at a time, because the first read of the body must not be swallowed: buffering here
     * would pull part of the payload into a reader the counter never sees, and the measurement
     * would be short by however much the buffer happened to grab.
     *
     * @return whether this looked like an HTTP response that will have a body
     */
    private static boolean skipHeaders(InputStream in) throws Exception {
        StringBuilder status = new StringBuilder(64);
        while (status.length() < 64) {
            int c = in.read();
            if (c < 0) return false;
            if (c == '\n') break;
            if (c != '\r') status.append((char) c);
        }
        // A redirect or an error page has a body too, and timing it would measure the wrong
        // server. Only a plain 200 is worth counting.
        if (!status.toString().startsWith("HTTP/") || status.indexOf(" 200") < 0) return false;

        int blank = 0;
        while (blank < 2) {
            int c = in.read();
            if (c < 0) return false;
            if (c == '\n') blank++;
            else if (c != '\r') blank = 0;
        }
        return true;
    }
}
