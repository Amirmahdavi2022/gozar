package xyz.jmc.gozar.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one {@link ProxyConfig} into the JSON the Stealth engine's core reads.
 *
 * <p>The core is a general-purpose proxy engine driven entirely by a JSON document. Everything the
 * Stealth engine does is decided here: which endpoint to dial, over which transport, and on which
 * local port to publish the SOCKS5 proxy that the TUN bridge is already pointed at.
 *
 * <p>Two rules shape the whole file:
 *
 * <ul>
 *   <li><b>The SOCKS port is fixed.</b> The TUN bridge is configured once, at connect time, to talk
 *       to a port on loopback. If the engine keeps that same port while swapping which server it
 *       dials out to, the TUN interface is never rebuilt and nothing on the phone notices the
 *       change. A port chosen at random per run would force the bridge to be rebuilt on every swap,
 *       which is exactly the dropped connection this design exists to avoid.
 *   <li><b>No routing rules that need the geo databases.</b> Everything goes out through the single
 *       proxy outbound. That keeps the config honest about what it does, and it means the two
 *       geo data files the core library ships can be stripped out of the build - they are 28 MB
 *       that would otherwise be carried in every APK for rules we never write.
 * </ul>
 *
 * <p>Nothing here touches Android or the core library, so it runs on a desktop JVM and is tested
 * for real rather than eyeballed.
 */
final class XrayConfig {

    /**
     * Where the Stealth engine publishes its SOCKS5 proxy.
     *
     * <p>Deliberately not the port the Turbo engine uses. During a handover both engines are alive
     * for a moment, and two listeners on one port means the second one fails to bind.
     */
    static final int SOCKS_PORT = 1820;

    /** Loopback only. The proxy must never be reachable from off the device. */
    static final String SOCKS_LISTEN = "127.0.0.1";

    /** The outbound tag the carrier proxy is published under when the engine is chained. */
    static final String CARRIER_TAG = "carrier";

    /** The outbound that opens sockets on this network with no proxy in front of them. */
    static final String DIRECT_TAG = "direct";

    /**
     * The outbound that splits the TLS client hello across several writes before it leaves.
     *
     * <p>🔑 Why this exists: filtering here does not read a whole connection, it reads the first
     * packet. A client hello that arrives in one piece hands the inspector the server name in a
     * single well-formed record; the same hello delivered in several small writes, milliseconds
     * apart, has to be reassembled before it can be matched, and the equipment doing the matching
     * mostly does not bother. It changes nothing about the connection itself - same protocol, same
     * certificate, same server - so an endpoint that would have worked still works, and one that
     * was being cut at the hello now has a chance.
     *
     * <p>Used only on the direct route. The chained route already leaves this network inside the
     * carrier's own tunnel, where there is no hello to read, and adding a second layer there would
     * be changing the one path that is known to work.
     */
    static final String FRAGMENT_TAG = "fragment";

    /**
     * The outbound that points at the local handshake-shaping proxy.
     *
     * <p>Same mechanism as {@link #CARRIER_TAG} — an ordinary SOCKS5 hop named by
     * {@code sockopt.dialerProxy}. The difference is only where it leads: the carrier moves the
     * connection's origin off this network, while this one leaves from here and reshapes the
     * handshake on the way out.
     */
    static final String SPOOF_TAG = "spoof";

    private XrayConfig() { }

    /**
     * Whether the engine can actually dial this endpoint.
     *
     * <p>🚨 This is narrower than what {@link ProxyConfig} can parse, and the difference matters.
     * The pools are full of hysteria2 and tuic entries, and the parser reads them correctly, but
     * this core has no working client for either: tuic it does not implement at all, and its
     * hysteria client config carries only a version, an address and a port - there is nowhere to
     * put the password, so an endpoint built from a hysteria2 URI could never authenticate.
     * Handing those to the engine would burn test budget and connection attempts on endpoints that
     * cannot succeed, so they are filtered out here instead, at the one place that knows.
     *
     * <p>What is left still covers the large majority of every pool we fetch, including all of the
     * Reality entries, which are the ones worth having.
     */
    static boolean supports(ProxyConfig endpoint) {
        if (endpoint == null || endpoint.host == null || endpoint.host.isEmpty()) return false;
        if (endpoint.port <= 0 || endpoint.port > 65535) return false;
        if (endpoint.id == null || endpoint.id.isEmpty()) return false;
        switch (endpoint.protocol) {
            case "vless":
            case "trojan":
                return true;
            case "ss":
                return supportedCipher(endpoint.id);
            default:
                return false;
        }
    }

