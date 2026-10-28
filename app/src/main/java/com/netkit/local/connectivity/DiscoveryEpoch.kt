package com.netkit.local.connectivity

import java.util.concurrent.atomic.AtomicLong

/**
 * Generation gate for asynchronous NSD callbacks.
 *
 * Android may deliver resolve callbacks after discovery has stopped. Callers
 * capture the token returned by begin() and must check isCurrent(token) before
 * publishing any result. No network address or token grants peer trust.
 */
internal class DiscoveryEpoch {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = token > 0L && generation.get() == token
}
