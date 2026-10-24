package com.dtn.mesh.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HubBeaconTest {

    /** A beacon body (after the 0xFFFF company id), laid out like the firmware's buildBeacon(). */
    private fun beacon(
        hub: Long = 0xda64a9b0,
        version: Int = 1,
        fw: Int = 4,
        phones: Int = 2,
        dms: Int = 3,
        links: List<Triple<Int, Int, Int>> = listOf(Triple(0xBDB8, 92, 30)), // short id, −RSSI, SNR × 4
    ): ByteArray {
        val out = mutableListOf<Byte>(0x44, 0x48, version.toByte())
        for (shift in listOf(24, 16, 8, 0)) out += ((hub shr shift) and 0xFF).toByte()
        out += fw.toByte(); out += phones.toByte(); out += dms.toByte(); out += links.size.toByte()
        links.forEach { (id, negRssi, snr4) ->
            out += (id shr 8).toByte(); out += id.toByte(); out += negRssi.toByte(); out += snr4.toByte()
        }
        return out.toByteArray()
    }

    @Test
    fun parse_goodBeacon() {
        val b = HubBeacons.parse(beacon(), rssi = -61, nowMs = 1_000L)
        assertNotNull(b)
        b!!
        assertEquals("!da64a9b0", b.hubId)
        assertEquals(4, b.firmware)
        assertEquals(2, b.phones)
        assertEquals(3, b.dmsWaiting)
        assertEquals(-61, b.rssi)
        assertEquals(1_000L, b.heardAtMs)
        assertEquals(1, b.links.size)
        assertEquals(0xBDB8, b.links[0].shortId)
        assertEquals("hub BDB8", b.links[0].label)
        assertEquals(-92, b.links[0].rssiDbm)
        assertEquals(7.5f, b.links[0].snrDb, 0.001f)
        assertEquals("DTN-HUB-A9B0", b.ssid)
        assertEquals("hub A9B0", b.label)
    }

    @Test
    fun parse_negativeSnr_unknownRssi_noLinks_andTrailingBytes() {
        val b = HubBeacons.parse(beacon(links = listOf(Triple(0x0001, 0, -18))), rssi = -80, nowMs = 0L)!!
        assertNull(b.links[0].rssiDbm)
        assertEquals(-4.5f, b.links[0].snrDb, 0.001f)
        assertEquals(0, HubBeacons.parse(beacon(links = emptyList()), -80, 0L)!!.links.size)
        // Fields appended by a later firmware (same version) are ignored.
        assertNotNull(HubBeacons.parse(beacon() + byteArrayOf(9, 9), -80, 0L))
    }

    @Test
    fun parse_truncated_isRejected() {
        val full = beacon(links = listOf(Triple(0xBDB8, 92, 30), Triple(0x1234, 100, 8)))
        assertNotNull(HubBeacons.parse(full, -70, 0L))
        assertNull(HubBeacons.parse(full.copyOf(full.size - 1), -70, 0L)) // last link cut short
        assertNull(HubBeacons.parse(full.copyOf(10), -70, 0L))            // header cut short
        assertNull(HubBeacons.parse(ByteArray(0), -70, 0L))
        assertNull(HubBeacons.parse(null, -70, 0L))
    }

    @Test
    fun parse_unknownVersion_wrongMagic_andReservedIds_areRejected() {
        assertNull(HubBeacons.parse(beacon(version = 2), -70, 0L))
        assertNull(HubBeacons.parse(beacon(version = 0), -70, 0L))
        assertNull(HubBeacons.parse(beacon().also { it[1] = 0x49 }, -70, 0L))
        assertNull(HubBeacons.parse(beacon(hub = 0), -70, 0L))
        assertNull(HubBeacons.parse(beacon(hub = 0xFFFFFFFF), -70, 0L))
    }

    @Test
    fun ssid_isDtnHub_plusTheLast4HexDigitsOfTheHubId() {
        assertEquals("DTN-HUB-A9B0", HubBeacons.ssidFor("!da64a9b0"))
        assertEquals("DTN-HUB-BDB8", HubBeacons.ssidFor("!da65bdb8"))
        assertEquals("DTN-HUB-00AB", HubBeacons.ssidFor("!000000ab"))
        assertEquals("DTN-HUB-00AB", HubBeacons.ssidFor("ab"))
        // Same suffix as the hub label used everywhere else.
        assertEquals(hubLabel("!da65bdb8").removePrefix("hub "), HubBeacons.ssidFor("!da65bdb8").removePrefix("DTN-HUB-"))
    }

    @Test
    fun strongest_picksTheBestSignalHeardRecently() {
        val now = 100_000L
        val near = HubBeacons.parse(beacon(hub = 0x000000a1), -55, now - 2_000)!!
        val far = HubBeacons.parse(beacon(hub = 0x000000b2), -85, now - 1_000)!!
        val stale = HubBeacons.parse(beacon(hub = 0x000000c3), -40, now - 20_000)!!
        assertEquals(near.hubId, HubBeacons.strongest(listOf(far, near, stale), now)?.hubId)
        // A hub whose Wi-Fi just failed is skipped; with nothing fresh left, nothing is picked.
        assertEquals(far.hubId, HubBeacons.strongest(listOf(far, near, stale), now, exclude = setOf(near.hubId))?.hubId)
        assertNull(HubBeacons.strongest(listOf(stale), now))
        assertNull(HubBeacons.strongest(emptyList(), now))
        // Equal signal: the one heard last, then the lowest id.
        val twinA = HubBeacons.parse(beacon(hub = 0x000000d4), -60, now - 3_000)!!
        val twinB = HubBeacons.parse(beacon(hub = 0x000000e5), -60, now - 1_000)!!
        assertEquals(twinB.hubId, HubBeacons.strongest(listOf(twinA, twinB), now)?.hubId)
        val twinC = twinA.copy(heardAtMs = twinB.heardAtMs)
        assertEquals(twinC.hubId, HubBeacons.strongest(listOf(twinB, twinC), now)?.hubId)
    }

    @Test
    fun sorted_isStrongestFirst_andBarsFollowTheSignal() {
        val a = HubBeacons.parse(beacon(hub = 0x00000001), -90, 0L)!!
        val b = HubBeacons.parse(beacon(hub = 0x00000002), -50, 0L)!!
        assertEquals(listOf(b.hubId, a.hubId), HubBeacons.sorted(listOf(a, b)).map { it.hubId })
        assertEquals(4, HubBeacons.bars(-50))
        assertEquals(2, HubBeacons.bars(-75))
        assertEquals(0, HubBeacons.bars(-95))
    }

    @Test
    fun sameContent_ignoresSignalAndTime() {
        val a = HubBeacons.parse(beacon(), -60, 0L)!!
        assertEquals(true, a.sameContent(a.copy(rssi = -70, heardAtMs = 5L)))
        assertEquals(false, a.sameContent(a.copy(phones = 3)))
    }
}
