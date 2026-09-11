package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where the Stealth engine's endpoints come from.
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
 *  - Fetching goes THROUGH the tunnel, never direct. On the networks Panther exists for, the hosts
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
     * Every path here was fetched, parsed and counted before being written down. Two numbers
     * decided the list: how many endpoints this core can actually dial out of a source, and how
     * many of those no other source already has. A source that duplicates another is bytes pulled
     * through a tunnel for nothing.
     *
     * Measured 2026/09/04 against the live files: 8 sources, ~500 KB, about 1600 dialable
     * endpoints. Four things were dropped on that evidence. The hysteria2 file yielded ZERO
     * dialable endpoints, because Xray cannot dial hysteria2 at all - 28 KB per refresh for
     * nothing. The two per-country files were 1 MB between them for one country each, and country
     * choice fetches its own list on demand now. And two other candidates turned out to be exact
     * subsets of sources already here.
     *
     * Eight repositories rather than three, which is the point: the failure that matters is not
     * one file going missing, it is one publisher going quiet.
     *
     * These are fetched as data, not vendored into the repo — we read a public list at runtime the
     * way any subscription client does, rather than redistributing anyone's files.
     */
    static final String[][] SOURCES = {
            // host, path, label
            {"raw.githubusercontent.com", "/0xRadikal/Free-v2ray-Configs/main/top100.txt", "radikal-top"},
            {"raw.githubusercontent.com", "/MahanKenway/Freedom-V2Ray/main/configs/vless_sub.txt", "freedom-vless"},
            {"raw.githubusercontent.com", "/MahanKenway/Freedom-V2Ray/main/configs/trojan_sub.txt", "freedom-trojan"},
            {"raw.githubusercontent.com", "/iboxz/free-v2ray-collector/main/main/mix.txt", "iboxz-mix"},
            {"raw.githubusercontent.com", "/V2RayRoot/V2RayConfig/main/Config/vless.txt", "v2rayroot-vless"},
            {"raw.githubusercontent.com", "/V2RayRoot/V2RayConfig/main/Config/shadowsocks.txt", "v2rayroot-ss"},
            {"raw.githubusercontent.com", "/sinavm/SVM/main/lite/subscriptions/xray/normal/reality", "sinavm-reality"},
            {"raw.githubusercontent.com", "/barry-far/V2ray-Config/main/Splitted-By-Protocol/trojan.txt", "barry-trojan"},
            // 🚨 Added when the third engine was. Every source above this line carries vless,
            // trojan and ss almost exclusively, so without a feed of its own the quic engine had
            // a pool of whatever handful of hysteria2 lines happened to fall out of the mixed
            // dumps - which on a bad day is none, and an engine with nothing to dial is the
            // "three engines on paper, one on the wire" problem all over again. This one is a
            // dedicated hysteria2 list: measured live, a hundred and forty-two lines, eighty of
            // them carrying salamander obfuscation already.
            {"raw.githubusercontent.com", "/0xRadikal/Free-v2ray-Configs/main/protocols/hysteria2.txt", "radikal-quic"},
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
