package xyz.jmc.gozar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import xyz.jmc.gozar.core.NetworkId

/**
 * Works out which network the phone is on, coarsely.
 *
 * Coarse is the point. What works on a carrier is rarely what works on home
 * wifi, and that is the only distinction the scoreboard needs — so no SSID and
 * no address ever gets written down.
 */
class NetworkWatcher(private val context: Context) {

    fun current(): NetworkId {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkId.UNKNOWN
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkId.UNKNOWN

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkId.wifi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkId.mobile(carrier())
            else -> NetworkId.UNKNOWN
        }
    }

    private fun carrier(): String? =
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.networkOperator
            ?.takeIf { it.isNotBlank() }
}
