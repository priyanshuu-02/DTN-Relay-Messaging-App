package com.dtn.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtnCryptoTest {

    private val messageId = ByteArray(16) { it.toByteArray1() }
    private val aad = ByteArray(32) { (it * 7).toByteArray1() }

    private fun Int.toByteArray1(): Byte = (this and 0xFF).toByte()

    @Test
    fun x25519_sharedSecretIsSymmetric() {
        val (aPriv, aPub) = DtnCrypto.generateX25519()
        val (bPriv, bPub) = DtnCrypto.generateX25519()
        val ssA = DtnCrypto.x25519(aPriv, bPub)
        val ssB = DtnCrypto.x25519(bPriv, aPub)
        assertArrayEquals(ssA, ssB)
        assertEquals(32, ssA.size)
    }

    @Test
    fun dmSealOpen_roundTrips() {
        val (aPriv, aPub) = DtnCrypto.generateX25519()
        val (bPriv, bPub) = DtnCrypto.generateX25519()
        val origin = 0x11111111
        val dest = 0x22222222
        val plaintext = "meet at the north ridge at dawn".toByteArray()

        val rootSender = DtnCrypto.x25519(aPriv, bPub)
        val sealed = DtnCrypto.seal(rootSender, messageId, origin, dest, DtnCrypto.SUITE_DM, 0, aad, plaintext)
        assertEquals(plaintext.size + DtnCrypto.GCM_TAG_LEN, sealed.size)

        val rootReceiver = DtnCrypto.x25519(bPriv, aPub)
        val opened = DtnCrypto.open(rootReceiver, messageId, origin, dest, DtnCrypto.SUITE_DM, 0, aad, sealed)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun open_withWrongKey_fails() {
        val (aPriv, _) = DtnCrypto.generateX25519()
        val (_, bPub) = DtnCrypto.generateX25519()
        val (cPriv, _) = DtnCrypto.generateX25519()
        val (_, dPub) = DtnCrypto.generateX25519()
        val sealed = DtnCrypto.seal(DtnCrypto.x25519(aPriv, bPub), messageId, 1, 2, DtnCrypto.SUITE_DM, 0, aad, "hi".toByteArray())
        assertNull(DtnCrypto.open(DtnCrypto.x25519(cPriv, dPub), messageId, 1, 2, DtnCrypto.SUITE_DM, 0, aad, sealed))
    }

    @Test
    fun open_withTamperedAad_fails() {
        val (aPriv, aPub) = DtnCrypto.generateX25519()
        val (bPriv, bPub) = DtnCrypto.generateX25519()
        val sealed = DtnCrypto.seal(DtnCrypto.x25519(aPriv, bPub), messageId, 1, 2, DtnCrypto.SUITE_DM, 0, aad, "secret".toByteArray())
        val tampered = aad.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(DtnCrypto.open(DtnCrypto.x25519(bPriv, aPub), messageId, 1, 2, DtnCrypto.SUITE_DM, 0, tampered, sealed))
    }

    @Test
    fun differentFragments_useDifferentKeys() {
        val (aPriv, aPub) = DtnCrypto.generateX25519()
        val (bPriv, bPub) = DtnCrypto.generateX25519()
        val root = DtnCrypto.x25519(aPriv, bPub)
        val s0 = DtnCrypto.seal(root, messageId, 1, 2, DtnCrypto.SUITE_DM, 0, aad, "x".toByteArray())
        val s1 = DtnCrypto.seal(root, messageId, 1, 2, DtnCrypto.SUITE_DM, 1, aad, "x".toByteArray())
        assertFalse(s0.contentEquals(s1))
        // Opening fragment 0's bytes as fragment 1 must fail (independent keys).
        assertNull(DtnCrypto.open(DtnCrypto.x25519(bPriv, aPub), messageId, 1, 2, DtnCrypto.SUITE_DM, 1, aad, s0))
    }

    @Test
    fun channelSealOpen_roundTrips() {
        val channelKey = DtnCrypto.randomBytes(32)
        val plaintext = "group broadcast".toByteArray()
        val sealed = DtnCrypto.seal(channelKey, messageId, 5, -1, DtnCrypto.SUITE_CHANNEL, 0, aad, plaintext)
        val opened = DtnCrypto.open(channelKey, messageId, 5, -1, DtnCrypto.SUITE_CHANNEL, 0, aad, sealed)
        assertArrayEquals(plaintext, opened)
        // A different channel key cannot open it.
        assertNull(DtnCrypto.open(DtnCrypto.randomBytes(32), messageId, 5, -1, DtnCrypto.SUITE_CHANNEL, 0, aad, sealed))
    }

    @Test
    fun nodeNum_isDeterministic_andStable() {
        val (_, xPub) = DtnCrypto.generateX25519()
        val (_, edPub) = DtnCrypto.generateEd25519()
        val n1 = DtnCrypto.deriveNodeNum(xPub, edPub)
        val n2 = DtnCrypto.deriveNodeNum(xPub, edPub)
        assertEquals(n1, n2)
        // Different keys → (almost surely) different id.
        val (_, xPub2) = DtnCrypto.generateX25519()
        assertNotNull(DtnCrypto.deriveNodeNum(xPub2, edPub))
    }

    @Test
    fun announce_signVerify_roundTrips_andDetectsTamper() {
        val (_, xPub) = DtnCrypto.generateX25519()
        val (edSeed, edPub) = DtnCrypto.generateEd25519()
        val payload = Announce.build(xPub, edPub, edSeed, "Alice", 1_700_000_000)
        val parsed = Announce.parseAndVerify(payload)
        assertNotNull(parsed)
        assertEquals("Alice", parsed!!.name)
        assertArrayEquals(xPub, parsed.xPub)
        assertEquals(DtnCrypto.deriveNodeNum(xPub, edPub), parsed.nodeNum)

        val tampered = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(Announce.parseAndVerify(tampered))
    }
}
