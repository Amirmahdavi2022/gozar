package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.List;

/**
 * What the app as a whole is able to dial, across every engine it has.
 *
 * <p>🚨 This exists because of a defect that was throwing away a quarter of every endpoint list.
 * The pool was filtered on the way in by {@link XrayConfig#supported} — one engine's idea of what
 * it can speak — so anything the OTHER engines could dial never reached the pool at all. Measured
 * against the live feeds this app already fetches: four hundred and eighty-three endpoints parsed
 * correctly, three hundred and fifty-seven kept, and a hundred and twenty of the hundred and
 * twenty-six discards were hysteria2 servers that a second engine dials perfectly well. They were
 * downloaded, parsed, checked against the wrong question, and binned, every refresh, for nothing.
 *
 * <p>So the pool now holds everything ANY engine can use, and each engine filters for itself when
 * it picks its own candidates. The two questions are genuinely different and conflating them is
 * what caused this:
 *
 * <ul>
 *   <li>"should this line be kept" — asked once, on the way into the pool, and the right answer is
 *       yes if anything in the app can dial it.
 *   <li>"can I dial this line" — asked by each engine about each candidate, and the right answer
 *       is different for each of them.
 * </ul>
 *
 * <p>Getting the second one wrong is loud: an engine hands its core a config it cannot speak and
 * the round fails visibly. Getting the first one wrong is silent, which is why it survived.
 */
final class Dialable {

    private Dialable() { }

    /** Whether any engine in this app can dial this endpoint. */
    static boolean any(ProxyConfig endpoint) {
        return XrayConfig.supports(endpoint) || HysteriaConfig.supports(endpoint);
    }

    /** Only the endpoints something here can dial, in the order they arrived. */
    static List<ProxyConfig> filter(List<ProxyConfig> endpoints) {
        List<ProxyConfig> out = new ArrayList<>();
        if (endpoints == null) return out;
        for (ProxyConfig endpoint : endpoints) if (any(endpoint)) out.add(endpoint);
        return out;
    }
}
