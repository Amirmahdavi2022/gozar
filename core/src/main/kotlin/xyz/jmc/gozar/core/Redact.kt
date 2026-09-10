package xyz.jmc.gozar.core

/**
 * Takes the addresses out of a log line before it is written anywhere.
 *
 * 🚨 The reason is not privacy in the usual sense — nothing here is ever uploaded. It is that a
 * log names the servers this app is dialling, and those servers are public, free, and shared with
 * everyone else using the same lists. One person pasting a log into a group chat is one more place
 * a blocklist can be built from, and the servers that get named die first. So a log that is meant
 * to be pasted has to be safe to paste.
 *
 * What survives is everything that makes a log worth reading: what happened, in what order, how
 * long it took, and what the error was. What goes is what a host or an address it happened to.
 *
 * The allowlist is deliberately short and deliberately boring: our own probe targets, the two
 * public resolvers, loopback, and the package's own name so file paths stay readable. All of them
 * are hammered by every phone on earth and say nothing about where this app gets out.
 */
object Redact {

    private const val HOST = "<host>"
    private const val ADDRESS = "x.x.x.x"
    private const val IDENTIFIER = "<id>"

    private val ALLOWED = setOf(
        "www.gstatic.com",
        "detectportal.firefox.com",
        "cp.cloudflare.com",
        "raw.githubusercontent.com",
        "xyz.jmc.gozar",
        "127.0.0.1",
        "1.1.1.1",
        "8.8.8.8",
    )

    /**
     * A name with at least one dot whose last label is letters.
     *
     * The letters requirement is what keeps timestamps and version numbers intact: 16:48:17.463753
     * and Xray 26.3.27 both end in digits, so neither is mistaken for a host.
     */
    private val HOSTNAME = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.[A-Za-z]{2,24}")

    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    /** Xray gives each internal session one of these. Harmless, and noise in a pasted log. */
    private val UUID = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")

    /**
     * An endpoint URI, which carries the credential as well as the address, so it is removed whole
     * rather than picked at.
     */
    private val URI = Regex("\\b(?:vless|trojan|ss|hysteria2|hy2|tuic)://\\S+", RegexOption.IGNORE_CASE)

    fun line(text: String): String {
        var out = URI.replace(text) { it.value.substringBefore("://") + "://" + HOST }
        out = UUID.replace(out, IDENTIFIER)
        // Addresses first: an IPv4 has no letters in its last label, so the hostname pass would
        // leave it alone and it would reach the log untouched.
        out = IPV4.replace(out) { if (it.value in ALLOWED) it.value else ADDRESS }
        out = HOSTNAME.replace(out) { if (it.value.lowercase() in ALLOWED) it.value else HOST }
        return out
    }

    /** For the native tunnel's log, which arrives as one block rather than a line at a time. */
    fun block(text: String): String = text.lineSequence().joinToString("\n") { line(it) }
}
