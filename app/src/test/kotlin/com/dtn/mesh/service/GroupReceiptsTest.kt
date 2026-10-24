package com.dtn.mesh.service

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.routing.AlgorithmCode
import com.dtn.mesh.routing.StrategySelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GroupReceiptsTest {

    private fun ids(n: Int) = List(n) { UUID.randomUUID().toString() }

    // ── Codec ────────────────────────────────────────────────────────────────

    @Test
    fun encodeDecode_roundTrips() {
        val one = ids(1)
        val r = GroupReceiptCodec.decode(GroupReceiptCodec.encode(7, one))
        assertEquals(GroupReceiptCodec.Receipt(7, one), r)

        val full = ids(GroupReceiptCodec.MAX_IDS)
        val bytes = GroupReceiptCodec.encode(255, full)
        assertEquals(GroupReceiptCodec.Receipt(255, full), GroupReceiptCodec.decode(bytes))
        assertEquals(0xA7, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes[1].toInt())
        assertEquals(GroupReceiptCodec.MAX_IDS, bytes[3].toInt())
    }

    @Test
    fun aFullReceipt_fitsOneLoRaFrame_afterTheDmOverhead() {
        // 223-byte payload − 16 GCM tag = 207 plaintext bytes; 4 header bytes + 12 × 16 = 196.
        assertEquals(207, GroupReceiptCodec.DM_PLAINTEXT_BYTES)
        assertEquals(12, GroupReceiptCodec.MAX_IDS)
        val bytes = GroupReceiptCodec.encode(9, ids(GroupReceiptCodec.MAX_IDS))
        assertTrue(bytes.size <= GroupReceiptCodec.DM_PLAINTEXT_BYTES)
        assertTrue(bytes.size + 16 <= DtnMessage.MAX_SINGLE_PACKET_PAYLOAD)
        // One more id would not fit.
        assertTrue(GroupReceiptCodec.HEADER_BYTES + (GroupReceiptCodec.MAX_IDS + 1) * 16 > GroupReceiptCodec.DM_PLAINTEXT_BYTES)
        assertThrows(IllegalArgumentException::class.java) { GroupReceiptCodec.encode(9, ids(GroupReceiptCodec.MAX_IDS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { GroupReceiptCodec.encode(9, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GroupReceiptCodec.encode(0, ids(1)) }
    }

    @Test
    fun decode_rejectsDamagedOrOtherVersions_toleratesAppendedFields() {
        val good = GroupReceiptCodec.encode(7, ids(2))
        assertNull(GroupReceiptCodec.decode(good.copyOf(good.size - 1)))           // truncated
        assertNull(GroupReceiptCodec.decode(good.copyOf().also { it[1] = 2 }))      // another version
        assertNull(GroupReceiptCodec.decode(good.copyOf().also { it[2] = 0 }))      // channel 0
        assertNull(GroupReceiptCodec.decode(good.copyOf().also { it[3] = 0 }))      // no ids
        assertNull(GroupReceiptCodec.decode(good.copyOf().also { it[3] = 13 }))     // more than fit
        assertNull(GroupReceiptCodec.decode(byteArrayOf(0xA7.toByte(), 1, 7)))     // shorter than the header
        assertNull(GroupReceiptCodec.decode(byteArrayOf(0x01, 1, 7, 1)))           // not marked
        assertEquals(2, GroupReceiptCodec.decode(good + byteArrayOf(1, 2, 3))?.ids?.size)
    }

    @Test
    fun theMarker_neverCollidesWithTextOrAlgorithmCodes() {
        // Every DM from this build starts with an algorithm code (0x01–0x06); none is the marker.
        for (type in StrategySelector.StrategyType.entries) {
            val plain = AlgorithmCode.prefix(type, "hello".toByteArray())
            assertFalse(GroupReceiptCodec.isMarked(plain))
            assertFalse(GroupReceiptCodec.isControlByte(plain[0].toInt() and 0xFF))
        }
        assertNull(AlgorithmCode.label(GroupReceiptCodec.MARKER))
        assertTrue(GroupReceiptCodec.isControlByte(GroupReceiptCodec.MARKER))
        // Valid UTF-8 text (older builds' DMs carry bare text) never starts with 0x80–0xBF: those
        // are continuation bytes. Check the first byte of every code point's encoding.
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp !in 0xD800..0xDFFF) {
                val first = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)[0].toInt() and 0xFF
                assertFalse("U+%04X".format(cp), GroupReceiptCodec.isControlByte(first))
            }
            cp += if (cp < 0x3000) 1 else 97
        }
        for (text in listOf("ok", "é", "👋 hi", "नमस्ते", "日本", "dtngrp:12:Family:abc")) {
            assertFalse(GroupReceiptCodec.isMarked(text.toByteArray(Charsets.UTF_8)))
        }
        assertFalse(GroupReceiptCodec.isMarked(ByteArray(0)))
    }

    @Test
    fun ttl_isWhatTheGroupMessageHadLeft_atLeast30Minutes() {
        assertEquals(30 * 60_000L, GroupReceiptCodec.ttlFor(5 * 60_000L))
        assertEquals(3 * 3_600_000L, GroupReceiptCodec.ttlFor(3 * 3_600_000L))
    }

    // ── Batching ─────────────────────────────────────────────────────────────

    @Test
    fun batching_perOriginAndChannel_afterTheWindow() {
        val b = GroupReceiptBatcher(windowMs = 3_000L)
        assertEquals(3_000L, b.add("!aaaa0001", 7, "m1", ttlLeftMs = 60_000L, nowMs = 0L))
        assertNull(b.add("!aaaa0001", 7, "m2", ttlLeftMs = 90_000L, nowMs = 1_000L)) // joins the waiting batch
        assertEquals(4_000L, b.add("!aaaa0001", 9, "m3", ttlLeftMs = 10_000L, nowMs = 1_000L)) // another channel
        assertEquals(4_000L, b.add("!bbbb0002", 7, "m4", ttlLeftMs = 10_000L, nowMs = 1_000L)) // another origin
        assertNull(b.add("!aaaa0001", 7, "m1", ttlLeftMs = 60_000L, nowMs = 2_000L)) // a re-flood: once

        assertTrue(b.takeDue(2_999L).isEmpty())
        val first = b.takeDue(3_000L).single()
        assertEquals(GroupReceiptBatcher.Batch("!aaaa0001", 7, listOf("m1", "m2"), 90_000L), first)
        assertEquals(2, b.waiting)
        assertEquals(setOf(9, 7), b.takeDue(4_000L).map { it.channel }.toSet())
        assertEquals(0, b.waiting)
    }

    @Test
    fun batching_cutsBigBatchesToOneFrameEach() {
        val b = GroupReceiptBatcher(windowMs = 3_000L)
        val all = ids(GroupReceiptCodec.MAX_IDS * 2 + 1)
        all.forEach { b.add("!aaaa0001", 7, it, 1_000L, 0L) }
        val out = b.takeDue(3_000L)
        assertEquals(listOf(12, 12, 1), out.map { it.ids.size })
        assertEquals(all, out.flatMap { it.ids })
        out.forEach { assertTrue(GroupReceiptCodec.encode(it.channel, it.ids).size <= GroupReceiptCodec.DM_PLAINTEXT_BYTES) }
    }

    @Test
    fun anOverdueBatch_isScheduledAgain() {
        // Its send never ran (the service stopped): the next receipt asks for a send right away.
        val b = GroupReceiptBatcher(windowMs = 3_000L)
        b.add("!aaaa0001", 7, "m1", 1_000L, 0L)
        assertEquals(3_000L, b.add("!aaaa0001", 7, "m2", 1_000L, 10_000L))
        assertEquals(listOf("m1", "m2"), b.takeDue(10_000L).single().ids)
    }

    // ── What the origin records ──────────────────────────────────────────────

    private val me = "0cdc860b"
    private val ownGroupMessage = MessageLifecycleLog.RouteRecord(
        msgId = "1a2b3c4d", source = me, destination = "BROADCAST", me = me, channel = 7,
    )

    @Test
    fun theOrigin_recordsOnlyReceiptsForItsOwnGroupMessages_firstOneEach() {
        assertTrue(GroupReceiptRules.accepts(ownGroupMessage, 7, "!aaaa0001"))
        assertFalse(GroupReceiptRules.accepts(ownGroupMessage, 8, "!aaaa0001"))                  // other channel
        assertFalse(GroupReceiptRules.accepts(ownGroupMessage, 7, "!$me"))                        // ourselves
        assertFalse(GroupReceiptRules.accepts(ownGroupMessage.copy(source = "bbbb0002"), 7, "!aaaa0001")) // someone else's
        assertFalse(GroupReceiptRules.accepts(ownGroupMessage.copy(destination = "ffbd889b", channel = 0), 0, "!aaaa0001")) // a DM
        val got = ownGroupMessage.copy(groupReceipts = listOf(MessageLifecycleLog.GroupReceipt("aaaa0001", 5L, "BLE")))
        assertFalse(GroupReceiptRules.accepts(got, 7, "!aaaa0001"))                               // already recorded
        assertTrue(GroupReceiptRules.accepts(got, 7, "!cccc0003"))
    }
}
