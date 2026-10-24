package com.dtn.mesh.ui.peers

import com.dtn.mesh.service.ContactRules
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.components.Tone

/** How a peer is reachable right now (best link first). */
enum class ReachKind { BLUETOOTH, WIFI_DIRECT, LORA_HUB, RADIO, INTERNET, OTHER_HUB, OFFLINE }

/**
 * One peer's reachability for chips, row subtitles and the chat header.
 *
 * @property chip status-chip text ("Nearby", "Wi-Fi Direct", "LoRa hub", "via LoRa (hub BDB8)", "Internet", "Offline").
 * @property link the link in words ("BLE", "LoRa hub", "internet relay"), "" when offline.
 */
data class PeerReach(val kind: ReachKind, val chip: String, val link: String, val tone: Tone) {
    val reachable: Boolean get() = kind != ReachKind.OFFLINE
}

/** Pure mapping from a [PeerInfo] (live link flags) to its [PeerReach]. */
fun PeerInfo.reach(): PeerReach = when {
    onBle -> PeerReach(ReachKind.BLUETOOTH, "Nearby", "BLE", Tone.GREEN)
    onWifiDirect -> PeerReach(ReachKind.WIFI_DIRECT, "Wi-Fi Direct", "Wi-Fi Direct", Tone.GREEN)
    isOnline && hubOnly -> PeerReach(ReachKind.LORA_HUB, "LoRa hub", "LoRa hub", Tone.GREEN)
    isOnline -> PeerReach(ReachKind.RADIO, "Nearby", "radio", Tone.GREEN)
    internetOnline -> PeerReach(ReachKind.INTERNET, "Internet", "internet relay", Tone.BLUE)
    meshHub != null -> PeerReach(ReachKind.OTHER_HUB, "via LoRa ($meshHubLabel)", "LoRa ($meshHubLabel)", Tone.GREEN)
    else -> PeerReach(ReachKind.OFFLINE, "Offline", "", Tone.GREY)
}

/** Shared list rules for the Network and Peers pages (pure, unit-tested). */
object PeerLists {
    /** "Seen just now · BLE · −61 dBm" / "On this hub" / "Last seen 5m ago". */
    fun subtitle(peer: PeerInfo, since: (Long) -> String): String {
        val r = peer.reach()
        val rssi = if (r.kind == ReachKind.BLUETOOTH && peer.lastRssi > -200 && peer.lastRssi != 0) " · ${peer.lastRssi} dBm" else ""
        return when (r.kind) {
            ReachKind.BLUETOOTH, ReachKind.WIFI_DIRECT, ReachKind.RADIO -> "Seen ${since(peer.lastSeenMs)} · ${r.link}$rssi"
            ReachKind.LORA_HUB -> "On this LoRa hub"
            ReachKind.OTHER_HUB -> "On ${peer.meshHubLabel} · over LoRa"
            ReachKind.INTERNET -> "Online on the internet relay"
            ReachKind.OFFLINE -> if (peer.lastSeenMs > 0) "Last seen ${since(peer.lastSeenMs)}" else "Not seen yet"
        }
    }

    /** Chat header line: "Online · via BLE", "Last seen 5m ago". */
    fun headerLine(peer: PeerInfo, since: (Long) -> String): String {
        val r = peer.reach()
        return when {
            r.reachable -> "Online · via ${r.link}"
            peer.lastSeenMs > 0 -> "Last seen ${since(peer.lastSeenMs)}"
            else -> "Not seen yet"
        }
    }

    /** Contacts (Peers › Contacts): added, not blocked, not hubs; name / id search. */
    fun contacts(peers: List<PeerInfo>, query: String): List<PeerInfo> =
        peers.filter { ContactRules.inContacts(it.nodeId, it.added, hasChat = false, isHub = it.isHub, blocked = blockedOf(it)) }
            .filter { matches(it, query) }
            .sortedWith(compareByDescending<PeerInfo> { it.reach().reachable }.thenBy { it.displayName().lowercase() })

    /** Discovered: announced phones that aren't contacts (minus blocked, hubs, recently deleted). */
    fun discovered(peers: List<PeerInfo>, deleted: Set<String>): List<PeerInfo> =
        peers.filter { ContactRules.inDiscovered(it.nodeId, it.added, it.hasKeys, it.isHub, blockedOf(it), deleted) }
            .sortedWith(compareByDescending<PeerInfo> { it.reach().reachable }.thenByDescending { it.lastSeenMs })

    /**
     * Nearby devices: phones reachable right now (no hubs, never blocked ones; a deleted phone only
     * once it's a contact again or has announced since). Best link first, then signal.
     */
    fun nearby(peers: List<PeerInfo>, deleted: Set<String>): List<PeerInfo> =
        peers.filter { p -> p.reach().reachable && !p.isHub && !p.blocked && (p.added || p.nodeId !in deleted) }
            .sortedWith(compareBy<PeerInfo> { it.reach().kind.ordinal }.thenByDescending { it.lastRssi })

    fun matches(peer: PeerInfo, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return peer.displayName().contains(q, ignoreCase = true) || peer.nodeId.contains(q, ignoreCase = true) ||
            (peer.announceName?.contains(q, ignoreCase = true) == true)
    }

    private fun blockedOf(p: PeerInfo): Set<String> = if (p.blocked) setOf(p.nodeId) else emptySet()
}
