package com.netkit.local.share

import java.util.Locale

/**
 * Reserves browser-upload destination names without holding a lock while the
 * potentially multi-gigabyte SAF stream is copied.
 *
 * SAF providers do not offer an atomic "create if absent" rename. The final
 * name is rechecked under the same reservation lock immediately before rename;
 * unrelated external provider writes can still race this best-effort check.
 */
internal class UploadNameReservations {
    private val inFlight = HashSet<String>()
    private val lock = Any()

    private fun key(name: String, scope: String): String = scope + "\u0000" + name.lowercase(Locale.ROOT)

    private fun available(requested: String, exists: (String) -> Boolean, scope: String): String {
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (number in 1..9999) {
            val candidate = if (number == 1) requested else "$base ($number)$extension"
            if (key(candidate, scope) !in inFlight && !exists(candidate)) return candidate
        }
        error("Too many duplicate upload names")
    }

    fun reserve(requested: String, scope: String = "", exists: (String) -> Boolean): String = synchronized(lock) {
        val name = available(requested, exists, scope)
        check(inFlight.add(key(name, scope)))
        name
    }

    /**
     * Commit under the short-lived reservation lock; a provider may have
     * created the reserved name while bytes were arriving. Never overwrite it.
     * The caller must release the original reservation in a finally block.
     */
    // Keep both positional-lambda and trailing-lambda callers source-compatible.
    fun commit(
        reservedName: String,
        requested: String,
        exists: (String) -> Boolean,
        rename: (String) -> Boolean
    ): String = commit(reservedName, requested, exists, "", rename)

    fun commit(
        reservedName: String,
        requested: String,
        exists: (String) -> Boolean,
        scope: String,
        rename: (String) -> Boolean
    ): String = synchronized(lock) {
        check(key(reservedName, scope) in inFlight) { "Unknown upload reservation" }
        val destination = if (exists(reservedName)) available(requested, exists, scope) else reservedName
        check(rename(destination)) { "Storage provider refused final rename" }
        destination
    }

    fun release(reservedName: String, scope: String = "") = synchronized(lock) {
        inFlight.remove(key(reservedName, scope))
    }
}
