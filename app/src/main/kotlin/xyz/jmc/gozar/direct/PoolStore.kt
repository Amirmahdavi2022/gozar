package xyz.jmc.gozar.direct

import java.io.File

/**
 * Where the endpoint pool lives between runs.
 *
 * Persisting it is not an optimisation, it is the whole reason this engine can come up on a
 * network where nothing can be fetched. Yesterday's list, scored from this device's own
 * experience, is often the only way back online — and the day the lists themselves are blocked is
 * exactly the day the engine is needed.
 */
internal interface PoolStore {
    fun load(): EndpointPool
    fun save(pool: EndpointPool)
}

internal class FilePoolStore(
    private val file: File,
    private val seed: () -> String? = { null },
) : PoolStore {

    /**
     * Read fresh each time rather than cached. It is a few hundred lines, and the alternative is
     * two views of the pool that disagree after a background refresh has written to it.
     *
     * 🚨 The seed is what stops the fast engine being decoration. It stands down when the pool is
     * empty; the pool is filled by fetching lists that are blocked on the networks this engine
     * exists for, so the fetch needs a tunnel that is already up; and the only other engine is
     * Tor. On a fresh install that circle has no way in, so every launch skipped the fast engine
     * and the app was Tor and nothing else — three engines on paper, one on the wire. A list
     * shipped in the apk gives it something to dial on the first press.
     *
     * Read once and written straight to disk, so the parse is paid on a first run and never
     * again, and so this device's own scores start accumulating immediately.
     */
    override fun load(): EndpointPool {
        val saved = runCatching { EndpointPool.deserialise(file.readText()) }.getOrNull()
        if (saved != null && saved.size() > 0) return saved

        val planted = plant() ?: return saved ?: EndpointPool()
        save(planted)
        return planted
    }

    private fun plant(): EndpointPool? {
        val document = runCatching { seed() }.getOrNull()
        if (document.isNullOrBlank()) return null

        val parsed = runCatching { XrayConfig.supported(ProxyConfig.parseDocument(document)) }
            .getOrNull()
        if (parsed.isNullOrEmpty()) return null

        val pool = EndpointPool()
        pool.merge(parsed)
        return pool
    }

    override fun save(pool: EndpointPool) {
        runCatching { file.writeText(pool.serialise()) }
    }
}

/**
 * How many endpoints are on file.
 *
 * Here rather than at the call site because the pool type is deliberately package-private — the
 * rest of the app has no business handling endpoints, only knowing whether there are any.
 */
internal fun PoolStore.count(): Int = load().size()
