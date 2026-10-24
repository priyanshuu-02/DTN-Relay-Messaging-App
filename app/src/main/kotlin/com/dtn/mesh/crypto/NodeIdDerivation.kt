package com.dtn.mesh.crypto

import java.security.MessageDigest

/**
 * One identity per phone (decision 14): the node id comes from Android's app-scoped device id
 * (`Settings.Secure.ANDROID_ID`, unique per app-signing key + user + device since Android 8),
 * hashed with the package name. Clearing the app's data or reinstalling keeps the id; only a
 * factory reset changes it. Apps can't read the phone's MAC (Android returns 02:00:00:00:00:00).
 *
 * id = first 4 bytes (big-endian uint32) of SHA-256("DTN-node-v1|" + ANDROID_ID + "|" + package).
 * 0 and 0xFFFFFFFF (the broadcast address) are never used: the input is re-hashed with a counter
 * suffix ("|1", "|2", …) until the result is neither.
 *
 * Pure JVM code, so it is unit-tested.
 */
object NodeIdDerivation {
    private const val PREFIX = "DTN-node-v1|"

    /** The value a known Android 2.2 bug returned on many devices: not unique, never used. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    /** False for a missing, blank, all-zero or known-broken ANDROID_ID (keep the stored id then). */
    fun isUsable(androidId: String?): Boolean =
        !androidId.isNullOrBlank() && !androidId.equals(BROKEN_ANDROID_ID, ignoreCase = true) && androidId.any { it != '0' }

    /** The node number for [androidId] + [packageName]; never 0 or 0xFFFFFFFF. */
    fun derive(androidId: String, packageName: String): Int = derive(androidId, packageName, ::sha256)

    /** [derive] with an injectable digest, so the re-hash path can be tested. */
    internal fun derive(androidId: String, packageName: String, digest: (String) -> ByteArray): Int {
        val base = PREFIX + androidId + "|" + packageName
        var counter = 0
        while (true) {
            val h = digest(if (counter == 0) base else "$base|$counter")
            val n = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
                ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (n != 0 && n != -1) return n
            counter++
        }
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
