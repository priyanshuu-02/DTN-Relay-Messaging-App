package com.dtn.mesh.ui

import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    // ── Avatar initials (issue 2.13) ────────────────────────────────────────

    @Test
    fun avatarInitial_lettersAreUppercased() {
        assertEquals("A", UiText.avatarInitial("alice"))
        assertEquals("P", UiText.avatarInitial("  priya"))
    }

    @Test
    fun avatarInitial_keepsEmojiWhole() {
        assertEquals("🚗", UiText.avatarInitial("🚗 Car Mule"))
        assertEquals("📡", UiText.avatarInitial("📡 Base Station"))
    }

    @Test
    fun avatarInitial_blankFallsBack() {
        assertEquals("?", UiText.avatarInitial(""))
        assertEquals("?", UiText.avatarInitial("   "))
        assertEquals("9", UiText.avatarInitial("99a9769a"))
    }

    // ── Safety number (issue 2.7) ──────────────────────────────────────────

    @Test
    fun fingerprintLines_showAll64HexChars() {
        val hex = (0 until 32).joinToString("") { "%02x".format(it * 5) }
        val lines = UiText.fingerprintLines(hex)
        assertEquals(4, lines.size)
        assertEquals(hex, lines.joinToString("").replace(" ", ""))
        assertTrue(lines.all { it.split(" ").size == 4 })
    }

    // ── Broadcast labels (issue 2.3) ───────────────────────────────────────

    @Test
    fun broadcastIds_allRecognised() {
        listOf("BROADCAST", "^all", "!ffffffff", "ffffffff", "!FFFFFFFF").forEach { assertTrue(it, UiText.isBroadcastId(it)) }
        assertFalse(UiText.isBroadcastId("!99a9769a"))
        assertFalse(UiText.isBroadcastId(null))
    }

    // ── Transport chips / headline (issues 2.9, BT-off) ────────────────────

    private val allOn = RadioStatusMonitor.Radios(bluetoothOn = true, wifiOn = true, locationOn = true, internetAvailable = true)

    @Test
    fun chip_bleWantedWithBluetoothOff_saysSoInsteadOfConnecting() {
        val status = TransportStatus(wanted = setOf(TransportKind.BLE))
        val chip = transportChip(TransportKind.BLE, status, allOn.copy(bluetoothOn = false), 0)
        assertEquals(LinkHealth.PROBLEM, chip.health)
        assertEquals("Bluetooth off", chip.detail)
    }

    @Test
    fun chip_notWanted_isOff() {
        val chip = transportChip(TransportKind.HUB, TransportStatus(), allOn, 0)
        assertEquals(LinkHealth.OFF, chip.health)
    }

    @Test
    fun chip_internetConnected_reportsContactsOnline() {
        val status = TransportStatus(internet = MeshConnectionState.CONNECTED, wanted = setOf(TransportKind.INTERNET))
        val chip = transportChip(TransportKind.INTERNET, status, allOn, 2)
        assertEquals(LinkHealth.ON, chip.health)
        assertEquals("2 contacts online", chip.detail)
    }

    @Test
    fun headline_onlineListsActiveLinksOnly_problemsStayOnTheirChips() {
        val status = TransportStatus(
            ble = MeshConnectionState.DISCONNECTED,
            hub = MeshConnectionState.CONNECTED,
            wanted = setOf(TransportKind.BLE, TransportKind.HUB),
        )
        val radios = allOn.copy(bluetoothOn = false)
        val chips = TransportKind.entries.map { transportChip(it, status, radios, 0) }
        val h = connectionHeadline(chips)
        assertEquals(LinkHealth.ON, h.health)
        assertEquals("via LoRa hub", h.detail)
        // The BLE problem is still reported — on the BLE chip itself.
        assertEquals("Bluetooth off", chips.first { it.kind == TransportKind.BLE }.detail)
    }

    @Test
    fun headline_nothingUpYet_listsWhatEachLinkIsDoing() {
        val status = TransportStatus(wifiDirect = MeshConnectionState.CONNECTING, wanted = setOf(TransportKind.WIFI_DIRECT))
        val h = connectionHeadline(TransportKind.entries.map { transportChip(it, status, allOn, 0) })
        assertEquals(LinkHealth.BUSY, h.health)
        assertEquals("Wi-Fi Direct: Searching for phones to link", h.detail)
    }

    @Test
    fun headline_nothingWanted_isOffline() {
        val chips = TransportKind.entries.map { transportChip(it, TransportStatus(), allOn, 0) }
        assertEquals("Offline", connectionHeadline(chips).title)
    }

    @Test
    fun chips_countWhoIsOnEachLink() {
        val status = TransportStatus(
            ble = MeshConnectionState.CONNECTED,
            wifiDirect = MeshConnectionState.CONNECTED,
            hub = MeshConnectionState.CONNECTED,
            wanted = setOf(TransportKind.BLE, TransportKind.WIFI_DIRECT, TransportKind.HUB),
        )
        val counts = mapOf(TransportKind.BLE to 2, TransportKind.WIFI_DIRECT to 3, TransportKind.HUB to 1)
        fun detail(k: TransportKind) = transportChip(k, status, allOn, 0, counts).detail
        assertEquals("Active · 2 nearby", detail(TransportKind.BLE))
        assertEquals("Linked · 3 phones", detail(TransportKind.WIFI_DIRECT))
        assertEquals("Connected · 1 phone", detail(TransportKind.HUB))
        // Nobody on the hub yet: just connected.
        assertEquals("Connected", transportChip(TransportKind.HUB, status, allOn, 0).detail)
    }

    @Test
    fun chip_wifiDirectBelowAndroid10_isGreyAndNeverAProblem() {
        val status = TransportStatus(wanted = setOf(TransportKind.WIFI_DIRECT))
        val chip = transportChip(TransportKind.WIFI_DIRECT, status, allOn, 0, wifiDirectSupported = false)
        assertEquals(LinkHealth.OFF, chip.health)
        assertEquals("Needs Android 10", chip.detail)
        assertEquals("Offline", connectionHeadline(listOf(chip)).title)
    }

    // ── QR codec (issues 2.11 + gallery upload) ────────────────────────────

    @Test
    fun qr_roundTripsThroughPixels() {
        val payload = "dtngrp:42:Hikers: north:" + "A".repeat(43)
        val m = QrCodec.encode(payload)
        assertEquals(payload, QrCodec.decode(QrCodec.toPixels(m), m.width, m.height))
        // Scaled up the way a shared PNG / screenshot would be.
        val scale = 6
        assertEquals(payload, QrCodec.decode(QrCodec.toPixels(m, scale), m.width * scale, m.height * scale))
    }

    @Test
    fun qr_decodesInvertedImages() {
        val payload = "dtnid:1:test"
        val m = QrCodec.encode(payload)
        val scale = 4
        val inverted = QrCodec.toPixels(m, scale).map { if (it == QrCodec.DARK) QrCodec.LIGHT else QrCodec.DARK }.toIntArray()
        assertEquals(payload, QrCodec.decode(inverted, m.width * scale, m.height * scale))
    }

    @Test
    fun qr_blankImageHasNoCode() {
        assertNull(QrCodec.decode(IntArray(200 * 200) { QrCodec.LIGHT }, 200, 200))
    }
}
