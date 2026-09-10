package xyz.jmc.gozar.core

/**
 * The last few hundred lines of what the tunnel actually did.
 *
 * This exists because of a lesson learned the expensive way on another app:
 * every question about why a connection failed can only be answered by the one
 * device that was there, and if that device has no way to say what it saw, the
 * answer is guesswork and the next build is a guess too.
 *
 * Nothing here is sent anywhere. It is a buffer in memory that a person can
 * copy out of Settings and paste somewhere themselves.
 */
object Diary {

    private const val KEEP = 300

    private val lines = ArrayDeque<String>()
    private var startedAt = 0L

    @Synchronized
    fun write(line: String) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        lines.addLast("[%3ds] %s".format(seconds, line))
        while (lines.size > KEEP) lines.removeFirst()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        startedAt = 0L
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "nothing yet" else lines.joinToString("\n")
}
