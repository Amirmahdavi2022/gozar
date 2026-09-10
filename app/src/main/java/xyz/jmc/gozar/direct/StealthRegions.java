package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Which exit countries the Stealth engine can offer, and how an endpoint's country is worked out.
 *
 * <p>Stealth is different from Global here. Global asks its own engine for a region and the engine
 * goes and finds a server there. Stealth has no engine to ask: it dials public endpoints, so the
 * only country it can offer is one the pool actually has servers in. That makes this class a
 * catalogue of measured supply rather than a list of places.
 *
 * <p><b>Where the country comes from.</b> The pools label almost every line with a flag emoji in
 * the {@code #fragment} — a pair of regional indicator characters that maps straight onto an ISO
 * 3166-1 alpha-2 code with no table and no guessing. Measured against the live sources: 2112 of
 * 2229 lines carried one, and where a country file could be checked against it the flag agreed
 * with the file (Netherlands 1213/1215, Singapore 725/727). Names in the label are not used: they
 * appear in half a dozen spellings and languages, and a wrong country is worse than no country.
 *
 * <p>An endpoint with no flag simply has no country. It stays in the pool and stays dialable on
 * Automatic; it just never satisfies a country the user picked.
 *
 * <p><b>Why the list is short.</b> Every country below was fetched and counted, keeping only the
 * ones Xray can actually dial — {@link XrayConfig#supports} rules out hysteria2 and tuic, which is
 * most of some countries' supply. Places with a handful of endpoints were left out on purpose: a
 * country that offers three servers is a country that fails, and failing after the user chose is
 * worse than never offering.
 *
 * <p>Android-free, so the catalogue and the parsing are checkable on a desktop JVM.
 */
final class StealthRegions {

    /** Let the pool's own scoring decide, with no country constraint. */
    static final String AUTOMATIC = "";

    /** The host serving the per-country lists. */
    static final String SOURCE_HOST = "raw.githubusercontent.com";

    /** Path of one country's list. The source publishes 54 of these and rebuilds them daily. */
    private static final String SOURCE_PATH =
            "/Delta-Kronecker/V2ray-Config/main/config/countries/%s.txt";

    /**
     * The offered countries, most supply first.
     *
     * <p>Counts are dialable endpoints measured on 2026/09/04 and are here as a record of why each
     * one made the list, not as a promise: NL 1213, SG 725, JP 645, KR 604, US 496, GB 494,
     * HK 405, AU 166, PL 156, TW 149, DE 147, NO 76, IE 51, FR 49, LT 44, CA 34, FI 31, AR 28,
     * SE 23, RU 20.
     *
     * <p>Iran is deliberately absent even though the source publishes it. An Iranian exit is not
     * an exit for anyone using this app.
     */
    private static final String[] OFFERED = {
            "NL", "SG", "JP", "KR", "US", "GB", "HK", "AU", "PL", "TW",
            "DE", "NO", "IE", "FR", "LT", "CA", "FI", "AR", "SE", "RU",
    };

    private StealthRegions() { }

    /** The countries worth showing, in offer order. Automatic is not in here; it is the default. */
    static List<String> offered() {
        return Collections.unmodifiableList(Arrays.asList(OFFERED));
    }

    /** True when this code is one we publish a country list for. */
    static boolean isOffered(String code) {
        String normalised = normalise(code);
        if (normalised.isEmpty()) return false;
        for (String offered : OFFERED) {
            if (offered.equals(normalised)) return true;
        }
        return false;
    }

    /** Upper-cased two-letter code, or {@link #AUTOMATIC} for anything that is not one. */
    static String normalise(String code) {
        if (code == null) return AUTOMATIC;
        String trimmed = code.trim().toUpperCase(Locale.US);
        if (trimmed.length() != 2) return AUTOMATIC;
        for (int i = 0; i < 2; i++) {
            char c = trimmed.charAt(i);
            if (c < 'A' || c > 'Z') return AUTOMATIC;
        }
        return trimmed;
    }

    static boolean isAutomatic(String code) { return normalise(code).isEmpty(); }

    /** Where to fetch one country's endpoints, or null when we do not offer that country. */
    static String pathFor(String code) {
        String normalised = normalise(code);
        if (normalised.isEmpty() || !isOffered(normalised)) return null;
        return String.format(SOURCE_PATH, normalised.toLowerCase(Locale.US));
    }

    /**
     * A display name for the picker, falling back to the bare code rather than showing nothing.
     *
     * <p>The fallback is not theoretical. {@code Locale.getDisplayCountry} does not return an empty
     * string for a code it does not know — on a desktop JVM it answers "Unknown Region", and the
     * ICU data behind it differs between Android versions. So anything that comes back looking
     * like a placeholder is discarded in favour of the code itself, which is at least true.
     */
    static String name(String code) {
        String normalised = normalise(code);
        if (normalised.isEmpty()) return "";
        // Inlined rather than pulled from a display helper: the only caller needs a label,
        // and the guard below already treats anything placeholder-looking as unusable.
        String name = new java.util.Locale("", normalised).getDisplayCountry(java.util.Locale.US);
        if (name.isEmpty() || name.equals(normalised) || name.startsWith("Unknown")) {
            return normalised;
        }
        return name;
    }

    /**
     * The country an endpoint sits in, taken from the flag emoji its label carries, or
     * {@link #AUTOMATIC} when the label has no flag.
     *
     * <p>Only the first flag counts. Some labels carry a second one — a channel badge, or a route
     * written as one flag to another — and the first is the server's own.
     */
    static String countryOf(ProxyConfig config) {
        return config == null ? AUTOMATIC : countryOfLabel(config.label);
    }

    static String countryOfLabel(String label) {
        if (label == null || label.isEmpty()) return AUTOMATIC;
        int length = label.length();
        for (int i = 0; i < length; ) {
            int first = label.codePointAt(i);
            int width = Character.charCount(first);
            if (isRegionalIndicator(first) && i + width < length) {
                int second = label.codePointAt(i + width);
                if (isRegionalIndicator(second)) {
                    char a = (char) ('A' + first - 0x1F1E6);
                    char b = (char) ('A' + second - 0x1F1E6);
                    return new String(new char[] { a, b });
                }
            }
            i += width;
        }
        return AUTOMATIC;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    /** True when this endpoint satisfies the country the user asked for. */
    static boolean matches(ProxyConfig config, String wanted) {
        String want = normalise(wanted);
        if (want.isEmpty()) return true;
        return want.equals(countryOf(config));
    }

    /**
     * The countries a set of endpoints actually covers, most represented first.
     *
     * <p>Used to show the picker what the device has right now rather than what the catalogue
     * claims, so a country the pool has lost can be marked as such instead of silently failing.
     */
    static List<String> present(List<ProxyConfig> configs) {
        List<String> order = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        if (configs != null) {
            for (ProxyConfig config : configs) {
                String code = countryOf(config);
                if (code.isEmpty()) continue;
                int at = order.indexOf(code);
                if (at < 0) { order.add(code); counts.add(1); }
                else counts.set(at, counts.get(at) + 1);
            }
        }
        // Simple insertion sort by count, descending; the list is at most a few dozen long.
        for (int i = 1; i < order.size(); i++) {
            String code = order.get(i);
            int count = counts.get(i);
            int j = i - 1;
            while (j >= 0 && counts.get(j) < count) {
                order.set(j + 1, order.get(j));
                counts.set(j + 1, counts.get(j));
                j--;
            }
            order.set(j + 1, code);
            counts.set(j + 1, count);
        }
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(order)));
    }
}
