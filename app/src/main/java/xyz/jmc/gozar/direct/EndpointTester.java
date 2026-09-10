package xyz.jmc.gozar.direct;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests candidate endpoints from the device itself and files the results in an {@link EndpointPool}.
 *
 * This exists because of one fact the public pools state about themselves: they test from a CI
 * runner, usually in Europe. That answers "is this server alive", which is not the question. The
 * question is "does this server answer from the network in the user's hand right now", and the only
 * place that can be answered is the device.
 *
 * What a pass here does and does not mean:
 *  - It means the address and port accept a connection from this network. That is precisely the
 *    thing filtering takes away, so it is the check that matters most and the cheapest to run.
 *  - It does NOT mean the proxy credentials are right or that a tunnel will establish. The engine
 *    still has to try, and a failure there is recorded against the endpoint like any other.
 * Claiming more than that would be dishonest, and the pool's scoring is built to correct for it:
 * a candidate that probes well but never carries traffic loses its score the first time it fails.
 *
 * No Android imports, so the scheduling, budgeting and early-stop rules are all verifiable on a
 * desktop JVM against real sockets.
 */
final class EndpointTester {

    /** How a single candidate is tested. Replaced in tests with something deterministic. */
    interface Probe {
        /** Round trip in millis, or -1 if the endpoint did not answer within the timeout. */
        long probe(ProxyConfig config, int timeoutMillis);
    }

    /** What one test pass achieved, for the log line. */
    static final class Outcome {
        final int tested;
        final int healthy;
        final long elapsedMillis;
        final boolean stoppedEarly;

        Outcome(int tested, int healthy, long elapsedMillis, boolean stoppedEarly) {
            this.tested = tested;
            this.healthy = healthy;
            this.elapsedMillis = elapsedMillis;
            this.stoppedEarly = stoppedEarly;
        }

        String summary() {
            return healthy + " of " + tested + " answered in " + elapsedMillis + "ms"
                    + (stoppedEarly ? " (stopped early, had enough)" : "");
        }
    }

    private EndpointTester() { }

    /**
     * Probes candidates in parallel, recording every result in the pool, and stops as soon as
     * {@code enough} of them have answered.
     *
     * Stopping early is the difference between a tester that runs while the user waits and one
     * that runs for a minute flat. We do not need the best endpoint on the internet; we need
     * enough working ones to connect now and to have somewhere to fall back to.
     */
    static Outcome test(EndpointPool pool, List<ProxyConfig> candidates, Probe probe,
                        int timeoutMillis, int parallelism, int enough, long now) {
        long started = System.currentTimeMillis();
        if (pool == null || candidates == null || candidates.isEmpty()) {
            return new Outcome(0, 0, 0, false);
        }
        pool.merge(candidates);

        final AtomicInteger healthy = new AtomicInteger();
        final AtomicInteger tested = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(
                Math.max(1, Math.min(parallelism, candidates.size())));
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (final ProxyConfig config : candidates) {
                jobs.add(() -> {
                    // Once we have what we need, the remaining jobs return without touching the
                    // network. Cancelling mid-probe would leave sockets to be reaped instead.
                    if (enough > 0 && healthy.get() >= enough) return null;
                    long latency = probe.probe(config, timeoutMillis);
                    tested.incrementAndGet();
                    if (latency >= 0) {
                        pool.recordSuccess(config.key(), latency, now);
                        healthy.incrementAndGet();
                    } else {
                        pool.recordFailure(config.key(), now);
                    }
                    return null;
                });
            }
            try {
                // The wall-clock cap keeps one hung probe from holding the whole pass open.
                long budget = (long) timeoutMillis * 4L + 5_000L;
                for (Future<Void> future : workers.invokeAll(jobs, budget, TimeUnit.MILLISECONDS)) {
                    future.isDone();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            workers.shutdownNow();
        }

        pool.prune(now);
        boolean stoppedEarly = enough > 0 && healthy.get() >= enough && tested.get() < candidates.size();
        return new Outcome(tested.get(), healthy.get(), System.currentTimeMillis() - started, stoppedEarly);
    }

    /**
     * The real probe. Picks its method from the protocol, because these do not share a transport:
     * VLESS, trojan and shadowsocks sit on TCP, while hysteria2 and tuic are QUIC over UDP and a
     * TCP connect to their port would fail on a perfectly good server.
     */
    static final Probe NETWORK_PROBE = new Probe() {
        @Override public long probe(ProxyConfig config, int timeoutMillis) {
            if (config == null) return -1;
            boolean udp = "hysteria2".equals(config.protocol) || "tuic".equals(config.protocol);
            return udp ? udpProbe(config, timeoutMillis) : tcpProbe(config, timeoutMillis);
        }
    };

    /** A completed TCP handshake is proof the path is open; nothing is sent afterwards. */
    /**
     * A probe that reaches the endpoint the way the engine does when it dials through the carrier.
     *
     * <p>Needed because a direct probe answers a different question on a filtered network. If the
     * engine has proved it can only reach its endpoints through the carrier, probing them directly
     * fails on servers that are perfectly alive, and every failure benches one — the pool would
     * empty itself fastest on exactly the networks it exists for. So the probe has to take the
     * same route the connection would.
     *
     * <p>Only TCP endpoints get here: a SOCKS CONNECT cannot carry QUIC, and the endpoints this
     * core can dial at all ({@link XrayConfig#supports}) are TCP anyway.
     *
     * @return a probe, or null when the carrier address is unusable, so the caller can decide
     *         rather than being handed something that fails everything.
     */
    static Probe throughCarrier(String carrier) {
        if (carrier == null) return null;
        String trimmed = carrier.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) return null;
        final String proxyHost = trimmed.substring(0, colon);
        final int proxyPort;
        try {
            proxyPort = Integer.parseInt(trimmed.substring(colon + 1).trim());
        } catch (NumberFormatException notAPort) {
            return null;
        }
        if (proxyHost.isEmpty() || proxyPort <= 0 || proxyPort > 65535) return null;
        return new Probe() {
            @Override public long probe(ProxyConfig config, int timeoutMillis) {
                if (config == null) return -1;
                long began = System.currentTimeMillis();
                boolean reached = SocksProbe.reaches(proxyHost, proxyPort,
                        config.host, config.port, timeoutMillis);
                if (!reached) return -1;
                // Never report zero: a latency of 0 reads as "instant" everywhere downstream.
                return Math.max(1, System.currentTimeMillis() - began);
            }
        };
    }

    static long tcpProbe(ProxyConfig config, int timeoutMillis) {
        Socket socket = new Socket();
        try {
            long started = System.nanoTime();
            socket.connect(new InetSocketAddress(config.host, config.port), timeoutMillis);
            return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        } catch (IOException | IllegalArgumentException unreachable) {
            return -1;
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * QUIC endpoints answer an initial datagram; a silent socket means the path is closed.
     * Deliberately not a real QUIC handshake — we are testing the path, not the server.
     */
    static long udpProbe(ProxyConfig config, int timeoutMillis) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMillis);
            byte[] payload = new byte[64];
            new Random().nextBytes(payload);
            payload[0] = (byte) 0xC0; // long header form, as a QUIC initial has
            long started = System.nanoTime();
            socket.send(new DatagramPacket(payload, payload.length,
                    new InetSocketAddress(config.host, config.port)));
            socket.receive(new DatagramPacket(new byte[256], 256));
            return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        } catch (Exception silent) {
            return -1;
        } finally {
            if (socket != null) socket.close();
        }
    }
}
