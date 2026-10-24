package com.dtn.mesh.crypto

import java.nio.ByteBuffer

/**
 * Identity announcement carried as the payload of an ANNOUNCE (0x05) bundle. It publishes a
 * node's public keys so peers can encrypt to it. The whole record is Ed25519-signed, and receivers
 * check that [nodeNum] matches the bundle's origin before trusting it.
 *
 * Wire layout (payload bytes), version 2 (node id derived from ANDROID_ID, decision 14):
 * ```
 * version(1)=2 flags(1) nodeNum(4) xPub(32) edPub(32) issuedAt(4, epoch s) nameLen(1) name(n) sig(64)
 * ```
 * The node id is inside the signature, so an announce can't be re-wrapped under another origin.
 * Version 1 (older builds) has no node id field: its node id is derived from the keys
 * ([DtnCrypto.deriveNodeNum]); those are still accepted. Max size 187 B (fits one LoRa frame).
 */
data class Announce(
    /** The node id these keys belong to (signed in v2, key-derived in v1). */
    val nodeNum: Int,
    val xPub: ByteArray,
    val edPub: ByteArray,
    val issuedAtSec: Int,
    val name: String,
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 2
        private const val VERSION_KEY_DERIVED = 1
        private const val MAX_NAME = 48
        private const val SIG_LEN = 64

        /** Build and sign a version-2 announce payload with this node's Ed25519 seed. */
        fun build(nodeNum: Int, xPub: ByteArray, edPub: ByteArray, edSeed: ByteArray, name: String, issuedAtSec: Int): ByteArray {
            val nameBytes = nameBytes(name)
            val body = ByteBuffer.allocate(1 + 1 + 4 + 32 + 32 + 4 + 1 + nameBytes.size)
                .put(VERSION.toByte()).put(0)
                .putInt(nodeNum)
                .put(xPub).put(edPub)
                .putInt(issuedAtSec)
                .put(nameBytes.size.toByte()).put(nameBytes)
                .array()
            return body + DtnCrypto.ed25519Sign(edSeed, body)
        }

        /** A version-1 payload, as older builds send it (tests and interop only). */
        internal fun buildV1(xPub: ByteArray, edPub: ByteArray, edSeed: ByteArray, name: String, issuedAtSec: Int): ByteArray {
            val nameBytes = nameBytes(name)
            val body = ByteBuffer.allocate(1 + 1 + 32 + 32 + 4 + 1 + nameBytes.size)
                .put(VERSION_KEY_DERIVED.toByte()).put(0)
                .put(xPub).put(edPub)
                .putInt(issuedAtSec)
                .put(nameBytes.size.toByte()).put(nameBytes)
                .array()
            return body + DtnCrypto.ed25519Sign(edSeed, body)
        }

        /** Parse + verify an announce payload (v1 or v2). Returns null if malformed or the signature fails. */
        fun parseAndVerify(payload: ByteArray): Announce? {
            if (payload.isEmpty()) return null
            val version = payload[0].toInt() and 0xFF
            val idLen = when (version) {
                VERSION -> 4
                VERSION_KEY_DERIVED -> 0
                else -> return null
            }
            if (payload.size < 1 + 1 + idLen + 32 + 32 + 4 + 1 + SIG_LEN) return null
            val buf = ByteBuffer.wrap(payload)
            buf.get() // version
            buf.get() // flags
            val signedNode = if (idLen > 0) buf.getInt() else null
            val xPub = ByteArray(32).also { buf.get(it) }
            val edPub = ByteArray(32).also { buf.get(it) }
            val issuedAt = buf.getInt()
            val nameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nameLen + SIG_LEN) return null
            val name = ByteArray(nameLen).also { buf.get(it) }
            val sig = ByteArray(SIG_LEN).also { buf.get(it) }
            val body = payload.copyOfRange(0, payload.size - SIG_LEN)
            if (!DtnCrypto.ed25519Verify(edPub, body, sig)) return null
            val nodeNum = signedNode ?: DtnCrypto.deriveNodeNum(xPub, edPub)
            return Announce(nodeNum, xPub, edPub, issuedAt, String(name, Charsets.UTF_8), version)
        }

        private fun nameBytes(name: String): ByteArray =
            name.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME) it.copyOf(MAX_NAME) else it }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Announce) return false
        return nodeNum == other.nodeNum && xPub.contentEquals(other.xPub) && edPub.contentEquals(other.edPub) &&
            issuedAtSec == other.issuedAtSec && name == other.name && version == other.version
    }

    override fun hashCode(): Int = (nodeNum * 31 + xPub.contentHashCode()) * 31 + edPub.contentHashCode()
}
