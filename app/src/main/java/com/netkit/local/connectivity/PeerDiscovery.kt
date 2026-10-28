package com.netkit.local.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** LAN DNS-SD peer sightings. Discovery is NOT authentication or trusted pairing. */
class PeerDiscovery(context: Context) {
    data class Peer(val name: String, val address: String, val port: Int)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val found = mutableStateListOf<Peer>()
    val discoveryWarning = mutableStateOf<String?>(null)

    // Reuse the tested bounded resolve ledger, idempotent multicast lease and
    // advertisement generation fence rather than duplicating their state machines.
    private val resolveLedger = NsdResolveLedger()
    private val discoveryGate = DiscoveryStateGate()
    private var discoveryEpoch: Long? = null
    private val advertisementEpoch = AdvertisementEpoch()
    private var registrationEpoch: Long? = null
    private var callback: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var platformMulticastLock: WifiManager.MulticastLock? = null
    private val multicastLease = MulticastLockLease(
        acquireAction = {
            val wifi = wifiManager ?: error("Wi-Fi service unavailable")
            val lock = platformMulticastLock ?: wifi.createMulticastLock("NetKitPeerDiscovery").also {
                it.setReferenceCounted(false)
                platformMulticastLock = it
            }
            if (!lock.isHeld) lock.acquire()
        },
        releaseAction = {
            platformMulticastLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
    )

    private fun acquireMulticastLease() {
        runCatching { multicastLease.acquire() }.onFailure {
            discoveryWarning.value = "تعذر تفعيل استقبال multicast: ${it.localizedMessage.orEmpty()}"
        }
    }

    private fun releaseMulticastLease() {
        runCatching { multicastLease.release() }.onFailure {
            discoveryWarning.value = "تعذر تحرير قفل multicast: ${it.localizedMessage.orEmpty()}"
        }
    }

    fun start() {
        var epoch = 0L
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                // Serialize stop with in-flight resolve publication. A stale listener
                // must never clear a newer scan or release its multicast lease.
                discoveryGate.stop(epoch) {
                    if (callback !== this) false else {
                        callback = null
                        discoveryEpoch = null
                        resolveLedger.clear()
                        found.clear()
                        discoveryWarning.value = "توقف اكتشاف الأجهزة من النظام. أعد المحاولة."
                        // Release while the gate is held so a new session cannot
                        // acquire the same non-reference-counted lock before this release.
                        releaseMulticastLease()
                        true
                    }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryGate.stop(epoch) {
                    if (callback !== this) false else {
                        callback = null
                        discoveryEpoch = null
                        resolveLedger.clear()
                        found.clear()
                        discoveryWarning.value = "تعذر بدء اكتشاف الأجهزة (رمز $errorCode)."
                        releaseMulticastLease()
                        true
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryGate.runIfActive(epoch) {
                    if (callback === this) {
                        discoveryWarning.value = "تعذر إيقاف اكتشاف الأجهزة بالكامل (رمز $errorCode)."
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                discoveryGate.runIfActive(epoch) {
                    if (callback === this) {
                        resolveLedger.invalidate(service.serviceName)
                        found.removeAll { it.name == service.serviceName }
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!NsdDiscoveryPolicy.isNetKitServiceType(service.serviceType)) return
                val serviceName = service.serviceName
                val discoveryListener = this
                val ticket = discoveryGate.withActive(epoch) {
                    if (callback === discoveryListener) resolveLedger.begin(serviceName) else null
                } ?: return
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        discoveryGate.runIfActive(epoch) {
                            if (callback === discoveryListener) resolveLedger.finish(serviceName, ticket)
                        }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        if (info.serviceName != serviceName ||
                            !NsdDiscoveryPolicy.isNetKitServiceType(info.serviceType) ||
                            info.port !in 1..65535 ||
                            host == null ||
                            !PeerAddressPolicy.isLocalPeer(host)
                        ) {
                            discoveryGate.runIfActive(epoch) {
                                if (callback === discoveryListener) resolveLedger.finish(serviceName, ticket)
                            }
                            return
                        }
                        val peer = Peer(info.serviceName, host.hostAddress, info.port)
                        discoveryGate.runIfActive(epoch) {
                            if (callback !== discoveryListener || !resolveLedger.isCurrent(serviceName, ticket)) {
                                return@runIfActive
                            }
                            if (!resolveLedger.finish(serviceName, ticket)) return@runIfActive
                            found.removeAll {
                                it.name == peer.name && it.address == peer.address && it.port == peer.port
                            }
                            found.add(peer)
                        }
                    }
                }
                runCatching { manager.resolveService(service, resolveListener) }
                    .onFailure {
                        discoveryGate.runIfActive(epoch) {
                            if (callback === discoveryListener) resolveLedger.finish(serviceName, ticket)
                        }
                    }
            }
        }
        val startedEpoch = discoveryGate.begin { token ->
            epoch = token
            callback = listener
            discoveryEpoch = token
            found.clear()
            discoveryWarning.value = null
            resolveLedger.clear()
            // Acquire under the same lifecycle gate so a concurrent stop cannot
            // release before acquisition and leave a leaked multicast lock.
            acquireMulticastLease()
        } ?: return
        discoveryGate.runIfActive(startedEpoch) {
            if (callback === listener) {
                runCatching {
                    manager.discoverServices("_netkit._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure { failure ->
                    discoveryGate.stop(startedEpoch) {
                        if (callback !== listener) false else {
                            callback = null
                            discoveryEpoch = null
                            resolveLedger.clear()
                            found.clear()
                            discoveryWarning.value = "تعذر بدء اكتشاف الأجهزة: ${failure.localizedMessage.orEmpty()}"
                            releaseMulticastLease()
                            true
                        }
                    }
                }
            }
        }
    }

    fun advertise(port: Int) {
        stopAdvertising()
        if (port <= 0) return
        val epoch = advertisementEpoch.begin()
        registrationEpoch = epoch
        val name = "NetKit-${Build.MODEL.take(16).replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_netkit._tcp."
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (!advertisementEpoch.isCurrent(epoch)) {
                    runCatching { manager.unregisterService(this) }
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (!advertisementEpoch.isCurrent(epoch)) return
                advertisementEpoch.stop(epoch)
                if (registration === this) registration = null
                if (registrationEpoch == epoch) registrationEpoch = null
                discoveryWarning.value = "تعذر إعلان الجهاز على الشبكة (رمز $errorCode)."
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                if (advertisementEpoch.isCurrent(epoch) && registration === this) {
                    advertisementEpoch.stop(epoch)
                    registration = null
                    registrationEpoch = null
                }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (advertisementEpoch.isCurrent(epoch)) {
                    discoveryWarning.value = "تعذر إيقاف إعلان الجهاز (رمز $errorCode)."
                }
            }
        }
        registration = listener
        runCatching { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                if (advertisementEpoch.isCurrent(epoch)) {
                    advertisementEpoch.stop(epoch)
                    if (registration === listener) registration = null
                    if (registrationEpoch == epoch) registrationEpoch = null
                    discoveryWarning.value = "تعذر إعلان الجهاز: ${it.localizedMessage.orEmpty()}"
                }
            }
    }

    fun stopAdvertising() {
        val active = registration
        val epoch = registrationEpoch
        registration = null
        registrationEpoch = null
        if (epoch != null) advertisementEpoch.stop(epoch)
        active?.let { runCatching { manager.unregisterService(it) } }
    }

    fun stop() {
        var active: NsdManager.DiscoveryListener? = null
        discoveryGate.stopCurrent {
            active = callback
            callback = null
            discoveryEpoch = null
            resolveLedger.clear()
            found.clear()
            // Keep lock release atomic with invalidating the epoch; a new start
            // must not inherit a lock that the old stop releases afterward.
            releaseMulticastLease()
        }
        active?.let { runCatching { manager.stopServiceDiscovery(it) } }
    }

    fun close() { stop(); stopAdvertising() }
}
