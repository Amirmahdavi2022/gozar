package xyz.jmc.gozar.core

/**
 * Turns the quic core's per-connection complaints into one counted line.
 *
 * 🚨 Why this exists, from a diary that could not be read. The core narrates every socket that
 * does not work out, and on a public endpoint that is dozens a minute: a mail client retrying,
 * a browser probing a captive portal, an app asking for a host the endpoint will not dial. None
 * of them say anything about the tunnel — it was carrying traffic the whole time — but at one
 * line each they buried every line that did matter. Two device logs came back where the four
 * interesting lines were separated by forty that were not.
 *
 * So the routine ones are counted rather than printed, and a tally goes out at most once a
 * minute. The information is not lost, it is aggregated: forty timeouts and two refusals read
 * as a network being unhelpful, which is exactly what forty separate lines meant and could not
 * convey.
 *
 * 🔑 What is NOT swallowed: anything that is not a per-connection failure. A core that dies, a
 * listener that will not bind, a config it will not accept — those are printed in full, because
 * they are about the engine rather than about one socket. The test for routine is the core's own
 * wording for a single proxied connection, not the presence of the word "error".
 */
class Noise(
    /** How often a tally may be printed. */
    private val windowMs: Long = WINDOW_MS,
    /** A tally goes out early once this many have piled up, so a busy minute is not hidden. */
    private val burst: Int = BURST,
) {

    private val counts = LinkedHashMap<String, Int>()
    private var since = 0L
    private var total = 0

    /**
     * @return the line to print, or null when this one was counted instead
     */
    fun consume(line: String, nowMs: Long): String? {
        if (!routine(line)) return clean(line)

        if (total == 0) since = nowMs
        total++
        val kind = kind(line)
        counts[kind] = (counts[kind] ?: 0) + 1

        if (total >= burst || nowMs - since >= windowMs) return tally(nowMs)
        return null
    }

    /** Whatever is still counted, for the moment the engine stops. Null when there is nothing. */
    fun flush(nowMs: Long): String? = if (total == 0) null else tally(nowMs)

    private fun tally(nowMs: Long): String {
        val seconds = ((nowMs - since).coerceAtLeast(0) / 1000).coerceAtLeast(1)
        val detail = counts.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val summary = "path 3: $total connections gave up in ${seconds}s ($detail)"
        counts.clear()
        total = 0
        since = nowMs
        return summary
    }

    /**
     * One proxied connection that did not work out, in the core's own wording.
     *
     * Matched on the shape of the message rather than on a severity level, because the core logs
     * these at the same level as things that matter.
     */
    private fun routine(line: String): Boolean =
        line.contains("SOCKS5 TCP error") || line.contains("SOCKS5 UDP error")

    private fun kind(line: String): String = when {
        line.contains("timeout") -> "timed out"
        line.contains("rejected") -> "refused by the endpoint"
        line.contains("connection reset") -> "reset"
        line.contains("no IPv4 address") -> "no address for the host"
        line.contains("authentication error") -> "turned away by the endpoint"
        else -> "other"
    }

    /**
     * The core's own timestamp, level and terminal colouring stripped off.
     *
     * Every line already goes into a diary that stamps its own time, and the escape codes render
     * as visible rubbish once the text is pasted anywhere. What is left is the sentence.
     */
    private fun clean(line: String): String {
        val stripped = line.replace(ANSI, "").replace('\t', ' ')
        val body = stripped.substringAfter("Z ", stripped).trim()
        return "quic core: " + body.take(200)
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val BURST = 40
        val ANSI = Regex("\u001B\\[[0-9;]*m")
    }
}
