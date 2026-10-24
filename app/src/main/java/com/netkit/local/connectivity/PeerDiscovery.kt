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
    private var discoveryEpoch = 0L
    private var resolveTicket = 0L
    private val pendingResolutions = mutableMapOf<String, Long>()

    fun start() {
        if (callback != null) return
        found.clear()
        pendingResolutions.clear()
        val epoch = ++discoveryEpoch
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                if (epoch == discoveryEpoch) stop()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (epoch == discoveryEpoch) stop()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                if (epoch != discoveryEpoch) return
                // Invalidate an in-flight resolve: its late callback must not resurrect a lost peer.
                pendingResolutions.remove(service.serviceName)
                found.removeAll { it.name == service.serviceName }
            }
            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (epoch != discoveryEpoch || !service.serviceType.startsWith("_netkit._tcp")) return
                val name = service.serviceName
                // NSD advertisements are untrusted. Bound outstanding resolutions as well as UI peers.
                if (name !in pendingResolutions && pendingResolutions.size >= 128) return
                val ticket = ++resolveTicket
                pendingResolutions[name] = ticket
                runCatching {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (epoch == discoveryEpoch && pendingResolutions[name] == ticket) {
                                pendingResolutions.remove(name)
                            }
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (epoch != discoveryEpoch || pendingResolutions[name] != ticket) return
                            pendingResolutions.remove(name)
                            val host = info.host ?: return
                            if (!PeerAddressPolicy.isLocalPeer(host) || info.port !in 1..65535) return
                            val peer = Peer(name, host.hostAddress ?: return, info.port)
                            found.removeAll { it.name == peer.name }
                            if (found.size >= 128) found.removeAt(0)
                            found.add(peer)
                        }
                    })
                }.onFailure {
                    if (pendingResolutions[name] == ticket) pendingResolutions.remove(name)
                }
            }
        }
        callback = listener
        runCatching { manager.discoverServices("_netkit._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { callback = null; discoveryEpoch++; pendingResolutions.clear() }
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
        discoveryEpoch++
        pendingResolutions.clear()
        found.clear()
        callback?.let { runCatching { manager.stopServiceDiscovery(it) } }
        callback = null
    }
    fun close() { stop(); stopAdvertising() }
}
