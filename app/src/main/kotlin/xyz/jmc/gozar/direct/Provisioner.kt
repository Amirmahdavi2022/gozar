package xyz.jmc.gozar.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Fills the endpoint pool, through whatever tunnel is already up.
 *
 * This is where the two engines stop being two apps that happen to share a screen and start
 * covering each other. The lists this engine needs are hosted on services that are blocked on
 * exactly the networks the engine exists for, so fetching them directly is the one thing
 * guaranteed to fail when it matters. Tor is slow but it gets through, so Tor fetches, and the
 * fast engine dials what Tor found. Next time the fast engine wins the race and does its own
 * refreshing.
 *
 * Deliberately never fetches direct, even when direct would work. A refresh that sometimes goes
 * through the tunnel and sometimes does not is a refresh whose failures nobody can explain, and it
 * leaks the fact that this phone is collecting proxy lists to whoever is watching the line.
 */
internal object Provisioner {

    /** Long enough for eight files through three volunteer hops, short enough to give up on. */
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 45_000

    /**
     * @return how many endpoints the pool holds afterwards, or -1 if nothing could be read
     */
    suspend fun refresh(
        store: PoolStore,
        socksPort: Int,
        log: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        val fetcher = object : ConfigSources.Fetcher {
            override fun fetch(host: String, path: String): String? {
                val conn = URL("https://$host$path").openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                // Some of these hosts answer 403 to a request with no user agent, which reads as
                // a dead source and is not one.
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode != 200) return null
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    return out.toString("UTF-8")
                }
            }
        }

        val refresh = runCatching { ConfigSources.refresh(fetcher) }.getOrNull()
        if (refresh == null || refresh.isEmpty) {
            log("could not refresh the endpoint list")
            return@withContext -1
        }

        log(refresh.summary())

        // Merged into what is already there rather than replacing it. The scores are this
        // device's own evidence and they are worth more than anything a feed can tell us; feeds
        // republish dead servers every quarter of an hour.
        val pool = store.load()
        pool.merge(Dialable.filter(refresh.configs))
        pool.prune(System.currentTimeMillis())
        store.save(pool)

        pool.size()
    }
}
