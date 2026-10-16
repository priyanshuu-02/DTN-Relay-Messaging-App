package com.dtn.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class FragmenterTest {

    @Test
    fun fragmentIds_areDistinct_andRecoverTheSameParent() {
        val parent = Fragmenter.canonicalParent(UUID.randomUUID().toString())
        val ids = (0 until Fragmenter.MAX_FRAGMENTS).map { Fragmenter.fragmentId(parent, it) }
        // All distinct
        assertEquals(ids.size, ids.toSet().size)
        // Each recovers the same canonical parent
        ids.forEach { assertEquals(parent, Fragmenter.canonicalParent(it)) }
        // Fragment ids differ from the canonical parent itself
        ids.forEach { assertNotEquals(parent, it) }
    }

    @Test
    fun canonicalParent_isIdempotent() {
        val id = UUID.randomUUID().toString()
        val c = Fragmenter.canonicalParent(id)
        assertEquals(c, Fragmenter.canonicalParent(c))
    }

    @Test
    fun multiFragment_cryptoRoundTrips_byteExact_withSplitMultibyteChar() {
        // A string whose multi-byte UTF-8 characters will be split across fragment boundaries.
        val original = "हिन्दी 🌐 mesh " .repeat(30) + "END"
        val plain = original.toByteArray(Charsets.UTF_8)

        // Simulate the DM suite: one shared root key (X25519 secret), per-fragment HKDF keys.
        val (aPriv, aPub) = DtnCrypto.generateX25519()
        val (bPriv, bPub) = DtnCrypto.generateX25519()
        val root = DtnCrypto.x25519(aPriv, bPub)
        val rootRecv = DtnCrypto.x25519(bPriv, aPub)

        val chunkSize = 100
        val chunks = plain.toList().chunked(chunkSize).map { it.toByteArray() }
        val total = chunks.size
        val messageId = ByteArray(16) { (it + 1).toByte() }
        val aad = ByteArray(32) { it.toByte() }
        val origin = 0x0A0A0A0A
        val dest = 0x0B0B0B0B

        // Seal each fragment independently (distinct fragment index).
        val sealed = chunks.mapIndexed { i, chunk ->
            DtnCrypto.seal(root, messageId, origin, dest, DtnCrypto.SUITE_DM, i, aad, chunk)
        }

        // Open each, then concatenate BYTES and decode once.
        val joined = java.io.ByteArrayOutputStream()
        sealed.forEachIndexed { i, ct ->
            val opened = DtnCrypto.open(rootRecv, messageId, origin, dest, DtnCrypto.SUITE_DM, i, aad, ct)
            assertArrayEquals("fragment $i", chunks[i], opened)
            joined.write(opened)
        }
        assertEquals(original, String(joined.toByteArray(), Charsets.UTF_8))

        // A fragment opened with the WRONG index must fail (index is bound into key + nonce).
        if (total > 1) {
            assertNull(DtnCrypto.open(rootRecv, messageId, origin, dest, DtnCrypto.SUITE_DM, 1, aad, sealed[0]))
        }
    }
}
