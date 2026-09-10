package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the pool of endpoints the Stealth engine dials, and decides which one is next.
 *
 * The point of this class is that the user never sees a server die. Three things make that work:
 *
 *  1. Endpoints are scored from what actually happened on THIS phone and THIS network, not from
 *     whatever ranking the source published. Every public pool tests from Europe and says so; a
 *     server that answers there routinely does not answer from here.
 *  2. The ranking is kept warm. When the live endpoint dies the next one is already chosen, so
 *     switching is a decision we made earlier rather than a search we start now.
 *  3. It survives being written to disk and read back. On a network where nothing can be fetched
 *     any more, yesterday's known-good list is the only thing that can still get a tunnel up.
 *
 * No Android imports, so all of it runs and is checkable on a desktop JVM.
 */
final class EndpointPool {

    /**
     * How many endpoints are kept on disk.
     *
     * <p>🚨 This was twenty, and twenty was the reason the engine kept running out of things to
     * try. A refresh brings back four hundred, the round that tests them binds forty-eight ports
     * for about eight seconds, and there are four rounds - so the search has room for roughly two
     * hundred endpoints and was being handed twenty. A device log showed it: nine candidates in
     * round one, then "no candidates left after 1 rounds", with three rounds of budget unused.
     *
     * <p>The saved list is also the only way back online on a network where nothing can be
     * fetched, so a bigger one is worth more than the disk it costs: two hundred entries is under
     * fifty kilobytes.
     */
    static final int KEEP = 200;

    /** One endpoint plus what this device has learned about it. */
    static final class Entry {
        final ProxyConfig config;
        int successes;
        int failures;
        long lastLatencyMillis = -1;
        long lastSuccessAt;
        /** Set when a probe or a connection just failed, so the selector skips it for a while. */
        long penaltyUntil;

        Entry(ProxyConfig config) { this.config = config; }

        /**
         * Higher is better. Built so that proven endpoints beat unproven ones, recent evidence
         * outweighs old evidence, and latency only decides between endpoints that both work.
         */
        double score(long now) {
            if (now < penaltyUntil) return -1;
            int attempts = successes + failures;
            // An endpoint with no history sits mid-table: worth trying, not worth trusting.
            double reliability = attempts == 0 ? 0.5 : (double) successes / attempts;
            // Confidence grows with evidence, so one lucky success cannot outrank ten.
            double confidence = attempts == 0 ? 0.35 : Math.min(1.0, attempts / 5.0);
            double base = reliability * confidence + 0.5 * (1 - confidence);

            double speed = 0;
            if (lastLatencyMillis >= 0) {
                // 100ms scores about 0.9, 1000ms about 0.5, 3000ms about 0.25.
                speed = 1.0 / (1.0 + lastLatencyMillis / 1000.0);
            }

            double freshness = 0;
            if (lastSuccessAt > 0) {
                long ageHours = (now - lastSuccessAt) / 3_600_000L;
                freshness = ageHours <= 1 ? 1.0 : Math.max(0, 1.0 - ageHours / 48.0);
            }

            double protocolBonus = 0;
            if (config.isReality()) protocolBonus = 0.08;
            else if ("hysteria2".equals(config.protocol)) protocolBonus = 0.06;

            return base * 0.55 + speed * 0.20 + freshness * 0.20 + protocolBonus;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Adds freshly fetched configs, keeping the history of any we already knew about. */
    void merge(List<ProxyConfig> configs) {
        if (configs == null) return;
        for (ProxyConfig config : configs) {
            if (config == null) continue;
            String key = config.key();
            if (!entries.containsKey(key)) entries.put(key, new Entry(config));
        }
    }

    int size() { return entries.size(); }

    Entry get(String key) { return entries.get(key); }

    void recordSuccess(String key, long latencyMillis, long now) {
        Entry entry = entries.get(key);
        if (entry == null) return;
        entry.successes++;
        entry.lastLatencyMillis = latencyMillis;
        entry.lastSuccessAt = now;
        entry.penaltyUntil = 0;
    }

    /**
     * Records a failure and benches the endpoint for a while. The penalty grows with repeated
     * failures so a server that is merely busy comes back quickly, while one that is genuinely
     * blocked drops out of rotation without being deleted — networks change, and an endpoint that
     * is dead on mobile data may be fine on wifi tomorrow.
     */
    void recordFailure(String key, long now) {
        Entry entry = entries.get(key);
        if (entry == null) return;
        entry.failures++;
        long minutes = Math.min(60, 2L << Math.min(5, entry.failures));
        entry.penaltyUntil = now + minutes * 60_000L;
    }

    /** Best first. Benched endpoints sink to the bottom rather than disappearing. */
    List<Entry> ranked(long now) {
        List<Entry> all = new ArrayList<>(entries.values());
        Collections.sort(all, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                return Double.compare(right.score(now), left.score(now));
            }
        });
        return all;
    }

