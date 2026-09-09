package xyz.jmc.gozar.engines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.torproject.jni.TorService
import kotlin.coroutines.resume

/**
 * Runs Tor, pointed at whichever pluggable transport won the race.
 *
 * A transport on its own is not a tunnel. Tor is what drives it, hands it the
 * bridge line through SOCKS auth, and exposes the SOCKS port everything else
 * actually uses. So this is the piece that turns four transports into four
 * ways out.
 */
class TorAndroidDriver(
    private val context: Context,
    private val socksPort: Int = 9250,
) : TorDriver {

    @Volatile private var running = false

    override suspend fun start(transport: String, ptPort: Int, bridges: List<String>): Int =
        withContext(Dispatchers.IO) {
            require(bridges.isNotEmpty()) {
                "$transport has no bridge lines, so Tor has nothing to dial"
            }

            writeTorrc(transport, ptPort, bridges)

            val came = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) {
                awaitStatusOn {
                    context.startService(Intent(context, TorService::class.java))
                    running = true
                }
            }

            check(came == true) { "tor did not come up over $transport" }
            socksPort
        }

    override fun stop() {
        if (!running) return
        running = false
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
    }

    /**
     * TorService reads this file on start. Everything Gozar decides — which
     * transport, which port it is listening on, which bridges to dial — is
     * expressed here and nowhere else.
     */
    private fun writeTorrc(transport: String, ptPort: Int, bridges: List<String>) {
        val lines = buildList {
            add("SocksPort $socksPort")
            add("UseBridges 1")
            // "exec" is absent on purpose: the transport is already running as
            // a process of ours, so Tor is told to talk to a socket rather
            // than to launch anything.
            add("ClientTransportPlugin $transport socks5 127.0.0.1:$ptPort")
            bridges.forEach { add("Bridge $it") }
        }

        runCatching {
            TorService.getTorrc(context).writeText(lines.joinToString("\n") + "\n")
        }
    }

    private suspend fun awaitStatusOn(startIt: () -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            val manager = LocalBroadcastManager.getInstance(context)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                        TorService.STATUS_ON -> {
                            manager.unregisterReceiver(this)
                            if (cont.isActive) cont.resume(true)
                        }
                        TorService.STATUS_OFF -> {
                            manager.unregisterReceiver(this)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
            }

            manager.registerReceiver(receiver, IntentFilter(TorService.ACTION_STATUS))
            cont.invokeOnCancellation { runCatching { manager.unregisterReceiver(receiver) } }
            startIt()
        }

    private companion object {
        /**
         * Generous on purpose. Snowflake has to find a volunteer through a
         * broker before Tor can even begin, and on a bad night that is slow
         * without being broken.
         */
        const val BOOTSTRAP_TIMEOUT_MS = 90_000L
    }
}
