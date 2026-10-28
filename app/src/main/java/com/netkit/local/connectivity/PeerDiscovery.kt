package com.netkit.local.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.mutableStateListOf

/** LAN DNS-SD peer sightings. Discovery is NOT authentication or trusted pairing. */
class PeerDiscovery(context: Context) {
    data class Peer(val name: String, val address: String, val port: Int)
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val found = mutableStateListOf<Peer>()
    private val registry = PeerDiscoveryRegistry()
    private val multicastLockGuard = Any()
    private var multicastLeaseHeld = false
    private lateinit var multicastLease: WifiManager.MulticastLock
    private var callback: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null

    /** Hold multicast reception only while DNS-SD discovery is active. */
    private fun acquireMulticastLease() {
        val wifi = wifiManager ?: return
        synchronized(multicastLockGuard) {
            if (multicastLeaseHeld) return
            try {
                if (!this::multicastLease.isInitialized) {
                    multicastLease = wifi.createMulticastLock("NetKitPeerDiscovery")
                    multicastLease.setReferenceCounted(false)
                }
                if (!multicastLease.isHeld) multicastLease.acquire()
                multicastLeaseHeld = multicastLease.isHeld
            } catch (_: Exception) {
                // Discovery can still run on platforms that reject multicast locks.
                // If acquisition partially succeeded, release it before reporting no lease.
                multicastLeaseHeld = runCatching {
                    this::multicastLease.isInitialized && multicastLease.isHeld
                }.getOrDefault(false)
                if (multicastLeaseHeld) runCatching { multicastLease.release() }
                multicastLeaseHeld = false
            }
        }
    }

    private fun releaseMulticastLease() {
        synchronized(multicastLockGuard) {
            if (!multicastLeaseHeld) return
            runCatching {
                if (multicastLease.isHeld) multicastLease.release()
            }
            multicastLeaseHeld = runCatching { multicastLease.isHeld }.getOrDefault(false)
        }
    }

    fun start() {
        if (callback != null) return
        found.clear()
        val scanGeneration = registry.beginScan()
        acquireMulticastLease()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                // If Android stops discovery unexpectedly, invalidate outstanding
                // resolve callbacks rather than letting them repopulate a dead scan.
                if (callback === this) {
                    callback = null
                    registry.stop(scanGeneration)
                    found.clear()
                    releaseMulticastLease()
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
            releaseMulticastLease()
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
        releaseMulticastLease()
    }

    fun close() { stop(); stopAdvertising() }
}
