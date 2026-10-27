package com.netkit.local.share

import java.text.Normalizer
import java.util.Locale

/**
 * Build an unambiguous, in-memory namespace for a selected SAF tree and relative folders.
 * Tree URI identity matters: equal folder paths in two user-selected trees are not the same destination.
 */
internal fun uploadReservationScope(treeUri: String, folders: List<String>): String {
    require(treeUri.isNotBlank()) { "Missing SAF upload tree" }
    return buildString {
        append(treeUri.length).append(':').append(treeUri)
        folders.forEach { segment -> append('|').append(segment.length).append(':').append(segment) }
    }
}

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
    // Keep the original reservation token linked to any alternate final name selected
    // at commit time. Some SAF providers may not expose a rename immediately to findFile().
    private val heldKeysByReservation = HashMap<String, MutableSet<String>>()
    private val lock = Any()

    private fun key(name: String, scope: String): String {
        // SAF providers differ in Unicode normalization and case behavior. Reserve
        // conservatively using the same NFC + invariant-uppercase key as ZIP paths,
        // so equivalent spellings cannot race into the same destination.
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        val folded = Normalizer.normalize(normalized.uppercase(Locale.ROOT), Normalizer.Form.NFC)
        return scope + "\u0000" + folded
    }

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
        val reservationKey = key(name, scope)
        check(inFlight.add(reservationKey))
        heldKeysByReservation[reservationKey] = mutableSetOf(reservationKey)
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
        val reservationKey = key(reservedName, scope)
        check(reservationKey in inFlight && reservationKey in heldKeysByReservation) {
            "Unknown upload reservation"
        }
        val destination = if (exists(reservedName)) available(requested, exists, scope) else reservedName
        val destinationKey = key(destination, scope)
        val addedDestinationReservation = destinationKey != reservationKey
        if (addedDestinationReservation) {
            check(inFlight.add(destinationKey)) { "Final upload name is already reserved" }
        }
        var renamed = false
        try {
            check(rename(destination)) { "Storage provider refused final rename" }
            renamed = true
            // Keep both keys until release(reservedName): the provider's exists() view may
            // lag behind a successful rename, and a concurrent upload must not reuse it.
            if (addedDestinationReservation) {
                heldKeysByReservation.getValue(reservationKey).add(destinationKey)
            }
            destination
        } finally {
            if (addedDestinationReservation && !renamed) inFlight.remove(destinationKey)
        }
    }

    fun release(reservedName: String, scope: String = "") = synchronized(lock) {
        val reservationKey = key(reservedName, scope)
        val heldKeys = heldKeysByReservation.remove(reservationKey) ?: setOf(reservationKey)
        heldKeys.forEach { inFlight.remove(it) }
    }
}
