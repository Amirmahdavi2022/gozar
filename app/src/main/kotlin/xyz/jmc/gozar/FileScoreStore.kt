package xyz.jmc.gozar

import org.json.JSONObject
import xyz.jmc.gozar.core.EngineRecord
import xyz.jmc.gozar.core.ScoreStore
import java.io.File

/**
 * Keeps the scoreboard in a small json file in the app's private storage.
 *
 * Hand rolled rather than pulled from a serialization library: it is four
 * fields, and the file should stay readable by a person debugging on a phone.
 */
class FileScoreStore(private val file: File) : ScoreStore {

    override fun load(): Map<String, Map<String, EngineRecord>> {
        val nets = read()?.optJSONObject("nets") ?: return emptyMap()
        return buildMap {
            for (network in nets.keys()) {
                val byEngine = nets.optJSONObject(network) ?: continue
                val engines = buildMap<String, EngineRecord> {
                    for (engine in byEngine.keys()) {
                        val o = byEngine.optJSONObject(engine) ?: continue
                        put(
                            engine,
                            EngineRecord(
                                wins = o.optInt("wins"),
                                losses = o.optInt("losses"),
                                avgMs = o.optLong("avgMs"),
                                lastGoodAt = o.optLong("lastGoodAt"),
                            ),
                        )
                    }
                }
                put(network, engines)
            }
        }
    }

    override fun loadBootstrapped(): Set<String> {
        val arr = read()?.optJSONArray("bootstrapped") ?: return emptySet()
        return buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }

    override fun save(data: Map<String, Map<String, EngineRecord>>, bootstrapped: Set<String>) {
        val nets = JSONObject()
        for ((network, engines) in data) {
            val byEngine = JSONObject()
            for ((engine, rec) in engines) {
                byEngine.put(
                    engine,
                    JSONObject()
                        .put("wins", rec.wins)
                        .put("losses", rec.losses)
                        .put("avgMs", rec.avgMs)
                        .put("lastGoodAt", rec.lastGoodAt),
                )
            }
            nets.put(network, byEngine)
        }

        val root = JSONObject().put("nets", nets).put("bootstrapped", bootstrapped.toList())
        runCatching { file.writeText(root.toString()) }
    }

    private fun read(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()
}
