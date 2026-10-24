package com.dtn.mesh.receiver

import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hub beacons this phone's BLE scan hears (written by [BleTransportAdapter], read by
 * [LoRaHubTransportAdapter] for auto-join and by the LoRa hub page's Available hubs). Hubs are
 * never phone peers: nothing here creates encounters, GATT links or routing-table entries.
 */
@Singleton
class HubBeaconRegistry @Inject constructor(private val diagnostics: DiagnosticsLog) {

    private val heard = ConcurrentHashMap<String, HubBeacon>()
    private val published = ConcurrentHashMap<String, Long>()

    private val _beacons = MutableStateFlow<List<HubBeacon>>(emptyList())
    /** Hubs heard in the last [HubBeacons.STALE_MS], strongest first. */
    val beacons: StateFlow<List<HubBeacon>> = _beacons.asStateFlow()

    /** When the last hub beacon arrived (0 = none yet), for the LoRa hub page's last-scan line. */
    @Volatile var lastHeardMs: Long = 0L
        private set

    /** When the BLE scan (which hears the beacons) started; 0 = not scanning. */
    @Volatile var scanStartedMs: Long = 0L
        private set

    /** The BLE scan started: beacons can be heard from now on. */
    fun scanStarted(nowMs: Long) { scanStartedMs = nowMs }

    /**
     * Record one heard advert. The list is republished at once when a hub appears or its values
     * change, otherwise at most every [REPUBLISH_MS] (adverts arrive about once a second).
     */
    fun record(beacon: HubBeacon) {
        lastHeardMs = beacon.heardAtMs
        val before = heard.put(beacon.hubId, beacon)
        if (before == null) {
            diagnostics.i(DiagSource.HUB, "Hub beacon: ${beacon.label} heard (${beacon.rssi} dBm, fw ${beacon.firmware}, " +
                "${beacon.phones} phone(s), ${beacon.dmsWaiting} DM(s) waiting" +
                (if (beacon.links.isEmpty()) ", hears no other hub" else ", hears " +
                    beacon.links.joinToString { l -> l.label + (l.rssiDbm?.let { " $it dBm" } ?: "") }) + ")")
        }
        val last = published[beacon.hubId] ?: 0L
        if (before == null || !before.sameContent(beacon) || beacon.heardAtMs - last >= REPUBLISH_MS) publish(beacon.heardAtMs)
    }

    /** Drop hubs not heard for [maxAgeMs] (the BLE sweeper, and Refresh with a short threshold). */
    fun prune(nowMs: Long, maxAgeMs: Long = HubBeacons.STALE_MS) {
        var changed = false
        heard.values.filter { nowMs - it.heardAtMs > maxAgeMs }.forEach { b ->
            if (heard.remove(b.hubId, b)) {
                changed = true
                diagnostics.i(DiagSource.HUB, "Hub beacon: ${b.label} not heard for ${(nowMs - b.heardAtMs) / 1000} s")
            }
        }
        if (changed) publish(nowMs)
    }

    /** Bluetooth stopped: nothing is heard any more. */
    fun clear() {
        scanStartedMs = 0L
        if (heard.isEmpty()) return
        heard.clear()
        published.clear()
        _beacons.value = emptyList()
    }

    /** The strongest hub heard recently (auto-join), skipping [exclude]; null = none heard. */
    fun strongest(nowMs: Long, exclude: Set<String> = emptySet()): HubBeacon? =
        HubBeacons.strongest(heard.values, nowMs, exclude = exclude)

    private fun publish(nowMs: Long) {
        val list = HubBeacons.sorted(heard.values)
        list.forEach { published[it.hubId] = nowMs }
        _beacons.value = list
    }

    private companion object {
        const val REPUBLISH_MS = 3_000L
    }
}
