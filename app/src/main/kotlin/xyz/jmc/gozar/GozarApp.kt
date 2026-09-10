package xyz.jmc.gozar

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
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

        // 🚨 The one failure the handler below can never record: a crash in native code. There is
        // no exception, no thread to catch it on, and the process is simply gone — which is what
        // the user sees as the app vanishing with a log that stops mid-sentence. Android keeps
        // its own record of why a process died, tombstone and all, and it survives into the next
        // launch. Reading it here is the difference between knowing and guessing.
        runCatching { recordLastExit() }

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Diary.crash(thread.name, error) }
            // Handed on rather than swallowed. Swallowing it would leave a half-dead process that
            // looks alive and behaves like nothing works, which is worse than closing.
            existing?.uncaughtException(thread, error)
        }
    }

    /**
     * Folds Android's own account of how this app last died into the log, once, at startup.
     *
     * Only worth reporting when the process was killed rather than closed: an ordinary exit is
     * noise. A native crash carries a description that names the signal and the library, which is
     * exactly the sentence that has been missing.
     */
    private fun recordLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val manager = getSystemService(ActivityManager::class.java) ?: return
        val last = manager.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return

        val cause = when (last.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash in native code"
            ApplicationExitInfo.REASON_CRASH -> "an uncaught exception"
            ApplicationExitInfo.REASON_ANR -> "the app stopped responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "the phone running out of memory"
            ApplicationExitInfo.REASON_SIGNALED -> "a signal (${last.status})"
            // Everything else is the app being closed, swapped out or updated. Not worth a line.
            else -> return
        }

        Diary.write("the last run ended in $cause: ${last.description ?: "no description"}")

        // Only the native ones carry a trace, and only from Android 12.
        if (last.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val trace = runCatching {
            last.traceInputStream?.bufferedReader()?.use { reader ->
                reader.readLines().take(60).joinToString("\n")
            }
        }.getOrNull()
        if (!trace.isNullOrBlank()) Diary.write("what android recorded about it:\n" + trace)
    }
}
