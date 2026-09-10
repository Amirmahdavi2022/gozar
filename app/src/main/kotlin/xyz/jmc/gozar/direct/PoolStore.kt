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

internal class FilePoolStore(private val file: File) : PoolStore {

    /**
     * Read fresh each time rather than cached. It is a few hundred lines, and the alternative is
     * two views of the pool that disagree after a background refresh has written to it.
     */
    override fun load(): EndpointPool =
        runCatching { EndpointPool.deserialise(file.readText()) }.getOrElse { EndpointPool() }

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
