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
    private val registry = PeerDiscoveryRegistry()
    private var callback: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        if (callback != null) return
        found.clear()
        val scanGeneration = registry.beginScan()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                // If Android stops discovery unexpectedly, invalidate outstanding
                // resolve callbacks rather than letting them repopulate a dead scan.
                if (callback === this) {
                    callback = null
                    registry.stop(scanGeneration)
                    found.clear()
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stop() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceLost(service: NsdServiceInfo) {
                if (callback !== this) return
                if (registry.markLost(scanGeneration, service.serviceName)) {
                    found.removeAll { it.name == service.serviceName }
                }
            }

            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (callback !== this || !service.serviceType.startsWith("_netkit._tcp")) return
                val ticket = registry.requestResolve(scanGeneration, service.serviceName) ?: return
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        registry.markResolveFailed(ticket)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (!registry.isCurrent(ticket)) return
                        val host = info.host
                        if (info.serviceName != ticket.serviceName ||
                            !info.serviceType.startsWith("_netkit._tcp") ||
                            info.port !in 1..65535 ||
                            host == null ||
                            !PeerAddressPolicy.isLocalPeer(host)
                        ) {
                            registry.markResolveFailed(ticket)
                            return
                        }
                        val ip = host.hostAddress
                        if (!registry.markResolved(ticket)) return
                        val peer = Peer(info.serviceName, ip, info.port)
                        // Keep same-named peers at distinct local endpoints separate.
                        found.removeAll { it.name == peer.name && it.address == peer.address && it.port == peer.port }
                        found.add(peer)
                    }
                }
                runCatching { manager.resolveService(service, resolveListener) }
                    .onFailure { registry.markResolveFailed(ticket) }
            }
        }
        callback = listener
        runCatching {
            manager.discoverServices("_netkit._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            if (callback === listener) {
                callback = null
                registry.stop(scanGeneration)
                found.clear()
            }
        }
    }

    fun advertise(port: Int) {
        stopAdvertising()
        if (port <= 0) return
        val name = "NetKit-${Build.MODEL.take(16).replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_netkit._tcp."
            setPort(port)
        }
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
        val active = callback
        callback = null
        registry.stop()
        active?.let { runCatching { manager.stopServiceDiscovery(it) } }
        found.clear()
    }

    fun close() { stop(); stopAdvertising() }
}
