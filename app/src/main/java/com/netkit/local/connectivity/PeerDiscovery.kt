package com.netkit.local.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.mutableStateListOf

/** LAN DNS-SD peer sightings. Discovery is NOT authentication or trusted pairing. */
class PeerDiscovery(context: Context) {
    data class Peer(val name: String, val address: String, val port: Int)
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val found = mutableStateListOf<Peer>()
    private var callback: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        if (callback != null) return
        found.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stop() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) { found.removeAll { it.name == service.serviceName } }
            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith("_netkit._tcp")) return
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress ?: return
                        val peer = Peer(info.serviceName, ip, info.port)
                        found.removeAll { it.name == peer.name }
                        found.add(peer)
                    }
                })
            }
        }
        callback = listener
        runCatching { manager.discoverServices("_netkit._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { callback = null }
    }
    fun advertise(port: Int) {
        stopAdvertising()
        if (port <= 0) return
        val name = "NetKit-${Build.MODEL.take(16).replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
        val service = NsdServiceInfo().apply { serviceName = name; serviceType = "_netkit._tcp."; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registration = null }
    }
    fun stopAdvertising() {
        registration?.let { runCatching { manager.unregisterService(it) } }
        registration = null
    }
    fun stop() {
        callback?.let { runCatching { manager.stopServiceDiscovery(it) } }
        callback = null
    }
    fun close() { stop(); stopAdvertising() }
}
