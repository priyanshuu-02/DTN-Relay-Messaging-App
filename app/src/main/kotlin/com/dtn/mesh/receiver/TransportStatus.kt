package com.dtn.mesh.receiver

/** The four independently controllable transports, in send-priority order. */
enum class TransportKind { BLE, WIFI_DIRECT, HUB, INTERNET }

/**
 * The link that actually carried one bundle. [HUB] = handed to the hub that is itself the target;
 * [HUB_FLOOD] = handed to the hub as a last resort to flood the LoRa backbone toward someone else.
 * Only BLE / Wi-Fi Direct / HUB reach the target directly; the rest need an end-to-end receipt.
 */
enum class SendPath(val label: String, val reachesTargetDirectly: Boolean) {
    BLE("BLE", true),
    WIFI_DIRECT("Wi-Fi Direct", true),
    HUB("LoRa hub", true),
    /**
     * Handed to our hub for a phone registered at the same hub: the hub passes it straight on,
     * but that's two hops through infrastructure, so delivery still waits for the receipt.
     */
    HUB_LOCAL("LoRa hub", false),
    HUB_FLOOD("LoRa hub flood", false),
    INTERNET("Internet relay", false),
}

/** Result of a successful send: transport packet id + the link used. */
data class SendOutcome(val packetId: Int, val path: SendPath)

/** One device reachable over a link right now, for the link details sheet. */
data class LinkPeer(
    val nodeId: String,
    /** e.g. "Group owner", "Member", "On this hub". */
    val role: String = "",
    /** e.g. "192.168.49.12", "RSSI −61 dBm". */
    val detail: String = "",
)

/** What one link is doing right now, for the link details sheet (tap a transport chip). */
data class LinkInfo(
    /** One-line state, e.g. "Group owner · 2 phones linked". */
    val summary: String = "",
    /** Extra facts, e.g. the Wi-Fi Direct group name or the relay endpoint. */
    val facts: List<String> = emptyList(),
    val peers: List<LinkPeer> = emptyList(),
)

/**
 * Live state of every transport plus the set the user wants running.
 *
 * "Wanted" and "state" are deliberately separate: a transport can be wanted but not up (Bluetooth
 * switched off, hub out of range, no internet), and the UI needs both to tell "off" apart from
 * "trying / needs attention". The aggregate is recomputed from this snapshot every time any
 * transport changes, so it can never go stale the way a connect-time snapshot did.
 */
data class TransportStatus(
    val ble: MeshConnectionState = MeshConnectionState.DISCONNECTED,
    val wifiDirect: MeshConnectionState = MeshConnectionState.DISCONNECTED,
    val hub: MeshConnectionState = MeshConnectionState.DISCONNECTED,
    val internet: MeshConnectionState = MeshConnectionState.DISCONNECTED,
    val wanted: Set<TransportKind> = emptySet(),
) {
    fun stateOf(kind: TransportKind): MeshConnectionState = when (kind) {
        TransportKind.BLE -> ble
        TransportKind.WIFI_DIRECT -> wifiDirect
        TransportKind.HUB -> hub
        TransportKind.INTERNET -> internet
    }

    fun isWanted(kind: TransportKind): Boolean = kind in wanted

    /** Transports that are up and usable right now. */
    val activeKinds: List<TransportKind>
        get() = TransportKind.entries.filter { stateOf(it) == MeshConnectionState.CONNECTED }

    /**
     * CONNECTED if any transport is usable, CONNECTING if any is still coming up, otherwise
     * DISCONNECTED. Wi-Fi Direct discovery reports CONNECTING until a P2P group forms, which is
     * accurate: discovery alone can't carry a bundle.
     */
    val aggregate: MeshConnectionState
        get() = when {
            TransportKind.entries.any { stateOf(it) == MeshConnectionState.CONNECTED } -> MeshConnectionState.CONNECTED
            TransportKind.entries.any { stateOf(it) == MeshConnectionState.CONNECTING } -> MeshConnectionState.CONNECTING
            else -> MeshConnectionState.DISCONNECTED
        }
}
