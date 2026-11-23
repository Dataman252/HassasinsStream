package com.wifistream.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wifistream.protocol.WifiStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone's address on Wi-Fi or on its own hotspot. Interfaces are walked directly
 * because in hotspot mode the "active network" is mobile data, not the hotspot.
 */
fun localWifiAddress(): String? {
    val candidates = NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it } }
        .filter { (_, address) -> address.isSiteLocalAddress }
    val preferred = candidates.firstOrNull { (name, _) ->
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
    }
    return (preferred ?: candidates.firstOrNull())?.second?.hostAddress
}

/** Announces the sender on the local network so the receiver finds it without typing an IP. */
class Discovery(context: Context, private val onChange: (registered: Boolean) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = WifiStream.SERVICE_TYPE
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onChange(true)
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = onChange(false)
            override fun onServiceUnregistered(info: NsdServiceInfo) = onChange(false)
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure { onChange(false) }
    }

    fun unregister() {
        listener?.let { runCatching { manager.unregisterService(it) } }
        listener = null
    }
}
