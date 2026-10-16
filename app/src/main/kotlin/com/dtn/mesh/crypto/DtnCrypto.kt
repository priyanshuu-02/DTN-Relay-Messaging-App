package com.dtn.mesh.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless cryptographic core for the DTN end-to-end envelope.
 *
 * Suite 1 (direct message): X25519 static-static ECDH → HKDF-SHA256 → AES-256-GCM.
 * Suite 2 (channel/group):  pre-shared 32-byte channel key → HKDF-SHA256 → AES-256-GCM.
 *
 * A fresh AES key + nonce is derived **per (messageId, origin, dest/channel, fragment)** via
 * HKDF, so each (key, nonce) pair is used for exactly one plaintext — nonce reuse is structurally
 * impossible as long as message ids are unique. Overhead on the wire is just the 16-byte GCM tag.
 *
 * Pure Java (BouncyCastle lightweight API + platform JCE), so it runs on API 26+ with no
 * dependency on the platform XDH provider (API 33+) or any native code.
 */
object DtnCrypto {

    const val SUITE_PLAIN = 0
    const val SUITE_DM = 1
    const val SUITE_CHANNEL = 2
    const val SUITE_ANNOUNCE = 3

    const val X25519_KEY_LEN = 32
    const val ED25519_KEY_LEN = 32
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_LEN = 16

    private val secureRandom = SecureRandom()

    // ── X25519 ───────────────────────────────────────────────────────────

    /** Generate a fresh X25519 keypair as raw 32-byte (private, public). */
    fun generateX25519(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(secureRandom)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    /** X25519 ECDH shared secret (32 bytes). Symmetric: agree(a,B)==agree(b,A). */
    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), out, 0)
        return out
    }

    // ── Ed25519 ──────────────────────────────────────────────────────────

    /** Generate a fresh Ed25519 keypair as raw 32-byte (seed, public). */
    fun generateEd25519(): Pair<ByteArray, ByteArray> {
        val priv = Ed25519PrivateKeyParameters(secureRandom)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    // ── HKDF ─────────────────────────────────────────────────────────────

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    // ── AEAD seal / open ─────────────────────────────────────────────────

    /**
     * Derive the per-message AES key + nonce and AES-256-GCM seal [plaintext].
     *
     * @param rootKey   shared secret (DM) or channel key (group).
     * @param messageId 16-byte bundle id (HKDF salt).
     * @param origin    origin node num (binds direction + sender).
     * @param dest      destination node num (or 0xFFFFFFFF for broadcast/channel).
     * @param suite     [SUITE_DM] or [SUITE_CHANNEL].
     * @param fragment  fragment index (each fragment seals independently).
     * @param aad       additional authenticated data (the masked header).
     * @return ciphertext ‖ 16-byte tag.
     */
    fun seal(
        rootKey: ByteArray, messageId: ByteArray, origin: Int, dest: Int,
        suite: Int, fragment: Int, aad: ByteArray, plaintext: ByteArray,
    ): ByteArray {
        val (key, nonce) = deriveKeyNonce(rootKey, messageId, origin, dest, suite, fragment)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Inverse of [seal]. Returns null if authentication fails (tamper / wrong key). */
    fun open(
        rootKey: ByteArray, messageId: ByteArray, origin: Int, dest: Int,
        suite: Int, fragment: Int, aad: ByteArray, ciphertext: ByteArray,
    ): ByteArray? = try {
        val (key, nonce) = deriveKeyNonce(rootKey, messageId, origin, dest, suite, fragment)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } catch (_: Exception) {
        null
    }

    private fun deriveKeyNonce(
        rootKey: ByteArray, messageId: ByteArray, origin: Int, dest: Int, suite: Int, fragment: Int,
    ): Pair<ByteArray, ByteArray> {
        val info = ByteBuffer.allocate(4 + 1 + 4 + 4 + 1)
            .put('D'.code.toByte()).put('T'.code.toByte()).put('N'.code.toByte()).put('1'.code.toByte())
            .put(suite.toByte())
            .putInt(origin).putInt(dest)
            .put(fragment.toByte())
            .array()
        val okm = hkdf(rootKey, messageId, info, 44) // 32-byte key + 12-byte nonce
        val key = okm.copyOfRange(0, 32)
        val nonce = okm.copyOfRange(32, 44)
        return key to nonce
    }

    // ── Identity binding ─────────────────────────────────────────────────

    /** 32-bit node number = first 4 bytes of SHA-256("DTN-node-v1" ‖ xPub ‖ edPub). */
    fun deriveNodeNum(xPub: ByteArray, edPub: ByteArray): Int {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("DTN-node-v1".toByteArray(Charsets.US_ASCII))
        md.update(xPub)
        md.update(edPub)
        val h = md.digest()
        var n = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        if (n == -1) n = n xor 0x1 // never collide with the broadcast address 0xFFFFFFFF
        return n
    }

    /** Full 32-byte key fingerprint (for safety-number display / pinning). */
    fun fingerprint(xPub: ByteArray, edPub: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("DTN-fp-v1".toByteArray(Charsets.US_ASCII))
        md.update(xPub)
        md.update(edPub)
        return md.digest()
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }
}
