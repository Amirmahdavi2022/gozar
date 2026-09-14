package xyz.jmc.gozar

import android.content.Context
import xyz.jmc.gozar.core.Engine

/**
 * Which ways out are allowed to race.
 *
 * 🚨 Written because a path became untestable. The race hands the tunnel to the first thing that
 * proves itself and stands the rest down unstarted, which is right for a user and useless for
 * anyone trying to find out whether a particular path works: on a network where the fast path
 * comes up in six seconds, the slow one is never started even once, so a change to it can be
 * shipped, run for days, and never execute. A device log showed exactly that — three minutes of
 * traffic and not one line about the path whose behaviour was the entire question.
 *
 * So this is a diagnostic lever first and a preference second. Turning the others off forces the
 * remaining one to be the path that carries the traffic, which is the only way to see what it
 * actually does on a real network.
 *
 * 🔑 The last one can never be turned off. An empty list is not a quieter app, it is an app with
 * no way out at all and a connect button that fails without explaining itself — and the person
 * most likely to switch everything off is the one experimenting, who is also the one least likely
 * to connect the black screen to a toggle they moved a week ago.
 */
class PathChoices(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True if this engine is allowed to race. Unknown engines default to allowed. */
    fun allows(name: String): Boolean = !prefs.getStringSet(OFF, emptySet()).orEmpty().contains(name)

    /**
     * Turns a path on or off, refusing to turn off the last one.
     *
     * @return true if the change was made.
     */
    fun set(name: String, allowed: Boolean, all: List<String>): Boolean {
        val off = prefs.getStringSet(OFF, emptySet()).orEmpty().toMutableSet()
        if (allowed) {
            off.remove(name)
        } else {
            if (all.count { !off.contains(it) && it != name } == 0) return false
            off.add(name)
        }
        prefs.edit().putStringSet(OFF, off).apply()
        return true
    }

    /**
     * Filters an engine list down to the allowed ones.
     *
     * Falls back to the whole list rather than an empty one. [set] already refuses to empty it,
     * but this is read on the connect path, where the cost of being wrong is a dead button, and
     * the stored set outlives the code that wrote it — a renamed engine would otherwise leave
     * every name in the off-set matching nothing and every name in the list matching the off-set.
     */
    fun filter(engines: List<Engine>): List<Engine> =
        engines.filter { allows(it.name) }.ifEmpty { engines }

    private companion object {
        const val FILE = "paths"
        const val OFF = "off"
    }
}
