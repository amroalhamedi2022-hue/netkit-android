package com.netkit.local.share

import java.util.UUID

/**
 * Bounded, session-local lookup for user-authorized SAF/storage handles.
 * Callers serialize access; entries are LRU and never grant additional storage
 * permissions. The root handle is pinned. Evicted URLs fail closed rather than
 * retaining metadata for every file ever browsed on a large device.
 */
internal class ShareHandleRegistry<T : Any>(private val maxHandles: Int = 8192) {
    init { require(maxHandles >= 2) }
    private val handles = LinkedHashMap<String, T>(16, 0.75f, true)
    private val byKey = HashMap<String, String>()
    private val keyById = HashMap<String, String>()

    val size: Int get() = handles.size

    fun reset(root: T?) {
        handles.clear()
        byKey.clear()
        keyById.clear()
        if (root != null) handles["root"] = root
    }

    fun find(id: String): T? = handles[id]

    fun register(key: String, handle: T): String {
        require(key.isNotEmpty())
        val existing = byKey[key]
        if (existing != null && handles.containsKey(existing)) {
            handles[existing] = handle // refresh the LRU position
            return existing
        }
        if (existing != null) byKey.remove(key)
        while (handles.size >= maxHandles) {
            val victim = handles.keys.firstOrNull { it != "root" }
                ?: error("No evictable shared-file handle")
            handles.remove(victim)
            keyById.remove(victim)?.let { byKey.remove(it) }
        }
        val id = UUID.randomUUID().toString()
        handles[id] = handle
        byKey[key] = id
        keyById[id] = key
        return id
    }
}