    /**
     * Whether this core would accept the cipher an ss:// line carries.
     *
     * 🚨 Checked here rather than left to the core, because the core's answer is all-or-nothing.
     * A round puts many endpoints in one document, and a single method it does not recognise makes
     * it reject the whole file — every other endpoint in that round dies for one bad line, and the
     * log reads as "nothing in the pool answered" when in truth nothing in the pool was ever
     * dialled. The feeds do republish junk, so this has to be caught before the document is built.
     *
     * The list is the AEAD set Xray implements, plus the two 2022 forms. Anything else — the
     * retired stream ciphers, a typo, a line whose credential never decoded — is dropped.
     */
    private static boolean supportedCipher(String credential) {
        int colon = credential.indexOf(':');
        if (colon <= 0) return false;
        String method = credential.substring(0, colon).trim().toLowerCase(Locale.US);
        switch (method) {
            case "aes-128-gcm":
            case "aes-192-gcm":
            case "aes-256-gcm":
            case "chacha20-poly1305":
            case "chacha20-ietf-poly1305":
            case "xchacha20-poly1305":
            case "xchacha20-ietf-poly1305":
            case "2022-blake3-aes-128-gcm":
            case "2022-blake3-aes-256-gcm":
            case "2022-blake3-chacha20-poly1305":
            case "none":
            case "plain":
                return true;
            default:
                return false;
        }
    }

    /** Keeps only the endpoints this engine can dial, in the order they were given. */
    static List<ProxyConfig> supported(List<ProxyConfig> endpoints) {
        List<ProxyConfig> out = new ArrayList<>();
        if (endpoints == null) return out;
        for (ProxyConfig endpoint : endpoints) {
            if (supports(endpoint)) out.add(endpoint);
        }
        return out;
    }

    /** Builds the config for one endpoint on the standard port, dialling out directly. */
    static String build(ProxyConfig endpoint) {
        return build(endpoint, SOCKS_PORT, "warning", null);
    }

    /** Builds the config for one endpoint on the standard port, dialling out through a carrier. */
    static String build(ProxyConfig endpoint, String carrier) {
        return build(endpoint, SOCKS_PORT, "warning", carrier);
    }

    /** Kept so existing callers that never chained read the same as they always did. */
    static String build(ProxyConfig endpoint, int socksPort, String logLevel) {
        return build(endpoint, socksPort, logLevel, null);
    }

    /**
     * Builds the config document.
     *
     * @param endpoint  the server to dial; must pass {@link #supports}
     * @param socksPort the loopback port to publish the SOCKS5 proxy on
     * @param logLevel  one of the core's levels: debug, info, warning, error, none
     * @param carrier   a {@code host:port} SOCKS5 proxy to dial the endpoint <em>through</em>, or
     *                  null to dial it directly
     * @throws IllegalArgumentException if the endpoint is one the engine cannot dial, so a
     *                                  mistake shows up here rather than as an opaque core error
     */
    static String build(ProxyConfig endpoint, int socksPort, String logLevel, String carrier) {
        if (!supports(endpoint)) {
            throw new IllegalArgumentException("The Stealth engine cannot dial " + endpoint);
        }
        if (socksPort <= 0 || socksPort > 65535) {
            throw new IllegalArgumentException("Invalid SOCKS port: " + socksPort);
        }

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"log\":{\"loglevel\":").append(quote(level(logLevel))).append("},");

