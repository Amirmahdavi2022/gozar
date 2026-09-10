package xyz.jmc.gozar.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Fetches a tiny endpoint through the tunnel's SOCKS port.
 *
 * The targets matter more than they look. They have to be small, reachable
 * everywhere, and dull enough that hitting them every fifteen seconds is
 * unremarkable to anyone watching. Captive portal endpoints fit exactly: every
 * phone on earth already hammers them.
 *
 * Cloudflare goes second rather than first, and that ordering is not cosmetic.
 * A large share of the free proxy pools this app will eventually race are
 * served by Cloudflare Workers, and a Worker cannot open a connection to a
 * Cloudflare address — so asking such an endpoint to prove itself against one
 * condemns a healthy endpoint as dead. Harmless for Tor, wrong the moment a
 * second engine lands, and cheaper to get right now than to rediscover later.
 *
 * The hostname is deliberately left unresolved here. With a proxy set, the JDK
 * hands the name to the SOCKS server rather than looking it up locally, so the
 * probe exercises the same path a real request takes and never leaks a lookup.
 */
class HttpProbe(
    private val targets: List<String> = listOf(
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://cp.cloudflare.com/generate_204",
    ),
    private val timeoutMs: Int = 8_000,
) : Prober {

    override suspend fun through(socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        for (target in targets) {
            try {
                val conn = URL(target).openConnection(proxy)
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.getInputStream().use { it.read() }
                return@withContext true
            } catch (_: IOException) {
                // Try the next one. A single unreachable target says nothing;
                // all of them failing says the tunnel is dead.
            }
        }
        false
    }
}
