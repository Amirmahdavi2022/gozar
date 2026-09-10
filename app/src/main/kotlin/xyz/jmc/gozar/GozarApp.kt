package xyz.jmc.gozar

import android.app.Application
import xyz.jmc.gozar.core.Diary

/**
 * Runs before anything else in every process this app has.
 *
 * Its whole job is to make sure that if the app dies, it leaves a note. An uncaught exception on
 * any thread otherwise takes the process down with nothing written anywhere the user can reach —
 * from the outside that is an app that vanishes when a button is pressed and a log that says
 * nothing happened, which is the least useful pair of facts possible.
 */
class GozarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diary.attach(filesDir)

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Diary.crash(thread.name, error) }
            // Handed on rather than swallowed. Swallowing it would leave a half-dead process that
            // looks alive and behaves like nothing works, which is worse than closing.
            existing?.uncaughtException(thread, error)
        }
    }
}
