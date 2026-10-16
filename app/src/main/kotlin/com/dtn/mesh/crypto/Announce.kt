package com.dtn.mesh.crypto

import java.nio.ByteBuffer

/**
 * Identity announcement carried as the payload of an ANNOUNCE (0x05) bundle (destination =
 * broadcast). It publishes a node's public keys so peers can encrypt to it and verify its node
 * id. The whole record is Ed25519-signed, and receivers must check that the node id derived from
 * the keys matches the bundle's origin before trusting it (trust-on-first-use + optional QR
 * verification on top).
 *
 * Wire layout (payload bytes):
 * ```
 * version(1) flags(1) xPub(32) edPub(32) issuedAt(4, epoch s) nameLen(1) name(n) sig(64)
 * ```
 */
data class Announce(
    val xPub: ByteArray,
    val edPub: ByteArray,
    val issuedAtSec: Int,
    val name: String,
) {
    /** The node id these keys bind to. */
    val nodeNum: Int get() = DtnCrypto.deriveNodeNum(xPub, edPub)

    companion object {
        const val VERSION = 1
        private const val MAX_NAME = 48

        /** Build and sign an announce payload with this node's Ed25519 seed. */
        fun build(xPub: ByteArray, edPub: ByteArray, edSeed: ByteArray, name: String, issuedAtSec: Int): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME) it.copyOf(MAX_NAME) else it }
            val body = ByteBuffer.allocate(1 + 1 + 32 + 32 + 4 + 1 + nameBytes.size)
                .put(VERSION.toByte()).put(0)
                .put(xPub).put(edPub)
                .putInt(issuedAtSec)
                .put(nameBytes.size.toByte()).put(nameBytes)
                .array()
            val sig = DtnCrypto.ed25519Sign(edSeed, body)
            return body + sig
        }

        /** Parse + verify an announce payload. Returns null if malformed or the signature fails. */
        fun parseAndVerify(payload: ByteArray): Announce? {
            if (payload.size < 1 + 1 + 32 + 32 + 4 + 1 + 64) return null
            val buf = ByteBuffer.wrap(payload)
            val version = buf.get().toInt() and 0xFF
            if (version != VERSION) return null
            buf.get() // flags
            val xPub = ByteArray(32).also { buf.get(it) }
            val edPub = ByteArray(32).also { buf.get(it) }
            val issuedAt = buf.getInt()
            val nameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nameLen + 64) return null
            val name = ByteArray(nameLen).also { buf.get(it) }
            val sig = ByteArray(64).also { buf.get(it) }
            val signedLen = payload.size - 64
            val body = payload.copyOfRange(0, signedLen)
            if (!DtnCrypto.ed25519Verify(edPub, body, sig)) return null
            return Announce(xPub, edPub, issuedAt, String(name, Charsets.UTF_8))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Announce) return false
        return xPub.contentEquals(other.xPub) && edPub.contentEquals(other.edPub) &&
            issuedAtSec == other.issuedAtSec && name == other.name
    }

    override fun hashCode(): Int = xPub.contentHashCode() * 31 + edPub.contentHashCode()
}
