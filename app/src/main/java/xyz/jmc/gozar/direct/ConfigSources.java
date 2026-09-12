package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where the quic engine's endpoints come from.
 *
 * Every one of these pools publishes a plain text list of public proxy URIs and rebuilds it on a
 * schedule. None of them is trustworthy on its own, and the honest reason is written on their own
 * pages: they test from a CI runner in Europe. A server that answers there frequently does not
 * answer from a filtered network. So this layer's job is only to gather candidates as widely and
 * as cheaply as possible; deciding what actually works is {@link EndpointPool}'s job, using
 * evidence from the device itself.
 *
 * Two rules that matter more than the source list:
 *
 *  - Fetching goes THROUGH the tunnel, never direct. On the networks this app exists for, the hosts
 *    below are blocked, so a direct fetch is the one thing guaranteed to fail exactly when it is
 *    needed. The carrier tunnel gets us to them.
 *  - A source that fails is skipped, never fatal. Sources disappear, get renamed, go private. One
 *    dead URL must cost its own entries and nothing else.
 *
 * Android-free, so the merging and failure handling are all checkable on a desktop JVM.
 */
final class ConfigSources {

    /** How many endpoints we are willing to carry forward from one refresh. */
    static final int MAX_CANDIDATES = 400;

    /**
     * The pools, most trusted first. Order matters: on a tie the earlier source's entry is kept,
     * and the earlier entries are the ones offered to the tester first.
     *
     * 🚨 Three files, all of them hysteria2 only, and the list got this short on measurement
     * rather than on taste. The app used to pull eight mixed dumps through the tunnel — around
     * half a megabyte a refresh — for a pool that was almost entirely vless and trojan, which
     * nothing here dials any more. Counted against the live files on 2026/09/12:
     *
     * <pre>
     *   radikal hysteria2      32 KB   199 lines   155 servers   155 new
     *   tgparse hysteria2      44 KB   176 lines    62 servers     8 new
     *   tgparse hy2             6 KB    38 lines    37 servers     3 new
     *   barry All_Configs    1817 KB   198 lines    96 servers     0 new
     *   epodonios All_Configs 1698 KB  128 lines    96 servers     0 new
     * </pre>
     *
     * The two big dumps are three and a half megabytes between them and contribute not one
     * server the small files do not already have. So they are gone, and what is left is 82 KB
     * for 166 distinct servers — a list that can be refreshed over a bad link without the
     * refresh itself being the reason the link is bad.
     *
     * Three publishers rather than one, because the failure that matters is not a file going
     * missing, it is a publisher going quiet.
     *
     * These are fetched as data, not vendored into the repo — we read a public list at runtime
     * the way any subscription client does, rather than redistributing anyone's files.
     */
    static final String[][] SOURCES = {
            // host, path, label
            {"raw.githubusercontent.com", "/0xRadikal/Free-v2ray-Configs/main/protocols/hysteria2.txt", "radikal-quic"},
            {"raw.githubusercontent.com", "/Surfboardv2ray/TGParse/main/splitted/hysteria2", "tgparse-quic"},
            {"raw.githubusercontent.com", "/Surfboardv2ray/TGParse/main/splitted/hy2", "tgparse-hy2"},
    };

    /** How a document is retrieved. The service supplies one that goes through the live tunnel. */
    interface Fetcher {
        /** Returns the document body, or null when the source could not be read. */
        String fetch(String host, String path) throws Exception;
    }

    /** What one refresh produced, so the caller can log something meaningful. */
    static final class Refresh {
        final List<ProxyConfig> configs;
        final List<String> succeeded;
        final List<String> failed;

        Refresh(List<ProxyConfig> configs, List<String> succeeded, List<String> failed) {
            this.configs = configs;
            this.succeeded = succeeded;
            this.failed = failed;
        }

        boolean isEmpty() { return configs.isEmpty(); }

        String summary() {
            return configs.size() + " endpoints from " + succeeded.size() + "/"
                    + (succeeded.size() + failed.size()) + " sources"
                    + (failed.isEmpty() ? "" : " (failed: " + join(failed) + ")");
        }

        private static String join(List<String> items) {
            StringBuilder out = new StringBuilder();
            for (String item : items) {
                if (out.length() > 0) out.append(", ");
                out.append(item);
            }
            return out.toString();
        }
    }

    private ConfigSources() { }

    /**
     * Reads every source and merges the results.
     *
     * Deliberately keeps going after a failure and returns whatever it got. A partial list is
     * worth far more than an exception: with two sources out of five reachable we can still put a
     * tunnel up, and on a bad network two out of five is the normal case, not the exceptional one.
     */
    static Refresh refresh(Fetcher fetcher) { return refresh(fetcher, SOURCES, MAX_CANDIDATES); }

