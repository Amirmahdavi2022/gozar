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

/**
 * One pool per network, because a score earned on one is not evidence about another.
 *
 * <p>🚨 This is the answer to "it behaves completely differently when I change operator". The
 * endpoints were scored in a single shared file with no idea which network the evidence came
 * from, so switching from wifi to a carrier meant dialling, in order, the servers that had proved
 * themselves somewhere else entirely — and every one of them that the new network happened to
 * block was written down as a failure, poisoning the ranking for the network where it worked
 * perfectly. Two networks were filling in the same scoreboard with contradictory answers, and
 * whichever had been used most recently won.
 *
 * <p>The LIST of endpoints is still shared, and that part matters: it is expensive to fetch,
 * comes from sources that are themselves blocked, and a server existing has nothing to do with
 * which network you are on. So a network seeing the app for the first time inherits every
 * endpoint already known and none of the scores, and starts learning its own.
 */
internal class FilePoolStore(
    private val directory: File,
    private val network: () -> String,
    private val seed: () -> String? = { null },
) : PoolStore {

    private val file: File get() = File(directory, "pool-${slug(network())}.txt")

    /** Where every network's pool lived before they were split apart. Still read, never written. */
    private val LEGACY = "pool.txt"

    /** Whatever the network calls itself, reduced to something safe to put in a filename. */
    private fun slug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unknown" }

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

        // A network being new is not a reason to start from a list that shipped weeks ago. What
        // another network has already fetched is the better starting point by a long way; only
        // its opinions are left behind.
        val inherited = inherit()
        if (inherited != null) {
            save(inherited)
            return inherited
        }

        val planted = plant() ?: return saved ?: EndpointPool()
        save(planted)
        return planted
    }

    /**
     * The largest pool already on this device, with every score stripped off it.
     *
     * 🚨 `pool.txt` is in this list, and leaving it out was the worst regression this app has
     * shipped. Before the pool was split per network there was exactly one file with that name,
     * holding weeks of this device's own evidence about which endpoints actually work here. The
     * split looked for `pool-*` only, found nothing, and fell through to the list baked into the
     * apk at build time — so every existing install silently threw away everything it had learned
     * and started again from a seed that was already stale on the day it was built. On a device
     * that had been connecting in three seconds, the first four rounds after the update found
     * nothing at all and the first press failed outright.
     *
     * The lesson worth keeping: changing where state lives is a migration, not a rename. A
     * lookup that quietly finds nothing is indistinguishable from a fresh install, and a fresh
     * install is the worst state this app can be in.
     */
    private fun inherit(): EndpointPool? {
        val mine = file.name
        val others = runCatching {
            directory.listFiles { candidate ->
                candidate.isFile && candidate.name != mine &&
                    (candidate.name.startsWith("pool-") || candidate.name == LEGACY)
            }
        }.getOrNull().orEmpty()
        if (others.isEmpty()) return null

        val richest = others
            .mapNotNull { runCatching { EndpointPool.deserialise(it.readText()) }.getOrNull() }
            .maxByOrNull { it.size() }
            ?: return null
        if (richest.size() == 0) return null

        // Re-read through the same parser the fetched lists go through, rather than copied entry
        // by entry. A stored line is the original URI followed by its counters, so taking the URI
        // alone drops the other network's opinions by construction instead of by remembering to
        // zero five fields.
        val uris = richest.serialise()
            .lineSequence()
            .map { it.substringBefore('\t') }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val configs = runCatching { ProxyConfig.parseDocument(uris) }.getOrNull()
        if (configs.isNullOrEmpty()) return null

        val fresh = EndpointPool()
        fresh.merge(Dialable.filter(configs))
        return if (fresh.size() > 0) fresh else null
    }

    private fun plant(): EndpointPool? {
        val document = runCatching { seed() }.getOrNull()
        if (document.isNullOrBlank()) return null

        val parsed = runCatching { Dialable.filter(ProxyConfig.parseDocument(document)) }
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
