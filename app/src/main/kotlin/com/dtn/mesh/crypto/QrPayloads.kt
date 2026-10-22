package com.dtn.mesh.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Group invite payload (rendered as a QR / shared as text): `dtngrp:<channel>:<name>:<base64 key>`.
 *
 * Pure JVM code (java.util.Base64, API 26+) so it is unit-testable. Unpadded standard Base64 is
 * byte-identical to the original android.util.Base64 NO_WRAP|NO_PADDING encoding, so invites made
 * by earlier builds still parse. Base64 never contains ':', so the key is always the text after
 * the LAST colon and group names may contain colons.
 */
object GroupInvite {
    const val PREFIX = "dtngrp:"
    private const val MAX_NAME = 32

    class Parsed(val channel: Int, val name: String, val key: ByteArray)

    fun build(channel: Int, name: String, key: ByteArray): String =
        PREFIX + channel + ":" + cleanName(name) + ":" + Base64.getEncoder().withoutPadding().encodeToString(key)

    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val body = t.removePrefix(PREFIX)
        val channel = body.substringBefore(':', "").toIntOrNull() ?: return null
        val rest = body.substringAfter(':', "")
        if (!rest.contains(':')) return null
        val keyText = rest.substringAfterLast(':')
        val name = rest.substringBeforeLast(':')
        val key = runCatching { Base64.getDecoder().decode(keyText) }.getOrNull() ?: return null
        if (key.size != 32 || channel !in 1..255) return null
        return Parsed(channel, cleanName(name).ifBlank { "group" }, key)
    }

    /**
     * Channel slot for a group key: 1..255 from SHA-256 of the key. Independently created groups
     * now land on effectively random slots instead of everyone's first group being channel 1, so
     * two unrelated groups only collide with probability 1/255 — and a local collision is refused
     * at join time instead of silently overwriting a group.
     */
    fun channelFor(key: ByteArray): Int {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("DTN-grp-ch-v1".toByteArray(Charsets.US_ASCII))
        md.update(key)
        return 1 + ((md.digest()[0].toInt() and 0xFF) % 255)
    }

    private fun cleanName(name: String): String =
        name.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME)
}

/**
 * Contact identity QR: `dtnid:1:<base64 xPub>:<base64 edPub>:<display name>`. Public keys only —
 * safe to share anywhere. The node id is derived from the keys, so it can't be spoofed by the QR.
 */
object IdentityQr {
    const val PREFIX = "dtnid:1:"

    class Parsed(val xPub: ByteArray, val edPub: ByteArray, val name: String)

    fun build(xPub: ByteArray, edPub: ByteArray, name: String): String {
        val enc = Base64.getEncoder().withoutPadding()
        return PREFIX + enc.encodeToString(xPub) + ":" + enc.encodeToString(edPub) + ":" +
            name.replace('\n', ' ').trim().take(48)
    }

    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val parts = t.removePrefix(PREFIX).split(":", limit = 3)
        if (parts.size < 2) return null
        val dec = Base64.getDecoder()
        val x = runCatching { dec.decode(parts[0]) }.getOrNull() ?: return null
        val ed = runCatching { dec.decode(parts[1]) }.getOrNull() ?: return null
        if (x.size != 32 || ed.size != 32) return null
        return Parsed(x, ed, parts.getOrNull(2)?.trim().orEmpty())
    }
}
