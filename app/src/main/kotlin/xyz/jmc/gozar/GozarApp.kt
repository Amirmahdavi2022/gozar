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

        // 🚨 Registered here, and the reason is the bug that hid the last two crashes from us.
        //
        // This used to be registered by the object that starts the native tunnel — which meant it
        // was only ever registered in a process that had not crashed yet. After the crash the
        // process is new, that object was never built, nothing was registered, and the log was
        // read as absent. The file was very likely sitting on disk full of exactly what we needed,
        // twice, and we were not looking at it.
        //
        // A record of a crash has to be wired up by something that runs before the crash can
        // happen and again after it, every time. That is startup, and nowhere else.
        Diary.include("the native tunnel", java.io.File(filesDir, "tunnel.log"))

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Diary.crash(thread.name, error) }
            // Handed on rather than swallowed. Swallowing it would leave a half-dead process that
            // looks alive and behaves like nothing works, which is worse than closing.
            existing?.uncaughtException(thread, error)
        }
    }
}