    /**
     * Best first, but with the endpoints in {@code country} ahead of everything else.
     *
     * <p>A country is a preference, never a constraint. If it were a constraint, a user who picked
     * Japan on a night when no Japanese endpoint answers would get no tunnel at all — which is a
     * worse outcome than a working tunnel somewhere else, and one they cannot diagnose. So the
     * endpoints they asked for are tried first, every one of them, and the rest of the pool sits
     * behind as the reason the app stays connected instead of failing.
     *
     * <p>The exit country the user is actually given is read from the live tunnel and shown on the
     * location card, so preferring is never the same as pretending.
     */
    List<Entry> rankedFor(String country, long now) {
        List<Entry> ordered = ranked(now);
        if (StealthRegions.isAutomatic(country)) return ordered;
        List<Entry> wanted = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (Entry entry : ordered) {
            if (StealthRegions.matches(entry.config, country)) wanted.add(entry);
            else rest.add(entry);
        }
        wanted.addAll(rest);
        return wanted;
    }

    /** How many endpoints this pool holds in a country, benched ones included. */
    int countIn(String country) {
        if (StealthRegions.isAutomatic(country)) return entries.size();
        int count = 0;
        for (Entry entry : entries.values()) {
            if (StealthRegions.matches(entry.config, country)) count++;
        }
        return count;
    }

    /** The endpoint to dial now, or null when every single one is benched. */
    Entry best(long now) {
        Entry winner = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entry entry : entries.values()) {
            double score = entry.score(now);
            if (score >= 0 && score > bestScore) { bestScore = score; winner = entry; }
        }
        return winner;
    }

    /**
     * The endpoint to switch to when {@code failedKey} just died — chosen without touching the
     * network, which is what lets the swap happen faster than the user notices.
     */
    Entry nextAfter(String failedKey, long now) {
        for (Entry entry : ranked(now)) {
            if (entry.config.key().equals(failedKey)) continue;
            if (entry.score(now) < 0) continue;
            return entry;
        }
        return null;
    }

    /** Trims to the endpoints worth keeping, so the saved pool cannot grow without limit. */
    void prune(long now) {
        List<Entry> keep = ranked(now);
        if (keep.size() <= KEEP) return;
        Map<String, Entry> trimmed = new LinkedHashMap<>();
        for (int i = 0; i < KEEP; i++) {
            Entry entry = keep.get(i);
            trimmed.put(entry.config.key(), entry);
        }
        entries.clear();
        entries.putAll(trimmed);
    }

    /**
     * Serialises the pool so it survives a restart, and more importantly survives the network
     * that fetched it going away. One entry per line: the original URI, then its history.
     */
    String serialise() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries.values()) {
            out.append(entry.config.raw.replace('\n', ' ').replace('\t', ' ')).append('\t')
               .append(entry.successes).append('\t')
               .append(entry.failures).append('\t')
               .append(entry.lastLatencyMillis).append('\t')
               .append(entry.lastSuccessAt).append('\n');
        }
        return out.toString();
    }

    /**
     * Reads a saved pool back. Anything malformed is skipped rather than throwing: a corrupted
     * line must cost us one endpoint, never the whole saved list — that list may be the only way
     * back online.
     *
     * Penalties are deliberately NOT restored. They describe a network we may no longer be on.
     */
    static EndpointPool deserialise(String text) {
        EndpointPool pool = new EndpointPool();
        if (text == null || text.isEmpty()) return pool;
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t");
            ProxyConfig config = ProxyConfig.parse(parts[0]);
            if (config == null) continue;
            Entry entry = new Entry(config);
            try {
                if (parts.length > 1) entry.successes = Integer.parseInt(parts[1]);
                if (parts.length > 2) entry.failures = Integer.parseInt(parts[2]);
                if (parts.length > 3) entry.lastLatencyMillis = Long.parseLong(parts[3]);
                if (parts.length > 4) entry.lastSuccessAt = Long.parseLong(parts[4]);
            } catch (NumberFormatException partial) {
                // Keep the endpoint, drop the unreadable history.
            }
            pool.entries.put(config.key(), entry);
        }
        return pool;
    }
}
