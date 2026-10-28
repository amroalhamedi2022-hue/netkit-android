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
    private val discoveryEpoch = DiscoveryEpoch()

    fun start() {
        if (callback != null) return
        found.clear()
        val generation = discoveryEpoch.begin()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (!discoveryEpoch.isCurrent(generation)) return
            }
            override fun onDiscoveryStopped(serviceType: String) {
                if (!discoveryEpoch.isCurrent(generation)) return
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryEpoch.isCurrent(generation)) stop()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // stop() already invalidates the generation; do not revive this listener.
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                if (!discoveryEpoch.isCurrent(generation)) return
                found.removeAll { it.name == service.serviceName }
            }
            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!discoveryEpoch.isCurrent(generation)) return
                if (!service.serviceType.startsWith("_netkit._tcp")) return
                runCatching {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            // NSD resolution is asynchronous and can finish after stop/restart.
                            // A stale callback must never repopulate the current device list.
                            if (!discoveryEpoch.isCurrent(generation)) return
                            val host = info.host
                            if (!LanPeerAddressPolicy.isAllowed(host)) {
                                // Do not retain an older allowed address for the same service
                                // after it resolves to an out-of-scope address.
                                found.removeAll { it.name == info.serviceName }
                                return
                            }
                            val ip = host?.hostAddress ?: return
                            val peer = Peer(info.serviceName, ip, info.port)
                            found.removeAll { it.name == peer.name }
                            found.add(peer)
                        }
                    })
                }
            }
        }
        callback = listener
        runCatching { manager.discoverServices("_netkit._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                if (discoveryEpoch.isCurrent(generation)) {
                    callback = null
                    discoveryEpoch.invalidate()
                }
            }
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
        val listener = callback
        callback = null
        // Invalidate before asking Android to stop; pending resolve callbacks may still arrive.
        discoveryEpoch.invalidate()
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
    }
    fun close() { stop(); stopAdvertising() }
}
