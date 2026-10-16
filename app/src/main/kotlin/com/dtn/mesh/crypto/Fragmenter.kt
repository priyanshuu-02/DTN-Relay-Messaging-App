package com.dtn.mesh.crypto

import java.util.UUID

/**
 * Helpers for splitting a message that exceeds the single-frame payload budget across several
 * bundles.
 *
 * All fragments of one message share a **canonical parent id** (the UUID with its last byte = 0)
 * but each fragment carries a **distinct wire message id** (last byte = index + 1). That way the
 * existing id-based dedup on phones and hubs treats fragments as independent bundles — so every
 * fragment propagates and is relayed normally — while the receiver recovers the parent (to group
 * them for reassembly) simply by zeroing the last byte. Fragment index/total also travel in the
 * header, and each fragment is sealed independently (its own index feeds the AEAD nonce + AAD).
 */
object Fragmenter {
    /** Max fragments per message (index+1 must stay in a byte; also bounds reassembly memory). */
    const val MAX_FRAGMENTS = 16

    /** The shared parent id for a fragment's wire id (last byte zeroed). */
    fun canonicalParent(id: String): String {
        val u = UUID.fromString(id)
        return UUID(u.mostSignificantBits, u.leastSignificantBits and 0xFF.inv().toLong()).toString()
    }

    /** Distinct wire id for fragment [index] (0-based) of [parentId]. */
    fun fragmentId(parentId: String, index: Int): String {
        require(index in 0 until MAX_FRAGMENTS) { "fragment index out of range" }
        val u = UUID.fromString(parentId)
        val lsb = (u.leastSignificantBits and 0xFF.inv().toLong()) or ((index + 1).toLong() and 0xFF)
        return UUID(u.mostSignificantBits, lsb).toString()
    }
}
