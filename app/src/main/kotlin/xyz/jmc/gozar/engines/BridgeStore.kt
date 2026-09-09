package xyz.jmc.gozar.engines

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Holds the bridge lines each transport needs.
 *
 * Two sources, merged. Lines shipped in assets are the ones that are public
 * constants and safe to bake in — Snowflake's, mostly, since it reaches a
 * broker rather than a fixed server. Lines in app storage were fetched at
 * runtime through a tunnel that was already up, which is the only way to get
 * obfs4 and webtunnel bridges in a place where every bridge distributor is
 * itself blocked.
 */
class BridgeStore(private val context: Context) {

    private val fetchedFile: File
        get() = File(context.filesDir, "bridges.json")

    fun linesFor(transport: String): List<String> =
        (bundled()[transport].orEmpty() + fetched()[transport].orEmpty()).distinct()

    /** Stores lines pulled down through a working tunnel. */
    fun saveFetched(byTransport: Map<String, List<String>>) {
        val root = JSONObject()
        for ((transport, lines) in byTransport) root.put(transport, lines)
        runCatching { fetchedFile.writeText(root.toString()) }
    }

    fun hasLinesFor(transport: String) = linesFor(transport).isNotEmpty()

    private fun bundled(): Map<String, List<String>> =
        parse(runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull())

    private fun fetched(): Map<String, List<String>> =
        parse(runCatching { fetchedFile.readText() }.getOrNull())

    private fun parse(text: String?): Map<String, List<String>> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()

        return buildMap {
            for (key in root.keys()) {
                if (key.startsWith("_")) continue // notes, not data
                val arr = root.optJSONArray(key) ?: continue
                val lines = buildList {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                if (lines.isNotEmpty()) put(key, lines)
            }
        }
    }

    private companion object {
        const val ASSET = "bridges.json"
    }
}