    /**
     * Reads the list for one country instead of the general pools.
     *
     * <p>Filtering the general pools by country does not work: two of the six sources are
     * themselves the Netherlands and Germany lists, so the merged pool is four fifths those two
     * countries and almost nothing else. Fetching the country's own published list is the only way
     * a user who picks Japan gets more than the handful of Japanese servers that happened to drift
     * into a general dump.
     *
     * <p>Returns an empty refresh for a country we do not offer, rather than fetching a path that
     * would 404. A country with no list is a country the picker should not have shown.
     */
    static Refresh refreshCountry(Fetcher fetcher, String code, int limit) {
        String path = StealthRegions.pathFor(code);
        if (path == null) {
            List<String> none = new ArrayList<>();
            return new Refresh(new ArrayList<ProxyConfig>(), none, none);
        }
        String label = "country-" + StealthRegions.normalise(code).toLowerCase(java.util.Locale.US);
        String[][] source = { { StealthRegions.SOURCE_HOST, path, label } };
        return refresh(fetcher, source, limit);
    }

    static Refresh refresh(Fetcher fetcher, String[][] sources, int limit) {
        List<ProxyConfig> merged = new ArrayList<>();
        List<List<ProxyConfig>> perSource = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String[] source : sources) {
            String label = source.length > 2 ? source[2] : source[0];
            String body = null;
            try {
                body = fetcher.fetch(source[0], source[1]);
            } catch (Throwable unreachable) {
                body = null;
            }
            if (body == null || body.trim().isEmpty()) {
                failed.add(label);
                continue;
            }
            List<ProxyConfig> parsed = ProxyConfig.parseDocument(body);
            if (parsed.isEmpty()) {
                // The document arrived but held nothing usable; that is a source failure too, and
                // worth telling apart from a network failure in the log.
                failed.add(label + " (unusable)");
                continue;
            }
            succeeded.add(label);
            perSource.add(parsed);
        }

        // Round robin rather than one source at a time. Concatenating meant the cap was spent
        // entirely on the first sources in the list and the last ones contributed nothing at all,
        // which quietly undid the reason for having several of them: the sources that matter most
        // are the ones the others do not overlap, and those were the ones being dropped.
        for (int depth = 0; merged.size() < limit; depth++) {
            boolean tookSomething = false;
            for (List<ProxyConfig> fromOne : perSource) {
                if (merged.size() >= limit) break;
                if (depth >= fromOne.size()) continue;
                tookSomething = true;
                ProxyConfig config = fromOne.get(depth);
                if (seen.add(config.key())) merged.add(config);
            }
            if (!tookSomething) break;
        }
        return new Refresh(merged, succeeded, failed);
    }

    /**
     * Picks the candidates worth spending an on-device test on, spreading them across protocols.
     *
     * Without this, a pool that is ninety per cent plain VLESS would fill the whole test budget
     * with one protocol, and if that protocol is the one being filtered today the test finds
     * nothing. Interleaving by protocol means a filtered protocol costs us a share of the budget
     * rather than all of it.
     */
    static List<ProxyConfig> shortlist(List<ProxyConfig> configs, int budget) {
        List<ProxyConfig> out = new ArrayList<>();
        if (configs == null || configs.isEmpty() || budget <= 0) return out;

        List<ProxyConfig> reality = new ArrayList<>();
        List<ProxyConfig> hysteria = new ArrayList<>();
        List<ProxyConfig> tuic = new ArrayList<>();
        List<ProxyConfig> rest = new ArrayList<>();
        for (ProxyConfig config : configs) {
            if (config.isReality()) reality.add(config);
            else if ("hysteria2".equals(config.protocol)) hysteria.add(config);
            else if ("tuic".equals(config.protocol)) tuic.add(config);
            else rest.add(config);
        }

        List<List<ProxyConfig>> buckets = new ArrayList<>();
        buckets.add(reality);
        buckets.add(hysteria);
        buckets.add(tuic);
        buckets.add(rest);

        int index = 0;
        boolean tookSomething = true;
        while (out.size() < budget && tookSomething) {
            tookSomething = false;
            for (List<ProxyConfig> bucket : buckets) {
                if (out.size() >= budget) break;
                if (index < bucket.size()) {
                    out.add(bucket.get(index));
                    tookSomething = true;
                }
            }
            index++;
        }
        return Collections.unmodifiableList(out);
    }
}
