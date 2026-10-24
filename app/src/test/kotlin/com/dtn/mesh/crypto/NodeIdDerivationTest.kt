package com.dtn.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NodeIdDerivationTest {

    private val androidId = "3f2a9c41b7d0e815"

    @Test
    fun derive_isStable() {
        assertEquals(NodeIdDerivation.derive(androidId, "com.dtn.mesh"), NodeIdDerivation.derive(androidId, "com.dtn.mesh"))
    }

    @Test
    fun derive_differsPerPackage_andPerDevice() {
        val debug = NodeIdDerivation.derive(androidId, "com.dtn.mesh")
        assertNotEquals(debug, NodeIdDerivation.derive(androidId, "com.dtn.mesh.staging"))
        assertNotEquals(debug, NodeIdDerivation.derive("0123456789abcdef", "com.dtn.mesh"))
    }

    @Test
    fun derive_isTheFirstFourBytesOfTheDocumentedHash() {
        val h = MessageDigest.getInstance("SHA-256").digest("DTN-node-v1|$androidId|com.dtn.mesh".toByteArray(Charsets.UTF_8))
        val expected = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        if (expected != 0 && expected != -1) assertEquals(expected, NodeIdDerivation.derive(androidId, "com.dtn.mesh"))
    }

    @Test
    fun derive_neverReturnsZeroOrBroadcast_rehashesWithACounter() {
        val inputs = mutableListOf<String>()
        val digest: (String) -> ByteArray = { input ->
            inputs += input
            when (inputs.size) {
                1 -> ByteArray(32) // 0x00000000
                2 -> ByteArray(32) { 0xFF.toByte() } // 0xFFFFFFFF
                else -> ByteArray(32) { 0x12 }
            }
        }
        val n = NodeIdDerivation.derive("id", "pkg", digest)
        assertEquals(0x12121212, n)
        assertEquals(listOf("DTN-node-v1|id|pkg", "DTN-node-v1|id|pkg|1", "DTN-node-v1|id|pkg|2"), inputs)
    }

    @Test
    fun derive_overManyInputs_neverHitsTheReservedValues() {
        (0 until 2_000).forEach { i ->
            val n = NodeIdDerivation.derive("%016x".format(i), "com.dtn.mesh")
            assertTrue(n != 0 && n != -1)
        }
    }

    @Test
    fun isUsable_rejectsMissingAndBrokenIds() {
        assertFalse(NodeIdDerivation.isUsable(null))
        assertFalse(NodeIdDerivation.isUsable(""))
        assertFalse(NodeIdDerivation.isUsable("   "))
        assertFalse(NodeIdDerivation.isUsable("0000000000000000"))
        assertFalse(NodeIdDerivation.isUsable("9774d56d682e549c"))
        assertTrue(NodeIdDerivation.isUsable(androidId))
    }
}
