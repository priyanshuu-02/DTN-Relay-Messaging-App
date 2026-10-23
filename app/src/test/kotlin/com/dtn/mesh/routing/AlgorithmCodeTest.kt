package com.dtn.mesh.routing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlgorithmCodeTest {

    @Test
    fun everyAlgorithm_roundTripsWithItsName() {
        val text = "hello 👋".toByteArray(Charsets.UTF_8)
        val names = mutableSetOf<String>()
        for (type in StrategySelector.StrategyType.entries) {
            val (label, body) = AlgorithmCode.split(AlgorithmCode.prefix(type, text))
            assertArrayEquals(text, body)
            names += label!!
        }
        assertEquals(StrategySelector.StrategyType.entries.size, names.size) // all distinct
        assertEquals("DiMaxProp", AlgorithmCode.split(AlgorithmCode.prefix(StrategySelector.StrategyType.DIMAXPROP, text)).first)
    }

    @Test
    fun messagesFromOlderBuilds_areLeftAlone() {
        val text = "plain text from an old build".toByteArray(Charsets.UTF_8)
        val (label, body) = AlgorithmCode.split(text)
        assertNull(label)
        assertArrayEquals(text, body)
        assertNull(AlgorithmCode.split(ByteArray(0)).first)
        assertNull(AlgorithmCode.split("éèê".toByteArray(Charsets.UTF_8)).first) // UTF-8 lead byte ≥ 0xC2
    }
}
