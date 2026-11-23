package com.wifistream.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.wifistream.protocol.WifiStream
import java.net.Inet6Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class FoundSender(val name: String, val address: String)

/** Finds phones that are sharing on this network, so nobody has to type an IP address. */
class SenderDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    // The platform resolves one service at a time, so found services wait in a queue.
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val senders = MutableStateFlow<List<FoundSender>>(emptyList())

    /** Adds a device found some other way, such as by asking a hotspot's gateway. */
    fun add(found: FoundSender) {
        senders.update { list -> if (list.any { it.address == found.address }) list else list.filterNot { it.name == found.name } + found }
    }

    fun start() {
        if (listener != null) return
        multicastLock = runCatching {
            wifi.createMulticastLock("wifistream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                senders.update { list -> list.filterNot { it.name == info.serviceName } }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        runCatching { nsd.discoverServices(WifiStream.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(pending) { pending.addLast(info) }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(pending) {
            if (resolving) return
            pending.removeFirstOrNull()?.also { resolving = true }
        } ?: return
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finished()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host
                val ip = host?.hostAddress
                if (ip != null) {
                    val address = "http://" + (if (host is Inet6Address) "[$ip]" else ip) + ":${info.port}"
                    val found = FoundSender(info.serviceName, address)
                    senders.update { list -> list.filterNot { it.name == found.name } + found }
                }
                finished()
            }
        }
        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(next, callback) }.onFailure { finished() }
    }

    private fun finished() {
        synchronized(pending) { resolving = false }
        resolveNext()
    }
}
