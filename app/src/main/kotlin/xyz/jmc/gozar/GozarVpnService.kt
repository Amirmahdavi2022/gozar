package xyz.jmc.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * Holds the tun device.
 *
 * Routing packets from this tun into the winning engine's SOCKS port is the
 * next piece and is not written yet. It has to be a native tun2socks rather
 * than a Go one: IPtProxy is a gomobile library and an Android app can only
 * carry one of those, so a second Go module would refuse to link.
 */
class GozarVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())

        tun = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.7.0.1", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            // Never route our own traffic through ourselves. Without this the
            // transports would try to reach their broker through the tunnel
            // they are supposed to be building.
            .also { builder ->
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            .establish()

        return START_STICKY
    }

    private fun teardown() {
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
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
            .setContentText("Connected")
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
