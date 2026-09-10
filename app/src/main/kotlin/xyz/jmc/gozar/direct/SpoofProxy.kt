package xyz.jmc.gozar.direct

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The local proxy the shaped dial route goes through.
 *
 * It listens on loopback and does one thing: tears up the opening packets of each connection it
 * forwards, so equipment that reads the first packet to decide whether to allow a connection has
 * nothing whole to read. Same protocol, same certificate, same server — an endpoint that would
 * have worked still works.
 *
 * A separate program rather than a setting on the core, because the core can only do what it
 * implements, and it has no equivalent of sending a decoy the inspector sees and the server never
 * does, or of reordering pieces so that reassembly in arrival order produces nonsense.
 *
 * Shipped as libbyedpi.so even though it is a program: since Android 10 an app may not execute a
 * file it wrote into its own data directory, and the installer's native library directory is the
 * one place a shipped binary lives with the execute bit already set.
 */
internal class SpoofProxy(context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")
    private var process: Process? = null

    /**
     * Whether this route can be offered at all.
     *
     * Checked before the mode goes into a plan rather than discovered by a failed dial. A dial
     * that fails because a binary is missing would be recorded as evidence about the endpoint,
     * and the pool would slowly fill with healthy servers marked dead.
     */
    fun available(): Boolean = binary.isFile && binary.canExecute()

    fun start(): Boolean {
        stop()
        if (!available()) return false

        val command = listOf(
            binary.absolutePath, "-i", "127.0.0.1", "-p", PORT.toString(),
        ) + SHAPING

        process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return false

        // Waiting for the port rather than sleeping a fixed interval: a fixed sleep is either
        // longer than it needs to be on every connect or too short on a loaded phone, and the
        // second failure looks exactly like an endpoint that did not answer.
        return waitForPort()
    }

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            running.waitFor()
        }
    }

    private fun waitForPort(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process == null) return false
            Socket().use { probe ->
                runCatching {
                    probe.connect(InetSocketAddress("127.0.0.1", PORT), 200)
                    return true
                }
            }
            runCatching { Thread.sleep(50) }.onFailure { return false }
        }
        stop()
        return false
    }

    companion object {
        /**
         * Loopback port. Deliberately not one the rest of the app uses, so a stale process from a
         * previous run cannot be mistaken for this one.
         */
        const val PORT = 18443

        const val WAIT_MS = 2_500L

        fun address(): String = "127.0.0.1:$PORT"

        /**
         * One strategy rather than a menu, chosen because it defeats the common case and cannot
         * be undone by reassembly. Splitting alone falls to equipment that reassembles before
         * matching, which is now ordinary. Out of order means reassembling in arrival order
         * produces something that does not parse, while the server's own stack — which reorders
         * by sequence number, as TCP requires — sees the correct hello.
         *
         * 🚨 Out-of-band strategies are deliberately absent. Measured against a destination that
         * was never filtered at all, both of them reset the connection outright. A strategy that
         * breaks ordinary traffic is worse than none, because every endpoint it touches looks
         * dead and gets written into the pool as a failure.
         */
        private val SHAPING = listOf("--disorder", "1")
    }
}
