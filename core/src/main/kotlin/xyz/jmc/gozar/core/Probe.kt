package xyz.jmc.gozar.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches a tiny endpoint through the tunnel's SOCKS port.
 *
 * The targets matter more than they look. They have to be small, reachable
 * everywhere, and dull enough that hitting them every fifteen seconds is
 * unremarkable to anyone watching. Captive portal endpoints fit exactly: every
 * phone on earth already hammers them.
 */
class HttpProbe(
    private val targets: List<String> = listOf(
        "http://cp.cloudflare.com/generate_204",
        "http://connectivitycheck.gstatic.com/generate_204",
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
