package com.dtn.mesh.crypto

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.routing.AlgorithmCode
import java.security.MessageDigest
import java.util.Base64

/**
 * Group invite payload (rendered as a QR / shared as text, or sent as a DM to picked members):
 * `dtngrp:<channel>:<name>:<base64 key>`.
 *
 * Pure JVM code (java.util.Base64, API 26+) so it is unit-testable. Unpadded standard Base64 is
 * byte-identical to the original android.util.Base64 NO_WRAP|NO_PADDING encoding, so invites made
 * by earlier builds still parse. Base64 never contains ':', so the key is always the text after
 * the LAST colon and group names may contain colons.
 *
 * The name is capped in UTF-8 bytes so the whole invite always fits ONE encrypted DM in one LoRa
 * frame ([MAX_NAME_BYTES]); builds before Oct 2026 capped it at 32 characters and still read
 * longer names (cut to their own cap).
 */
object GroupInvite {
    const val PREFIX = "dtngrp:"

    /**
     * Text bytes one DM carries in a single LoRa frame: the 223-byte payload budget minus the
     * 16-byte GCM tag minus the 1-byte routing-algorithm code at the start of every DM's plaintext.
     */
    const val DM_TEXT_BYTES = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN - AlgorithmCode.PREFIX_BYTES

    /** Channels are 1..255: at most 3 digits. */
    private const val MAX_CHANNEL_DIGITS = 3

    /** A 32-byte key in unpadded Base64. */
    private const val KEY_B64_CHARS = 43

    /** Invite bytes around the name: "dtngrp:" + channel digits + two ':' + the Base64 key (55). */
    val FIXED_BYTES: Int = PREFIX.length + MAX_CHANNEL_DIGITS + 2 + KEY_B64_CHARS

    /** Longest group name (UTF-8 bytes) whose invite still fits one DM frame: 206 − 55 = 151. */
    val MAX_NAME_BYTES: Int = DM_TEXT_BYTES - FIXED_BYTES

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

    /** One line, trimmed, at most [MAX_NAME_BYTES] UTF-8 bytes (the Create group field uses the same rule). */
    fun cleanName(name: String): String =
        truncateUtf8(name.replace('\n', ' ').replace('\r', ' ').trim(), MAX_NAME_BYTES).trim()

    /** UTF-8 size of [text] (the Create group counter). */
    fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** The longest prefix of [text] that fits [maxBytes] UTF-8 bytes, never splitting a code point. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            out.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}

/**
 * Contact identity QR, public keys only (safe to share anywhere):
 * - v2 `dtnid:2:<hex8 node id>:<base64 xPub>:<base64 edPub>:<display name>`. The node id comes from
 *   the phone's Android id (decision 14), so the QR carries it next to the keys.
 * - v1 `dtnid:1:<base64 xPub>:<base64 edPub>:<display name>` from older builds: the node id is
 *   derived from the keys ([DtnCrypto.deriveNodeNum]).
 */
object IdentityQr {
    /** Every identity QR starts with this (any version). */
    const val PREFIX = "dtnid:"
    private const val V1 = "dtnid:1:"
    private const val V2 = "dtnid:2:"

    class Parsed(val nodeNum: Int, val xPub: ByteArray, val edPub: ByteArray, val name: String)

    fun build(nodeNum: Int, xPub: ByteArray, edPub: ByteArray, name: String): String {
        val enc = Base64.getEncoder().withoutPadding()
        return V2 + "%08x".format(nodeNum) + ":" + enc.encodeToString(xPub) + ":" + enc.encodeToString(edPub) + ":" +
            name.replace('\n', ' ').trim().take(48)
    }

    fun parse(text: String): Parsed? {
        val t = text.trim()
        return when {
            t.startsWith(V2) -> {
                val parts = t.removePrefix(V2).split(":", limit = 4)
                if (parts.size < 3 || parts[0].length != 8) return null
                val node = parts[0].toLongOrNull(16)?.toInt() ?: return null
                if (node == 0 || node == -1) return null
                val (x, ed) = keys(parts[1], parts[2]) ?: return null
                Parsed(node, x, ed, parts.getOrNull(3)?.trim().orEmpty())
            }
            t.startsWith(V1) -> {
                val parts = t.removePrefix(V1).split(":", limit = 3)
                if (parts.size < 2) return null
                val (x, ed) = keys(parts[0], parts[1]) ?: return null
                Parsed(DtnCrypto.deriveNodeNum(x, ed), x, ed, parts.getOrNull(2)?.trim().orEmpty())
            }
            else -> null
        }
    }

    private fun keys(xText: String, edText: String): Pair<ByteArray, ByteArray>? {
        val dec = Base64.getDecoder()
        val x = runCatching { dec.decode(xText) }.getOrNull() ?: return null
        val ed = runCatching { dec.decode(edText) }.getOrNull() ?: return null
        if (x.size != 32 || ed.size != 32) return null
        return x to ed
    }
}
