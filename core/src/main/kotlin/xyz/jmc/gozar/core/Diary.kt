package xyz.jmc.gozar.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last few hundred lines of what the tunnel actually did, kept on disk as well as in memory.
 *
 * This exists because of a lesson learned the expensive way: every question about why a connection
 * failed can only be answered by the one device that was there, and if that device has no way to
 * say what it saw, the answer is guesswork and the next build is a guess too.
 *
 * 🚨 On disk is not belt-and-braces. A buffer in memory dies with the process, so the one failure
 * it can never explain is the process dying — which is exactly the failure that most needs
 * explaining, and which reads to the user as an empty log and a closed app. The previous session
 * survives here so the crash that ended it can be read afterwards.
 *
 * Nothing is sent anywhere. These are files in the app's own storage that a person can copy out of
 * Settings and paste somewhere themselves.
 */
object Diary {

    private const val KEEP = 300

    private val lines = ArrayDeque<String>()
    private var startedAt = 0L

    private var current: File? = null
    private var previous: File? = null

    /**
     * Logs written by native code we start.
     *
     * A crash inside a C library never reaches [crash] — there is no exception, no stack, and the
     * process is simply gone. What that library wrote about itself on its way down is then the
     * only account of what happened, so it is collected here rather than left in a file nobody
     * looks at.
     */
    private val included = LinkedHashMap<String, File>()

    /** Wired once at startup, before anything can fail. */
    @Synchronized
    fun attach(directory: File) {
        current = File(directory, "session.log")
        previous = File(directory, "previous.log")

        // Picked up rather than started empty. After a crash the process is new and this buffer is
        // not, but the file on disk still holds everything the dead process wrote — and [text]
        // prefers the buffer, so starting empty would hide the very session worth reading behind
        // whatever the new process happened to log first.
        runCatching {
            val existing = current?.takeIf { it.exists() }?.readLines().orEmpty()
            existing.filter { it.isNotBlank() }.takeLast(KEEP).forEach { lines.addLast(it) }
        }
    }

    @Synchronized
    fun include(label: String, file: File) {
        included[label] = file
    }

    @Synchronized
    fun write(line: String) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        // Redacted here rather than at the copy button, so an address is never written to disk in
        // the first place. A file that has to be sanitised before it is read is a file someone
        // will eventually read unsanitised.
        val entry = "[%3ds] %s".format(seconds, Redact.line(line))

        lines.addLast(entry)
        while (lines.size > KEEP) lines.removeFirst()

        runCatching { current?.appendText(entry + "\n") }
    }

    /**
     * Records a crash into the session that was running when it happened.
     *
     * Written straight through rather than buffered: the process has moments left.
     */
    @Synchronized
    fun crash(thread: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = "\n*** the app stopped here, on thread $thread ***\n$trace"
        lines.addLast(entry)
        runCatching { current?.appendText(entry + "\n") }
    }

    /**
     * Starts a new session and keeps the old one.
     *
     * Rotating rather than truncating, because the interesting session is usually the one that
     * just ended badly, and the app reaching this line means a new one is beginning.
     */
    @Synchronized
    fun clear() {
        lines.clear()
        startedAt = 0L
        runCatching {
            val now = current ?: return@runCatching
            val old = previous ?: return@runCatching
            if (now.exists() && now.length() > 0) {
                old.delete()
                if (!now.renameTo(old)) {
                    old.writeText(now.readText())
                }
            }
            now.delete()
        }
    }

    /**
     * Everything worth pasting: this session if there is one, and the session before it, which is
     * where a crash will be.
     */
    @Synchronized
    fun text(): String {
        val parts = mutableListOf<String>()

        val before = runCatching { previous?.takeIf { it.exists() }?.readText() }.getOrNull()
        if (!before.isNullOrBlank()) parts += "--- the session before this one ---\n" + before.trim()

        val now = if (lines.isNotEmpty()) {
            lines.joinToString("\n")
        } else {
            runCatching { current?.takeIf { it.exists() }?.readText() }.getOrNull()?.trim().orEmpty()
        }
        if (now.isNotBlank()) parts += "--- this session ---\n" + now

        for ((label, file) in included) {
            val body = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
            if (body.isNullOrBlank()) continue
            // Only the tail: these can run to thousands of lines, and the last words are the ones
            // that say how it ended.
            val tail = body.trim().lines().takeLast(80).joinToString("\n")
            parts += "--- $label ---\n" + Redact.block(tail)
        }

        return if (parts.isEmpty()) "nothing yet" else parts.joinToString("\n\n")
    }
}
