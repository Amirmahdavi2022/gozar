package xyz.jmc.gozar.direct;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Probes every port of a round at once and stops as soon as the answer is decided.
 *
 * <p>🔑 Why this exists. A round used to wait for every probe thread to finish before looking at
 * any of them, so a round cost the SLOWEST attempt in it - the full probe timeout - even when the
 * endpoint that was going to win had answered in three hundred milliseconds. Dead endpoints are
 * the common case in a public pool, and a dead endpoint costs exactly the timeout, so the round
 * was always paying it. That was seconds of "connecting" with the answer already in hand.
 *
 * <p>What it does instead: the moment one attempt answers, everything still running is given a
 * short grace window and then the round is over. The window is not politeness - it is what keeps
 * the choice honest. Attempts start together, so a probe that answers a little after the first one
 * really is a little slower, and the fastest endpoint is the one worth keeping. Without the window
 * the round would take the first thread the scheduler happened to wake, which is not the same
 * thing.
 *
 * <p>Nothing here decides which endpoint wins - {@link StealthBatch#best} still does that, from
 * whatever answered. This only decides when to stop waiting.
 *
 * <p>Free of Android and of any socket, so the timing can be tested against a stub prober on a
 * desktop JVM rather than guessed at.
 */
final class RoundProbe {

    /** Measures one attempt. Returns its latency in milliseconds, or negative if it did not answer. */
    interface Prober {
        long probe(int index);
    }

    /**
     * How long the round keeps listening after the first attempt answers.
     *
     * <p>Wide enough that a second endpoint on the same network gets a fair hearing, narrow enough
     * that it is not felt. Measured latencies of endpoints that work sit well inside it.
     */
    static final int GRACE_MS = 600;

    private RoundProbe() { }

    /**
     * Runs {@code count} probes in parallel.
     *
     * @param count     attempts in this round
     * @param timeoutMs the longest any single probe may take, and so the longest a round with no
     *                  answer at all can last
     * @param graceMs   how long to keep waiting after the first answer
     * @param prober    what to run for each attempt
     * @return one latency per attempt, negative where the attempt did not answer in time
     */
    static long[] run(int count, int timeoutMs, int graceMs, Prober prober) {
        long[] out = new long[Math.max(0, count)];
        if (count <= 0) return out;

        AtomicLongArray results = new AtomicLongArray(count);
        for (int i = 0; i < count; i++) results.set(i, -1L);

        Object gate = new Object();
        long start = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            final int index = i;
            Thread worker = new Thread(() -> {
                long latency;
                try {
                    latency = prober.probe(index);
                } catch (RuntimeException failure) {
                    latency = -1L;
                }
                results.set(index, latency);
                if (latency >= 0) {
                    synchronized (gate) { gate.notifyAll(); }
                }
            }, "probe-" + index);
            worker.setDaemon(true);
            worker.start();
        }

        // Two deadlines. The hard one is the probe timeout: a round where nothing answers must
        // still end. The soft one only appears once something has answered.
        long hardDeadline = start + timeoutMs + 2_000L;
        long softDeadline = Long.MAX_VALUE;

        synchronized (gate) {
            while (true) {
                long now = System.currentTimeMillis();
                long deadline = Math.min(hardDeadline, softDeadline);
                if (now >= deadline) break;
                if (softDeadline == Long.MAX_VALUE && anyAnswered(results, count)) {
                    softDeadline = now + Math.max(0, graceMs);
                    continue;
                }
                try {
                    gate.wait(Math.max(1, Math.min(deadline - now, 100L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (softDeadline == Long.MAX_VALUE && anyAnswered(results, count)) {
                    softDeadline = System.currentTimeMillis() + Math.max(0, graceMs);
                }
            }
        }

        // Threads still running are left to finish on their own; they are daemons and write into
        // an array nobody reads again. The snapshot is what the round is judged on.
        for (int i = 0; i < count; i++) out[i] = results.get(i);
        return out;
    }

    private static boolean anyAnswered(AtomicLongArray results, int count) {
        for (int i = 0; i < count; i++) if (results.get(i) >= 0) return true;
        return false;
    }
}