        json.append("\"inbounds\":[{\"tag\":\"socks-in\",\"listen\":").append(quote(SOCKS_LISTEN))
            .append(",\"port\":").append(socksPort)
            .append(",\"protocol\":\"socks\",\"settings\":{\"auth\":\"noauth\",\"udp\":true,")
            .append("\"address\":\"127.0.0.1\"},")
            // Sniffing recovers the real hostname from the traffic itself. The bridge hands us an
            // IP address, so without this the server name never reaches the outbound and TLS to a
            // virtual host fails.
            .append("\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],")
            .append("\"routeOnly\":false}}],");

        // 🚨 The core runs as a plain executable, and a Go binary on Android has no
        // /etc/resolv.conf to read - the platform resolves names through netd, which the binary
        // cannot reach. Endpoints published as a hostname rather than an address would simply
        // never resolve. Giving the core its own resolver removes the dependency on the platform
        // entirely, and has the side benefit that the lookup for the server we are about to dial
        // does not go to whatever resolver the local network handed us.
        //
        // 🚨 Over TCP, not UDP, and that is not a detail. These queries have to go somewhere that
        // is reachable BEFORE the tunnel exists, which rules out sending them through the endpoint
        // we are still trying to dial - see the routing rules below - and the routes that are
        // available at that moment are a plain socket or the carrier's SOCKS proxy. A SOCKS proxy
        // carries UDP only if it implements UDP ASSOCIATE, which is exactly what the carrier does
        // not do, so a UDP resolver would leave every hostname endpoint unresolvable on the one
        // route this engine relies on.
        json.append("\"dns\":{\"servers\":[\"tcp://1.1.1.1\",\"tcp://8.8.8.8\"],")
            .append("\"queryStrategy\":\"UseIP\",\"disableCache\":false},");

        String[] hop = carrierHop(carrier);
        boolean chained = hop != null;
        boolean fragmented = !chained && isSecured(endpoint);
        json.append("\"outbounds\":[");
        appendProxyOutbound(json, endpoint, "proxy",
                chained ? CARRIER_TAG : fragmented ? FRAGMENT_TAG : null);
        if (fragmented) appendFragmentOutbound(json);
        if (chained) appendCarrierOutbound(json, hop[0], Integer.parseInt(hop[1]));
        json.append(",{\"tag\":").append(quote(DIRECT_TAG)).append(",\"protocol\":\"freedom\"}")
            .append(",{\"tag\":\"block\",\"protocol\":\"blackhole\"}],");

