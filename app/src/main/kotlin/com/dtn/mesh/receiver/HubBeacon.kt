package com.dtn.mesh.receiver

import com.dtn.mesh.model.NodeId

/** How well a hub hears one other hub over LoRa (from that hub's last heartbeat). */
data class HubLoRaLink(
    /** Low 16 bits of the other hub's id: its Wi-Fi suffix ("hub BDB8"). */
    val shortId: Int,
    /** LoRa RSSI in dBm, null when the hub reported none. */
    val rssiDbm: Int?,
    /** LoRa SNR in dB (0.25 dB steps). */
    val snrDb: Float,
) {
    val label: String get() = "hub %04X".format(shortId and 0xFFFF)
}

/**
 * One hub's Bluetooth beacon (firmware v4+), as heard by this phone's BLE scan.
 *
 * @property hubId the hub's node id ("!da64a9b0"), the id its HELLO carries.
 * @property phones phones registered at the hub right now.
 * @property dmsWaiting DMs in its buffer waiting for delivery (not broadcasts / announces / receipts).
 * @property links the other hubs it hears over LoRa, strongest first (at most 4).
 * @property rssi the BLE signal this phone heard the beacon with (dBm).
 * @property heardAtMs when this phone last heard it.
 */
data class HubBeacon(
    val hubId: String,
    val firmware: Int,
    val phones: Int,
    val dmsWaiting: Int,
    val links: List<HubLoRaLink>,
    val rssi: Int,
    val heardAtMs: Long,
) {
    /** The hub's SoftAP name, "DTN-HUB-A9B0". */
    val ssid: String get() = HubBeacons.ssidFor(hubId)

    /** "hub A9B0". */
    val label: String get() = hubLabel(hubId)

    /** Same values from the hub (ignores signal and time): a re-heard advert that changed nothing. */
    fun sameContent(other: HubBeacon): Boolean =
        hubId == other.hubId && firmware == other.firmware && phones == other.phones &&
            dmsWaiting == other.dmsWaiting && links == other.links
}

/**
 * The hub beacon format (wire-protocol.md › Hub Bluetooth beacon). A non-connectable legacy
 * advert whose only AD structure is manufacturer data with company id 0xFFFF (no SIG id) and the
 * magic "DH", so it is never mistaken for a phone (phones advertise the DTN GATT service UUID):
 *
 * `[0x44 'D'][0x48 'H'][ver 1][4B hub id BE][fw][phones][DMs waiting][n]` then
 * n × `[2B other hub's short id BE][1B −RSSI dBm, 0 = none][1B SNR × 4, int8]` (n ≤ 4, 27 B max).
 *
 * Readers ignore beacons with another version; fields added later are appended (same version).
 * Pure (unit-tested).
 */
object HubBeacons {
    const val COMPANY_ID = 0xFFFF
    const val MAGIC_0: Byte = 0x44 // 'D'
    const val MAGIC_1: Byte = 0x48 // 'H'
    const val VERSION = 1
    /** Magic, version, hub id, firmware, phones, DMs waiting, n. */
    const val HEADER_BYTES = 11
    const val LINK_BYTES = 4

    /** A beacon counts as heard "now" for auto-join and the Best signal mark this long. */
    const val FRESH_MS = 15_000L
    /** Beacons not heard for this long leave the Available hubs list. */
    const val STALE_MS = 30_000L

    /**
     * Parse the manufacturer data (after the 2-byte company id) of one advert, or null when it
     * isn't a hub beacon we understand: wrong magic, another version, truncated, or a reserved id.
     */
    fun parse(data: ByteArray?, rssi: Int, nowMs: Long): HubBeacon? {
        if (data == null || data.size < HEADER_BYTES) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
        if (u8(data, 2) != VERSION) return null
        val hub = be32(data, 3)
        if (hub == 0 || hub == -1) return null
        val n = u8(data, 10)
        if (data.size < HEADER_BYTES + n * LINK_BYTES) return null
        val links = (0 until n).map { i ->
            val o = HEADER_BYTES + i * LINK_BYTES
            val negRssi = u8(data, o + 2)
            HubLoRaLink(
                shortId = (u8(data, o) shl 8) or u8(data, o + 1),
                rssiDbm = if (negRssi == 0) null else -negRssi,
                snrDb = data[o + 3] / 4f,
            )
        }
        return HubBeacon(
            hubId = NodeId.fromNodeNum(hub).value,
            firmware = u8(data, 7),
            phones = u8(data, 8),
            dmsWaiting = u8(data, 9),
            links = links,
            rssi = rssi,
            heardAtMs = nowMs,
        )
    }

    /** The hub's Wi-Fi name: "DTN-HUB-" + the last 4 hex digits of its id (firmware `%04X`). */
    fun ssidFor(hubId: String): String = "DTN-HUB-" + hubId.removePrefix("!").padStart(4, '0').takeLast(4).uppercase()

    /**
     * The hub auto-join asks for: the strongest beacon heard in the last [freshMs] (ties: the one
     * heard last, then the lowest id), skipping [exclude] (hubs whose Wi-Fi just failed). Null =
     * no beacon heard, so the caller falls back to any `DTN-HUB-*` network.
     */
    fun strongest(
        beacons: Collection<HubBeacon>,
        nowMs: Long,
        freshMs: Long = FRESH_MS,
        exclude: Set<String> = emptySet(),
    ): HubBeacon? = beacons
        .filter { nowMs - it.heardAtMs <= freshMs && it.hubId !in exclude }
        .sortedWith(compareByDescending<HubBeacon> { it.rssi }.thenByDescending { it.heardAtMs }.thenBy { it.hubId })
        .firstOrNull()

    /** Available hubs: strongest signal first (then the lowest id, so the order is stable). */
    fun sorted(beacons: Collection<HubBeacon>): List<HubBeacon> =
        beacons.sortedWith(compareByDescending<HubBeacon> { it.rssi }.thenBy { it.hubId })

    /** 0–4 signal bars for a BLE RSSI. */
    fun bars(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun be32(b: ByteArray, o: Int): Int =
        (u8(b, o) shl 24) or (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)
}
