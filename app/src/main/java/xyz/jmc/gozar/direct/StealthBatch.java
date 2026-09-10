package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How one fan-out round is composed, and what its results are allowed to mean.
 *
 * <p>🔑 This replaces a loop that dialled one endpoint one way at a time. That loop was not slow
 * because the network was slow — it was slow because every attempt stopped the core, started a
 * fresh process, waited for a listener and killed it again, about six seconds to learn one bit
 * about one server. Measured against the real binary, forty attempts share one process that is
 * fully listening in 0.05s, so a round of forty now costs about what a single endpoint used to.
 *
 * <p>Two things fall out of that change rather than being designed in, and both were real defects:
 *
 * <ul>
 *   <li>There is no longer any reason to spend the best endpoints on one route before trying the
 *       others. The old policy held the remembered route for the first three candidates because
 *       each extra route tripled the cost of a dead endpoint. In a round every route is tried at
 *       once, so the highest-scoring endpoint gets all of them immediately. The endpoint most
 *       likely to work no longer gets only the route least likely to work.
 *   <li>A failure is only evidence about an endpoint if the machinery it was dialled through was
 *       working. When the carrier tunnel dropped mid-round, every attempt riding it failed for a
 *       reason that had nothing to do with the servers, and healthy endpoints were benched for it.
 *       See {@link #shouldRecordFailures}.
 * </ul>
 */
final class StealthBatch {

    private StealthBatch() {}

    /**
     * The first local port a round listens on.
     *
     * <p>Deliberately far from {@link XrayConfig#SOCKS_PORT}: the round is scratch work and the
     * live tunnel's port must never be one of the ports a round binds, or a round would fight the
     * connection it is trying to replace.
     */
    static final int BASE_PORT = 31_000;

    /** Ports one round may bind. Forty was measured at 42MB of core memory, so this is not tight. */
    static final int MAX_ATTEMPTS = 48;

    /**
     * The most endpoints one round may ever carry.
     *
     * <p>Equal to {@link #MAX_ATTEMPTS} because that is the ceiling: a round on a network with a
     * single usable route spends one port per endpoint, so forty-eight endpoints is forty-eight
     * ports. How many a round actually takes depends on how many routes it has, which is what
     * {@link #candidatesFor(int[])} answers.
     */
    static final int MAX_CANDIDATES = MAX_ATTEMPTS;

    /**
     * How many endpoints a round should collect, given the routes this network can use.
     *
     * <p>🔑 This used to be the constant sixteen, which was right when there were three routes
     * (sixteen endpoints tried every way is exactly forty-eight ports) and wrong ever since. With
     * two routes a round bound thirty-two of its forty-eight ports and left the rest idle; with
     * one route it bound sixteen and left two thirds idle. The cost of a round is the ports it
     * binds, and that cost was measured at forty-eight, so the idle ports were search we had
     * already paid for and were not doing.
     *
     * <p>Nothing here can exceed {@link #MAX_ATTEMPTS} — the division is what guarantees it, and
     * {@link #plan(List, int[], int)} enforces it again on the way out.
     */
    static int candidatesFor(int[] modes) {
        int routes = (modes == null || modes.length == 0) ? 1 : modes.length;
        return Math.max(1, Math.min(MAX_CANDIDATES, MAX_ATTEMPTS / routes));
    }

    /**
     * The attempts for one round: every candidate, on every route this network can use.
     *
     * <p>Order matters only for port assignment and for which attempts survive the cap. Candidates
     * arrive best-scoring first, and each candidate's routes are kept together, so truncating at
     * the cap drops the worst-scoring endpoints whole rather than leaving one of them with a
     * single arbitrary route.
     *
     * @param candidates endpoints to try, best first
     * @param modes      the routes available on this network, from {@link StealthPlan#modes}
     * @param cap        the most attempts this round may hold
     */
    static List<XrayConfig.Attempt> plan(List<ProxyConfig> candidates, int[] modes, int cap) {
        List<XrayConfig.Attempt> attempts = new ArrayList<>();
        if (candidates == null || modes == null || modes.length == 0 || cap <= 0) return attempts;
        for (ProxyConfig candidate : candidates) {
            if (candidate == null || !XrayConfig.supports(candidate)) continue;
            // A candidate goes in whole or not at all - see the note above.
            if (attempts.size() + modes.length > cap) break;
            for (int mode : modes) attempts.add(new XrayConfig.Attempt(candidate, mode));
            if (attempts.size() >= cap) break;
        }
        return attempts;
    }

    /** {@link #plan} with the standard caps. */
    static List<XrayConfig.Attempt> plan(List<ProxyConfig> candidates, int[] modes) {
        int room = candidatesFor(modes);
        List<ProxyConfig> capped = new ArrayList<>();
        if (candidates != null) {
            for (ProxyConfig candidate : candidates) {
                if (capped.size() >= room) break;
                capped.add(candidate);
            }
        }
        return plan(capped, modes, MAX_ATTEMPTS);
    }

    /**
     * The endpoints in this round that failed on every route they were given.
     *
     * <p>🚨 Per candidate, not per attempt. One endpoint appears up to three times in a round, and
     * recording a failure for each would bench a server three times over for what is a single
     * piece of evidence — and would do it on a filtered network, where a direct failure is the
     * expected outcome for a perfectly healthy server.
     *
     * @param attempts the round, as built by {@link #plan}
     * @param answered which attempt indices came back through a real request
     */
    static Set<String> failedEverywhere(List<XrayConfig.Attempt> attempts, Set<Integer> answered) {
        Set<String> held = new LinkedHashSet<>();
        Set<String> tried = new LinkedHashSet<>();
        if (attempts == null) return held;
        for (int i = 0; i < attempts.size(); i++) {
            String key = attempts.get(i).endpoint.key();
            tried.add(key);
            if (answered != null && answered.contains(i)) held.add(key);
        }
        tried.removeAll(held);
        return tried;
    }

    /**
     * Whether this round's failures say anything about the endpoints at all.
     *
     * <p>🚨 The reason this exists, from a real device log: the carrier's tunnel went stale
     * mid-round and its dials came back {@code read/write on closed pipe}. Nothing was wrong with
     * those servers, and they were written into the pool as dead anyway. A round whose hop died
     * under it has measured the hop, not the servers.
     *
     * <p>Deliberately not narrowed to "only the carried attempts are discarded". A round is the
     * unit of evidence, and a hop that died partway through has an unknown effect on when each
     * attempt ran and what it was competing with. Throwing the round away costs one round; keeping
     * half of it costs endpoints that are hard to get back.
     */
    static boolean shouldRecordFailures(boolean hopsAliveBefore, boolean hopsAliveAfter) {
        return hopsAliveBefore && hopsAliveAfter;
    }

    /** The local port attempt {@code index} listens on. */
    static int portFor(int index) {
        if (index < 0 || index >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Attempt index out of range: " + index);
        }
        return BASE_PORT + index;
    }

    /**
     * The winner among the attempts that answered: lowest latency wins.
     *
     * <p>Latency here is a real request that came back, not a socket that opened, so this is
     * comparing working routes against each other rather than guessing between candidates.
     *
     * @param latencies latency per attempt index, negative where the attempt did not answer
     * @return the winning index, or -1 when nothing answered
     */
    static int best(long[] latencies) {
        int winner = -1;
        long bestSoFar = Long.MAX_VALUE;
        if (latencies == null) return -1;
        for (int i = 0; i < latencies.length; i++) {
            if (latencies[i] < 0) continue;
            if (latencies[i] < bestSoFar) {
                bestSoFar = latencies[i];
                winner = i;
            }
        }
        return winner;
    }
}