        // AsIs keeps the sniffed name as the name: no lookup happens on this device, so a poisoned
        // resolver on the local network cannot redirect anything.
        //
        // 🚨 The two rules are the fix for a deadlock that cost every hostname endpoint eight
        // seconds and then failed it. With no rules at all, the core's own DNS queries fall to the
        // first outbound, which is the proxy - so resolving the server we are about to dial
        // required a tunnel through that same server to already be up. The queries timed out, the
        // endpoint was recorded as dead, and the endpoint was fine.
        //
        // Order matters and the first rule is what makes the second one safe. Everything arriving
        // from the phone goes through the tunnel, full stop, including the apps' own DNS on port
        // 53: without that rule first, the port-53 rule below would push app lookups out onto this
        // network in the clear, which is precisely the leak the tunnel exists to prevent. Only the
        // core's internal resolver reaches the second rule, because it is the only traffic here
        // that does not come from an inbound.
        json.append("\"routing\":{\"domainStrategy\":\"AsIs\",\"rules\":[")
            .append("{\"type\":\"field\",\"inboundTag\":[\"socks-in\"],\"outboundTag\":\"proxy\"},")
            .append("{\"type\":\"field\",\"port\":\"53\",\"outboundTag\":")
            .append(quote(chained ? CARRIER_TAG : DIRECT_TAG)).append("}]}}");
        return json.toString();
    }

    /**
     * One endpoint tried one way — the unit the fan-out config is built from.
     *
     * <p>The mode travels with the endpoint rather than being applied to the whole config,
     * because the point of the fan-out is that the same server can be tried three ways at once.
     */
    static final class Attempt {
        final ProxyConfig endpoint;
        final int mode;

        Attempt(ProxyConfig endpoint, int mode) {
            if (endpoint == null) throw new IllegalArgumentException("No endpoint");
            this.endpoint = endpoint;
            this.mode = mode;
        }

        @Override public String toString() { return endpoint + "/" + mode; }
    }

    /** The inbound tag for attempt {@code index}. Paired with {@link #outTag}. */
    static String inTag(int index) { return "in-" + index; }

    /** The outbound tag for attempt {@code index}. Paired with {@link #inTag}. */
    static String outTag(int index) { return "out-" + index; }

    /**
     * A config that tries many attempts at once, each on its own local port.
     *
     * <p>🔑 This exists because the engine was testing one endpoint at a time, and the cost was
     * not the network — it was the process. Every attempt stopped the core, started a fresh one,
     * waited for its listener, probed it and killed it: about six seconds of wall clock to learn
     * one bit about one server. A pool of four hundred cannot be searched six seconds at a time,
     * and in practice a whole connect budget bought evidence about six of them.
     *
     * <p>The core itself was never the limit. Xray will hold as many inbounds and outbounds as it
     * is given, and a routing rule per pair keeps them from mixing: traffic arriving on port
     * {@code basePort + i} leaves through attempt {@code i} and nowhere else. So one process
     * start buys N simultaneous probes instead of one, and the probes run in parallel because
     * they are separate sockets, not separate processes.
     *
     * <p>The route is a property of each attempt, not of the config, so the same server can sit
     * on three ports at once — direct, shaped and carried — and the first port to answer says
     * both which server works and which way it works. That was previously three sequential
     * six-second attempts.
     *
     * <p>Nothing here dials on its own. An outbound is inert until something connects to its
     * inbound, so a config of eighty attempts costs eighty listening sockets and no traffic.
     *
     * @param attempts what to try, in the order their ports are assigned
     * @param basePort the first local port; attempt {@code i} listens on {@code basePort + i}
     * @param carrier  {@code host:port} of the carrier's SOCKS proxy, or null if none is up
     * @param spoof    {@code host:port} of the local shaping proxy, or null if it is not running
     */
    static String buildFanout(java.util.List<Attempt> attempts, int basePort, String logLevel,
                              String carrier, String spoof) {
        if (attempts == null || attempts.isEmpty()) {
            throw new IllegalArgumentException("No attempts to fan out");
        }
        if (basePort <= 0 || basePort + attempts.size() - 1 > 65535) {
            throw new IllegalArgumentException("Port range does not fit: " + basePort
                    + " + " + attempts.size());
        }
        String[] carrierHop = carrierHop(carrier);
        String[] spoofHop = carrierHop(spoof);

        // Worked out before anything is written, so an attempt whose hop is missing is refused
        // here rather than silently emitted as a direct dial. A spoof attempt quietly demoted to
        // direct would be recorded as "shaping did not help" on evidence that never involved it.
        String[] hops = new String[attempts.size()];
        boolean needCarrier = false, needSpoof = false, needFragment = false;
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            if (!supports(attempt.endpoint)) {
                throw new IllegalArgumentException("The Stealth engine cannot dial "
                        + attempt.endpoint);
            }
            switch (attempt.mode) {
                case DialMode.CHAINED:
                    if (carrierHop == null) {
                        throw new IllegalArgumentException("Chained attempt with no carrier");
                    }
                    hops[i] = CARRIER_TAG;
                    needCarrier = true;
                    break;
                case DialMode.SPOOF:
                    if (spoofHop == null) {
                        throw new IllegalArgumentException("Spoof attempt with no shaping proxy");
                    }
                    hops[i] = SPOOF_TAG;
                    needSpoof = true;
                    break;
                default:
                    // Same rule as the single-endpoint config: fragment the hello on the direct
                    // route only, and only when there is a TLS hello to fragment.
                    if (isSecured(attempt.endpoint)) {
                        hops[i] = FRAGMENT_TAG;
                        needFragment = true;
                    }
                    break;
            }
        }

        StringBuilder json = new StringBuilder(1024 + 512 * attempts.size());
        json.append("{\"log\":{\"loglevel\":").append(quote(level(logLevel))).append("},");

        json.append("\"inbounds\":[");
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0) json.append(",");
            json.append("{\"tag\":").append(quote(inTag(i))).append(",\"listen\":")
                .append(quote(SOCKS_LISTEN)).append(",\"port\":").append(basePort + i)
                .append(",\"protocol\":\"socks\",\"settings\":{\"auth\":\"noauth\",\"udp\":true,")
                .append("\"address\":\"127.0.0.1\"},")
                .append("\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],")
                .append("\"routeOnly\":false}}");
        }
        json.append("],");

        // Same reasoning as the single-endpoint config: the binary has no /etc/resolv.conf, and
        // these queries have to be answerable before any tunnel exists, so they go over TCP to a
        // route that is up already.
        json.append("\"dns\":{\"servers\":[\"tcp://1.1.1.1\",\"tcp://8.8.8.8\"],")
            .append("\"queryStrategy\":\"UseIP\",\"disableCache\":false},");

        json.append("\"outbounds\":[");
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0) json.append(",");
            appendProxyOutbound(json, attempts.get(i).endpoint, outTag(i), hops[i]);
        }
        if (needFragment) appendFragmentOutbound(json);
        if (needCarrier) appendCarrierOutbound(json, carrierHop[0], Integer.parseInt(carrierHop[1]));
        if (needSpoof) {
            appendSocksOutbound(json, SPOOF_TAG, spoofHop[0], Integer.parseInt(spoofHop[1]));
        }
        json.append(",{\"tag\":").append(quote(DIRECT_TAG)).append(",\"protocol\":\"freedom\"}")
            .append(",{\"tag\":\"block\",\"protocol\":\"blackhole\"}],");

        json.append("\"routing\":{\"domainStrategy\":\"AsIs\",\"rules\":[");
        for (int i = 0; i < attempts.size(); i++) {
            json.append("{\"type\":\"field\",\"inboundTag\":[").append(quote(inTag(i)))
                .append("],\"outboundTag\":").append(quote(outTag(i))).append("},");
        }
        // Every inbound is claimed above, so only the core's own resolver reaches this rule -
        // exactly as in the single-endpoint config, and for the same reason.
        json.append("{\"type\":\"field\",\"port\":\"53\",\"outboundTag\":")
            .append(quote(needCarrier ? CARRIER_TAG : DIRECT_TAG)).append("}]}}");
        return json.toString();
    }

    /**
     * The hop that makes this engine survive a network where its own endpoints are blocked.
     *
     * <p>🔑 Reaching a public endpoint from the outside is one problem; reaching it from a network
     * that filters it is another, and no choice of endpoint solves the second. Dialling through
     * the carrier moves the outgoing connection's origin off this network, so the endpoint is
     * dialled from wherever the carrier exits instead of from here. The exit the user sees is
     * still the endpoint's own country - only the first hop changes - which is the whole reason
     * this engine exists.
     *
     * <p>The core's own name for it is {@code sockopt.dialerProxy}: the outbound keeps its
     * protocol, its TLS and its transport exactly as they were, and only the socket underneath is
     * opened by another outbound.
     */
    private static void appendCarrierOutbound(StringBuilder json, String host, int port) {
        appendSocksOutbound(json, CARRIER_TAG, host, port);
    }

    /** Any named SOCKS5 hop. Both the carrier and the shaping proxy are exactly this. */
    private static void appendSocksOutbound(StringBuilder json, String tag, String host, int port) {
        json.append(",{\"tag\":").append(quote(tag))
            .append(",\"protocol\":\"socks\",\"settings\":{\"servers\":[{\"address\":")
            .append(quote(host)).append(",\"port\":").append(port).append("}]}}");
    }

    /**
     * Splits a {@code host:port} carrier address, or returns null if there is nothing usable.
     *
     * <p>Null rather than an exception: a missing or malformed carrier means "dial directly",
     * which is a working configuration, not an error.
     */
    static String[] carrierHop(String carrier) {
        if (carrier == null) return null;
        String trimmed = carrier.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) return null;
        String host = trimmed.substring(0, colon).trim();
        if (host.isEmpty()) return null;
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        return new String[] { host, String.valueOf(port) };
    }

    /**
     * The freedom outbound that does the splitting. See {@link #FRAGMENT_TAG}.
     *
     * <p>The numbers are the ones the clients that work on this kind of network have settled on:
     * split the hello into 50-100 byte pieces with 10-20ms between them. Small enough that the
     * server name never lands whole in one packet, spaced enough that the pieces are not simply
     * coalesced back together on the way out.
     */
    private static void appendFragmentOutbound(StringBuilder json) {
        json.append(",{\"tag\":").append(quote(FRAGMENT_TAG))
            .append(",\"protocol\":\"freedom\",\"settings\":{\"fragment\":{")
            .append("\"packets\":\"tlshello\",\"length\":\"50-100\",\"interval\":\"10-20\"}}")
            // Without this the pieces can be buffered and sent as one, which would undo the whole
            // thing quietly - the config would look right and behave exactly as it did before.
            .append(",\"streamSettings\":{\"sockopt\":{\"TcpNoDelay\":true}}}");
    }

    private static void appendProxyOutbound(StringBuilder json, ProxyConfig endpoint, String tag,
                                            String hopTag) {
        json.append("{\"tag\":").append(quote(tag)).append(",\"protocol\":")
            .append(quote(protocolName(endpoint)))
            .append(",\"settings\":{");
        switch (endpoint.protocol) {
            case "vless":   appendVless(json, endpoint); break;
            case "trojan":  appendTrojan(json, endpoint); break;
            default:        appendShadowsocks(json, endpoint); break;
        }
        json.append("}");
        appendStreamSettings(json, endpoint, hopTag);
        json.append("}");
    }

    private static String protocolName(ProxyConfig endpoint) {
        return "ss".equals(endpoint.protocol) ? "shadowsocks" : endpoint.protocol;
    }

    private static void appendVless(StringBuilder json, ProxyConfig endpoint) {
        json.append("\"vnext\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"users\":[{\"id\":").append(quote(endpoint.id))
            .append(",\"encryption\":").append(quote(orDefault(endpoint, "encryption", "none")));
        // The flow only means anything alongside TLS or Reality. Sent on a plain connection the
        // core rejects the whole config, so a pool entry with a stray flow would cost us the
        // endpoint rather than just the option.
        String flow = param(endpoint, "flow");
        if (!flow.isEmpty() && isSecured(endpoint)) json.append(",\"flow\":").append(quote(flow));
        json.append(",\"level\":0}]}]");
    }

    private static void appendTrojan(StringBuilder json, ProxyConfig endpoint) {
        json.append("\"servers\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"password\":").append(quote(endpoint.id))
            .append(",\"level\":0}]");
    }

    private static void appendShadowsocks(StringBuilder json, ProxyConfig endpoint) {
        // The credential arrives as method:password, already base64-decoded by the parser.
        String method = "aes-256-gcm";
        String password = endpoint.id;
        int colon = endpoint.id.indexOf(':');
        if (colon > 0) {
            method = endpoint.id.substring(0, colon);
            password = endpoint.id.substring(colon + 1);
        }
        json.append("\"servers\":[{\"address\":").append(quote(endpoint.host))
            .append(",\"port\":").append(endpoint.port)
            .append(",\"method\":").append(quote(method))
            .append(",\"password\":").append(quote(password))
            .append(",\"uot\":false,\"level\":0}]");
    }

    private static void appendStreamSettings(StringBuilder json, ProxyConfig endpoint,
                                             String hopTag) {
        String network = network(endpoint);
        String security = security(endpoint);
        json.append(",\"streamSettings\":{\"network\":").append(quote(network))
            .append(",\"security\":").append(quote(security))
            // Resolve the server address through the core's own DNS above rather than through the
            // platform, for the same reason.
            .append(",\"sockopt\":{\"domainStrategy\":\"UseIP\"");
        if (hopTag != null) json.append(",\"dialerProxy\":").append(quote(hopTag));
        json.append("}");

        if ("reality".equals(security)) appendReality(json, endpoint);
        else if ("tls".equals(security)) appendTls(json, endpoint);

        switch (network) {
            case "ws":          appendWebsocket(json, endpoint); break;
            case "httpupgrade": appendHttpUpgrade(json, endpoint); break;
            case "xhttp":       appendXhttp(json, endpoint); break;
            case "grpc":        appendGrpc(json, endpoint); break;
            case "http":        appendHttp2(json, endpoint); break;
            case "kcp":         appendKcp(json, endpoint); break;
            default:            appendTcp(json, endpoint); break;
        }
        json.append("}");
    }

    private static void appendReality(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"realitySettings\":{\"serverName\":").append(quote(serverName(endpoint)))
            .append(",\"fingerprint\":").append(quote(orDefault(endpoint, "fp", "chrome")))
            .append(",\"publicKey\":").append(quote(param(endpoint, "pbk")))
            .append(",\"shortId\":").append(quote(param(endpoint, "sid")))
            .append(",\"spiderX\":").append(quote(param(endpoint, "spx")))
            .append(",\"show\":false}");
    }

    private static void appendTls(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"tlsSettings\":{\"serverName\":").append(quote(serverName(endpoint)))
            .append(",\"fingerprint\":").append(quote(orDefault(endpoint, "fp", "chrome")));
        // Pool entries often carry allowInsecure=1. The core removed that option outright and now
        // rejects the entire config when it sees it - which is how 20 real endpoints were quietly
        // failing until these configs were run through the actual core instead of checked against
        // documentation. The flag is dropped rather than translated: accepting any certificate
        // would undo the point of the TLS the entry asked for. An endpoint that truly needs it
        // fails its probe and gets benched, which is the honest outcome.
        String alpn = param(endpoint, "alpn");
        if (!alpn.isEmpty()) {
            json.append(",\"alpn\":[");
            String[] parts = alpn.split(",");
            for (int i = 0; i < parts.length; i++) {
                String value = parts[i].trim();
                if (value.isEmpty()) continue;
                if (i > 0) json.append(',');
                json.append(quote(value));
            }
            json.append(']');
        }
        json.append("}");
    }

    private static void appendWebsocket(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"wsSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendHttpUpgrade(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"httpupgradeSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendXhttp(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"xhttpSettings\":{\"path\":").append(quote(path(endpoint)))
            .append(",\"mode\":").append(quote(orDefault(endpoint, "mode", "auto")));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":").append(quote(host));
        json.append("}");
    }

    private static void appendGrpc(StringBuilder json, ProxyConfig endpoint) {
        String service = param(endpoint, "serviceName");
        if (service.isEmpty()) service = param(endpoint, "path");
        json.append(",\"grpcSettings\":{\"serviceName\":").append(quote(service))
            .append(",\"multiMode\":").append("multi".equals(param(endpoint, "mode")))
            .append(",\"idle_timeout\":60,\"health_check_timeout\":20}");
    }

    private static void appendHttp2(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"httpSettings\":{\"path\":").append(quote(path(endpoint)));
        String host = hostHeader(endpoint);
        if (!host.isEmpty()) json.append(",\"host\":[").append(quote(host)).append(']');
        json.append("}");
    }

    private static void appendKcp(StringBuilder json, ProxyConfig endpoint) {
        json.append(",\"kcpSettings\":{\"mtu\":1350,\"tti\":50,\"uplinkCapacity\":12,")
            .append("\"downlinkCapacity\":100,\"congestion\":false,\"readBufferSize\":1,")
            .append("\"writeBufferSize\":1,\"header\":{\"type\":")
            .append(quote(orDefault(endpoint, "headerType", "none"))).append("},\"seed\":")
            .append(quote(param(endpoint, "seed"))).append("}");
    }

    private static void appendTcp(StringBuilder json, ProxyConfig endpoint) {
        // Plain TCP needs no block at all unless the entry asks to be disguised as HTTP, in which
        // case the core needs the request shape spelled out.
        if (!"http".equalsIgnoreCase(param(endpoint, "headerType"))) {
            json.append(",\"tcpSettings\":{\"header\":{\"type\":\"none\"}}");
            return;
        }
        json.append(",\"tcpSettings\":{\"header\":{\"type\":\"http\",\"request\":{\"version\":\"1.1\",")
            .append("\"method\":\"GET\",\"path\":[").append(quote(path(endpoint))).append("],")
            .append("\"headers\":{\"Host\":[").append(quote(hostHeaderOrServer(endpoint)))
            .append("],\"User-Agent\":[\"Mozilla/5.0\"],\"Accept-Encoding\":[\"gzip, deflate\"],")
            .append("\"Connection\":[\"keep-alive\"],\"Pragma\":\"no-cache\"}}}}");
    }

    // --- small readers -------------------------------------------------------------------------

    /** The transport the entry declares, normalised to the names the core accepts. */
    static String network(ProxyConfig endpoint) {
        String type = param(endpoint, "type");
        if (type.isEmpty()) type = param(endpoint, "network");
        switch (type.toLowerCase(Locale.US)) {
            case "ws":          return "ws";
            case "grpc":        return "grpc";
            case "h2":
            case "http":        return "http";
            case "httpupgrade": return "httpupgrade";
            case "xhttp":
            case "splithttp":   return "xhttp";  // renamed upstream; pools still publish both
            case "kcp":
            case "mkcp":        return "kcp";
            default:            return "tcp";
        }
    }

    /** The TLS flavour, defaulting to none so a missing value never invents encryption. */
    static String security(ProxyConfig endpoint) {
        String security = param(endpoint, "security");
        switch (security.toLowerCase(Locale.US)) {
            case "reality": return "reality";
            case "tls":
            case "xtls":    return "tls";  // xtls as a transport security is long gone; tls is the
                                           // honest reading of an entry that still says it
            default:        return "none";
        }
    }

    private static boolean isSecured(ProxyConfig endpoint) {
        return !"none".equals(security(endpoint));
    }

    /** The name to present in the handshake: the explicit one, else the header host, else the address. */
    private static String serverName(ProxyConfig endpoint) {
        String sni = param(endpoint, "sni");
        if (!sni.isEmpty()) return sni;
        String host = param(endpoint, "host");
        if (!host.isEmpty()) return host;
        return endpoint.host;
    }

    private static String hostHeader(ProxyConfig endpoint) {
        String host = param(endpoint, "host");
        if (!host.isEmpty()) return host;
        return param(endpoint, "sni");
    }

    private static String hostHeaderOrServer(ProxyConfig endpoint) {
        String host = hostHeader(endpoint);
        return host.isEmpty() ? endpoint.host : host;
    }

    private static String path(ProxyConfig endpoint) {
        String path = param(endpoint, "path");
        if (path.isEmpty()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String param(ProxyConfig endpoint, String key) {
        Map<String, String> params = endpoint.params;
        if (params == null) return "";
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }

    private static String orDefault(ProxyConfig endpoint, String key, String fallback) {
        String value = param(endpoint, key);
        return value.isEmpty() ? fallback : value;
    }

    private static String level(String logLevel) {
        if (logLevel == null) return "warning";
        switch (logLevel.toLowerCase(Locale.US)) {
            case "debug":
            case "info":
            case "warning":
            case "error":
            case "none":
                return logLevel.toLowerCase(Locale.US);
            default:
                return "warning";
        }
    }

    /** Quotes and escapes a value. Pool data is untrusted text and lands straight in this JSON. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
