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

        startForeground(NOTIFICATION_ID, notification())

        val fd = establish() ?: run {
            teardown()
            return START_NOT_STICKY
        }

        scope.launch {
            val up = Tunnel.bringUp(this@GozarVpnService, fd)
            if (!up) teardown()
        }

        return START_STICKY
    }

    private fun establish(): Int? {
        val descriptor = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.7.0.1", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
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
