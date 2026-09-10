package xyz.jmc.gozar

import android.content.Context

/**
 * The one config the user brought, if they brought one.
 *
 * Most people will never touch this. The ones who do already have a link that
 * works and want it raced alongside the built in routes rather than replaced
 * by them, so it is stored as typed and validated no further than it has to be.
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("gozar", Context.MODE_PRIVATE)

    var link: String?
        get() = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY) else putString(KEY, value.trim())
            }.apply()
        }

    /** Enough of a check to catch a paste that went wrong, and no more. */
    fun looksUsable(candidate: String): Boolean {
        val text = candidate.trim()
        return SCHEMES.any { text.startsWith(it, ignoreCase = true) } && text.length > 16
    }

    private companion object {
        const val KEY = "user_config"
        val SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://")
    }
}
