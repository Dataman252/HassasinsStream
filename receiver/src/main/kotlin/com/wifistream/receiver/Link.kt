package com.wifistream.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/** The connection to the sharing phone: how fast it is, and where the phone is now. */
object Link {
    /** Result of the last speed test, in megabits per second. */
    var lastSpeedMbps by mutableStateOf<Double?>(null)

    // When the phone turns up at a new address (say, after switching to its hotspot), old links are redirected.
    private val moves = ConcurrentHashMap<String, String>()

    fun relocate(oldBase: String, newBase: String) {
        moves.keys.filter { moves[it] == oldBase }.forEach { moves[it] = newBase }
        moves[oldBase] = newBase
    }

    /** [url] pointed at the phone's current address. */
    fun map(url: String): String {
        for ((old, new) in moves) if (url.startsWith(old)) return new + url.removePrefix(old)
        return url
    }

    /** "2.4 GHz", "5 GHz" or "6 GHz" when on Wi-Fi; null otherwise. */
    fun wifiBand(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return null) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = if (Build.VERSION.SDK_INT >= 31) {
            caps.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        }
        val mhz = info?.frequency ?: return null
        return when {
            mhz in 2400..2500 -> "2.4 GHz"
            mhz in 4900..5900 -> "5 GHz"
            mhz > 5900 -> "6 GHz"
            else -> null
        }
    }

    /** What a measured speed is good for, in plain words. */
    fun verdict(mbps: Double): String = when {
        mbps >= 120 -> "Enough for everything, including the biggest 4K remuxes."
        mbps >= 60 -> "Enough for 4K, including most remuxes."
        mbps >= 30 -> "Fine for 1080p and 4K web files. Big 4K remuxes may stall."
        mbps >= 12 -> "Fine for 1080p. Too slow for 4K."
        else -> "Too slow for HD. Try the phone's hotspot, or a 5 GHz Wi-Fi network."
    }
}

/** "62 Mb/s". */
fun mbpsLabel(mbps: Double): String = if (mbps >= 10) "${mbps.toInt()} Mb/s" else "%.1f Mb/s".format(mbps)
