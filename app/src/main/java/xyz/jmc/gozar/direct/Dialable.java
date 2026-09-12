package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.List;

/**
 * What the app as a whole is able to dial, across every engine it has.
 *
 * <p>🚨 Keep this separate from any one engine's idea of what it can speak, even now that only
 * one engine dials a list at all. The last time the two were conflated, the pool was filtered on
 * the way in by the old TLS core's rules and every hysteria2 line — a quarter of every feed — was
 * downloaded, parsed, checked against the wrong question and binned, on every refresh, for
 * nothing. The two questions are genuinely different:
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
        return HysteriaConfig.supports(endpoint);
    }

    /** Only the endpoints something here can dial, in the order they arrived. */
    static List<ProxyConfig> filter(List<ProxyConfig> endpoints) {
        List<ProxyConfig> out = new ArrayList<>();
        if (endpoints == null) return out;
        for (ProxyConfig endpoint : endpoints) if (any(endpoint)) out.add(endpoint);
        return out;
    }
}
