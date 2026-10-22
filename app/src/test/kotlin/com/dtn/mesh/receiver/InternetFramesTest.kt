package com.dtn.mesh.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetFramesTest {

    @Test
    fun wrap_unwrap_roundTrips_withPublisher() {
        val wire = ByteArray(40) { it.toByte() }
        val framed = InternetFrames.wrap(0x99a9769a.toInt(), wire)
        assertEquals(wire.size + InternetFrames.HEADER_LEN, framed.size)
        val u = InternetFrames.unwrap(framed)
        assertEquals(0x99a9769a.toInt(), u.publisherNum)
        assertArrayEquals(wire, u.wire)
    }

    @Test
    fun unwrap_passesRawPayloadsThrough() {
        val raw = ByteArray(40) { 0x11 }
        val u = InternetFrames.unwrap(raw)
        assertNull(u.publisherNum)
        assertArrayEquals(raw, u.wire)
    }

    @Test
    fun presence_encodeDecode() {
        assertEquals(Presence.Status(true, 1_700_000_000L), Presence.decode(Presence.encode(true, 1_700_000_000L)))
        assertEquals(Presence.Status(false, 5L), Presence.decode(Presence.encode(false, 5L)))
        // A cleared retained message (empty payload) means offline.
        assertEquals(false, Presence.decode(ByteArray(0))?.online)
        assertNull(Presence.decode("maybe:12".toByteArray()))
        assertNull(Presence.decode("on:abc".toByteArray()))
    }

    @Test
    fun presence_liveRecordsAreFresh_retainedOnesCountFromWhenSent() {
        val nowMs = 1_700_000_000_000L
        val stale = 150_000L
        val skew = 300_000L
        fun on(agoSec: Long) = Presence.Status(true, nowMs / 1000 - agoSec)
        // Published while we're subscribed: online now, whatever its (possibly drifting) clock says.
        assertEquals(nowMs, Presence.heardAtMs(on(3_600), retained = false, nowMs, stale, skew))
        // Replayed on subscribe: only a recent heartbeat counts, dated when it was sent.
        assertEquals(nowMs - 40_000L, Presence.heardAtMs(on(40), retained = true, nowMs, stale, skew))
        // Left behind by a phone that vanished 9 minutes ago (the old 10-minute window said "online").
        assertNull(Presence.heardAtMs(on(540), retained = true, nowMs, stale, skew))
        // A little clock drift is tolerated, a bogus future timestamp isn't.
        assertEquals(nowMs, Presence.heardAtMs(on(-60), retained = true, nowMs, stale, skew))
        assertNull(Presence.heardAtMs(on(-3_600), retained = true, nowMs, stale, skew))
        // "off" (goodbye or Last Will) is never online.
        assertNull(Presence.heardAtMs(Presence.Status(false, nowMs / 1000), retained = false, nowMs, stale, skew))
    }

    @Test
    fun presence_topicParsing() {
        val prefix = "dtn/v1/presence/"
        assertEquals("99a9769a", Presence.nodeHexFromTopic("dtn/v1/presence/99a9769a", prefix))
        assertNull(Presence.nodeHexFromTopic("dtn/v1/presence/xyz", prefix))
        assertNull(Presence.nodeHexFromTopic("dtn/v1/bcast", prefix))
    }

    @Test
    fun transportStatus_aggregateFollowsEveryTransport() {
        val off = TransportStatus()
        assertEquals(MeshConnectionState.DISCONNECTED, off.aggregate)
        // Only Wi-Fi Direct discovering + hub joining → still connecting (nothing usable yet).
        val coming = off.copy(wifiDirect = MeshConnectionState.CONNECTING, hub = MeshConnectionState.CONNECTING)
        assertEquals(MeshConnectionState.CONNECTING, coming.aggregate)
        // The hub joining later flips the aggregate without anyone re-snapshotting it.
        val hubUp = coming.copy(hub = MeshConnectionState.CONNECTED)
        assertEquals(MeshConnectionState.CONNECTED, hubUp.aggregate)
        assertEquals(listOf(TransportKind.HUB), hubUp.activeKinds)
        val inetOnly = off.copy(internet = MeshConnectionState.CONNECTED)
        assertEquals(MeshConnectionState.CONNECTED, inetOnly.aggregate)
    }
}
