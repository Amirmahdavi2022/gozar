package xyz.jmc.gozar.engines

import android.util.Base64
import org.json.JSONObject
import xyz.jmc.gozar.core.X25519
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Gets the edge program an account before it starts, so that it never has to ask for one itself.
 *
 * 🚨 The defect this fixes, because it is not guessable from the code. A Go program on Android has
 * no `/etc/resolv.conf` — Android does not ship one — so Go's resolver falls back to 127.0.0.1:53
 * and [::1]:53, where nothing is listening, and every hostname lookup fails immediately. The same
 * device log that prompted this shows another Go program in the app hitting it in plain sight:
 * `lookup … on [::1]:53`.
 *
 * The edge program's first act is to register an account over HTTPS, which needs a hostname. So it
 * died in well under a second, three times over, and the app faithfully reported that nothing
 * answered — when nothing had actually been asked. Three rungs of a ladder built to survive a
 * hostile network, defeated by a missing file.
 *
 * 🔑 None of that applies up here. This runs on Android's own resolver and TLS stack, the same ones
 * that already reach the probe hosts successfully in that very log. It registers once, writes the
 * result where the program looks for it, and from then on the program finds an account already
 * waiting and skips the call that was killing it.
 *
 * The file lands at `<cache>/primary/wgcf-identity.json` because that is precisely where the
 * program looks; the name is not ours to choose.
 */
class EdgeAccount(
    private val cache: File,
    private val log: (String) -> Unit = {},
) {

    /**
     * Makes sure an account exists on disk.
     *
     * @return true if there is one, whether it was already there or made just now
     */
    fun ensure(): Boolean {
        val file = File(File(cache, "primary"), IDENTITY)
        if (file.isFile && file.length() > 0) return true

        val registered = runCatching { register() }.getOrElse { failure ->
            // Deliberately only the kind of failure, never the text. Error strings from this call
            // can carry the address that was being reached, and a pasted log should not say where
            // this app goes.
            log("$LABEL could not set up an account (${failure.javaClass.simpleName})")
            null
        } ?: return false

        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(registered)
            true
        }.getOrElse { false }
    }

    /** Throws away the account, so the next [ensure] makes a fresh one. */
    fun forget() {
        runCatching { File(cache, "primary").deleteRecursively() }
    }

    private fun register(): String {
        val privateKey = X25519.clamp(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val publicKey = X25519.publicKey(privateKey)

        val body = JSONObject()
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", timestamp())
            .put("key", encode(publicKey))
            .put("type", "Android")
            .put("model", "PC")
            .put("locale", "en_US")
            .put("warp_enabled", true)
            .toString()

        val connection = (URL(REGISTER).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            // Matched to what the program itself sends. An account registered under a different
            // client string is not obviously wrong, but it is needlessly distinguishable, and
            // being distinguishable is the one thing this whole app is trying not to be.
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            setRequestProperty("CF-Client-Version", "a-6.30-3596")
        }

        val answer = try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode in 200..299) { "registration refused" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        // 🔑 The private key never leaves this device and so is not in the reply. The program
        // expects to find it in the same object, under this name, which is why the reply is
        // extended rather than rebuilt — anything the far side sent that we don't know about is
        // kept exactly as it arrived.
        return JSONObject(answer).put("private_key", encode(privateKey)).toString()
    }

    private fun encode(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(System.currentTimeMillis())

    private companion object {
        const val IDENTITY = "wgcf-identity.json"
        const val REGISTER = "https://api.cloudflareclient.com/v0a4005/reg"
        const val TIMEOUT_MS = 12_000
        const val LABEL = "path 4"
    }
}
