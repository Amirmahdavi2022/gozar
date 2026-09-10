package xyz.jmc.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.jmc.gozar.core.Diary

/**
 * Owns the tun device and hands it to the tunnel once a way out is found.
 */
class GozarVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }

        // A new session starts here rather than deeper in, so that a failure while the tun is
        // being built lands in this session's log instead of the one that is about to be rotated
        // away as history.
        Diary.clear()

        // Everything from here to the tun being open runs on the main thread inside a system
        // callback, so anything that throws takes the process down instantly — which is what the
        // user sees as the app closing the moment they press connect, with no log, because the
        // log went down with the process. None of it is allowed to throw uncaught any more:
        // a tunnel that fails to start is a message, not a crash.
        val started = runCatching { startForeground(NOTIFICATION_ID, notification()) }
        if (started.isFailure) {
            Diary.write("android would not let the tunnel run in the foreground: " +
                started.exceptionOrNull()?.message)
            stopSelf()
            return START_NOT_STICKY
        }

        val fd = establish()
        if (fd == null) {
            teardown()
            return START_NOT_STICKY
        }

        scope.launch {
            val up = try {
                Tunnel.bringUp(this@GozarVpnService, fd)
            } catch (e: Throwable) {
                Diary.crash("tunnel", e)
                false
            }
            if (!up) teardown()
        }

        return START_STICKY
    }

    /**
     * @return the tun's descriptor, or null if the device would not give us one
     */
    private fun establish(): Int? = try {
        buildTun()
    } catch (e: Throwable) {
        // A rejected route, a withdrawn permission, a builder the system does not like: all of
        // them arrive here as an exception and all of them used to be fatal.
        Diary.crash("tun", e)
        null
    }

    private fun buildTun(): Int? {
        val descriptor = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(Tun2Socks.MTU)
            .addAddress("10.7.0.1", 32)
            // Not a public resolver: the tunnel answers DNS itself, at the
            // address hev's mapdns listens on. Pointing this at 1.1.1.1 sends
            // every lookup out as UDP, which no SOCKS proxy we tunnel through
            // will carry, and the result is a connection where nothing loads.
            .addDnsServer(Tun2Socks.DNS_ADDRESS)
            .addRoute("0.0.0.0", 0)
            // Redundant under a default route, and kept anyway. The mapped
            // range is what mapdns hands back for a name, so if it is ever not
            // routed into the tun every lookup succeeds and every connection
            // fails — the quietest bug this app could have.
            .addRoute(Tun2Socks.MAPPED_NETWORK, Tun2Socks.MAPPED_PREFIX)
            // Our own traffic must not go through our own tunnel. Without this
            // the transports would try to reach their broker through the thing
            // they are supposed to be building, and nothing would ever start.
            .also { builder -> runCatching { builder.addDisallowedApplication(packageName) } }
            .establish()

        tun = descriptor
        return descriptor?.fd
    }

    private fun teardown() {
        Tunnel.tearDown()
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        teardown()
        super.onRevoke()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Finding a way out")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "xyz.jmc.gozar.STOP"
        private const val CHANNEL_ID = "gozar.connection"
        private const val NOTIFICATION_ID = 1
    }
}
