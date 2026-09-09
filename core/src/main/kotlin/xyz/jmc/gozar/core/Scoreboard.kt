package xyz.jmc.gozar.core

/**
 * Which network the phone is on right now.
 *
 * Deliberately coarse: no SSID, no IP, nothing worth finding on a phone that
 * ended up in the wrong hands. "mobile:MCI" and "wifi" is as specific as it
 * gets, and that is enough — what works on a carrier is rarely what works on
 * home wifi, and that is the only distinction the scoreboard needs.
 */
@JvmInline
value class NetworkId(val value: String) {
    companion object {
        val UNKNOWN = NetworkId("unknown")
        fun wifi() = NetworkId("wifi")
        fun mobile(carrier: String?) = NetworkId("mobile:${carrier.orEmpty().ifEmpty { "?" }}")
    }
}

data class EngineRecord(
    val wins: Int = 0,
    val losses: Int = 0,
    /** Moving average of how long it took to come up and pass a probe. */
    val avgMs: Long = 0,
    val lastGoodAt: Long = 0,
)

/** Somewhere to keep the scoreboard between launches. Android hands in a file. */
interface ScoreStore {
    fun load(): Map<String, Map<String, EngineRecord>>
    fun save(data: Map<String, Map<String, EngineRecord>>, bootstrapped: Set<String>)
    fun loadBootstrapped(): Set<String>
}

/** A store that forgets everything. Useful for tests and first runs. */
class MemoryStore : ScoreStore {
    private var data: Map<String, Map<String, EngineRecord>> = emptyMap()
    private var boot: Set<String> = emptySet()
    override fun load() = data
    override fun loadBootstrapped() = boot
    override fun save(data: Map<String, Map<String, EngineRecord>>, bootstrapped: Set<String>) {
        this.data = data
        this.boot = bootstrapped
    }
}

/**
 * Remembers what worked where.
 *
 * This is what makes the second launch fast. The first one is a real race; every
 * one after it starts with whatever won here last time and usually finishes
 * before the second engine has even been started.
 */
class Scoreboard(
    private val store: ScoreStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val nets: MutableMap<String, MutableMap<String, EngineRecord>> =
        store.load().mapValues { it.value.toMutableMap() }.toMutableMap()
    private val bootstrapped: MutableSet<String> = store.loadBootstrapped().toMutableSet()

    @Synchronized
    fun record(network: NetworkId, engine: String, ok: Boolean, tookMs: Long) {
        val byEngine = nets.getOrPut(network.value) { mutableMapOf() }
        val current = byEngine[engine] ?: EngineRecord()

        byEngine[engine] = if (ok) {
            current.copy(
                wins = current.wins + 1,
                lastGoodAt = clock(),
                // Weighted toward history, so one bad night does not bury the
                // engine that is usually fastest here.
                avgMs = if (current.avgMs == 0L) tookMs else (current.avgMs * 3 + tookMs) / 4,
            )
        } else {
            current.copy(losses = current.losses + 1)
        }
        persist()
    }

    /**
     * Decides who goes off the line first.
     *
     * Anything that worked here recently leads, fastest first. Engines with no
     * history here come next, because an unknown is worth more than a known
     * failure. What has been failing here goes last — but it still goes, because
     * networks change and yesterday's dead engine is sometimes today's only one.
     */
    @Synchronized
    fun order(network: NetworkId, engines: List<Engine>): List<Engine> {
        val scores = nets[network.value].orEmpty()
        val fresh = clock() - Racer.RECENT_WIN_MS

        return engines.sortedWith(
            compareBy(
                { engine ->
                    val rec = scores[engine.name]
                    when {
                        rec != null && rec.lastGoodAt > fresh -> 0
                        rec == null -> 1
                        else -> 2
                    }
                },
                { engine -> scores[engine.name]?.avgMs ?: Long.MAX_VALUE },
            )
        )
    }

    @Synchronized
    fun isBootstrapped(engine: String) = engine in bootstrapped

    @Synchronized
    fun markBootstrapped(engine: String) {
        if (bootstrapped.add(engine)) persist()
    }

    private fun persist() = store.save(nets.mapValues { it.value.toMap() }, bootstrapped.toSet())
}
