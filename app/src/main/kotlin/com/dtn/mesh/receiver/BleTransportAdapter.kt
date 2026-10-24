// File-level suppression — kapt has a long-standing bug where per-method @SuppressLint
// occasionally emits an empty @SuppressLint() in the generated Java stub, which then fails
// the Java compile with "annotation is missing a default value for the element 'value'".
// A single @file:SuppressLint sidesteps the bug and covers every Bluetooth call in this file.
@file:SuppressLint("MissingPermission")

package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.LocalNodeIdentity
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/*
 * BLE transport for encounter-based, phone-to-phone DTN exchange. This is the **primary** P2P
 * path when a phone is attached to a hub SoftAP (WiFi Direct conflicts with a busy WiFi STA;
 * BLE coexists cleanly).
 *
 * Each phone is symmetric:
 * - Runs a **GATT server** exposing a DTN service with the bundle characteristic (centrals write
 *   bundles to it) and, since fix 3, the link characteristic (centrals subscribe to its
 *   indications and write their HELLO to it).
 * - **Advertises** the service UUID plus its 4-byte node id as service data, so scanners learn
 *   who is nearby without connecting.
 * - **Scans** for the service UUID; on discovery emits an encounter.
 *
 * ## One link per phone pair, used both ways (Oct 2026 fix 3, [BleRoles])
 * When two phones see each other only the lower node id connects (central), in the background,
 * and writes its bundles; the higher id (peripheral) sends its bundles over the same link as
 * indications. The higher id connects itself only after 3 s without that link, and nobody opens a
 * client link to a phone that already has a link with us in either role (two client links per pair
 * hung on the Redmi ⇄ OnePlus pair: `_diag/fix3_report.md`). One client link per peer phone, reused
 * while it is up even when the peer's address changes; an older link to the same phone is always
 * closed ([BleLinkRules] has the fix 1 story: leaked links → status 133).
 *
 * A send uses a link that is up NOW, or returns null at once and lets the link come up in the
 * background ([linkReady] then triggers a flush): a flush pass never waits for a connect.
 *
 * ## Bundle transfer framing
 * A bundle is sent as `[2B totalLen BE][wireBytes...]` ([BleFraming]) in MTU − 3 chunks, one in
 * flight: writes with response from the central, indications from the peripheral. Each chunk is
 * acknowledged by the ATT layer (the hop-level confirmation). The receiver reassembles per sender
 * ([FrameAssembler]).
 *
 * Older builds (no link characteristic): our client link to them is write-only and they connect to
 * us for their own bundles, as before fix 3.
 */
@Singleton
class BleTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: LocalNodeIdentity,
    private val wifiDirectHints: WifiDirectHints,
    private val diagnostics: DiagnosticsLog,
    private val hubBeacons: HubBeaconRegistry,
) : DtnTransport {

    companion object {
        private const val TAG = "BleTransport"

        val SERVICE_UUID: UUID = UUID.fromString("d791b000-2c8f-4f2a-9b1e-000000000001")
        val BUNDLE_CHAR_UUID: UUID = UUID.fromString("d791b001-2c8f-4f2a-9b1e-000000000001")
        /**
         * Fix 3: the peripheral's way back. A central subscribes to its indications (CCCD) and writes
         * a [BleHello] to it; the peripheral then sends that central its bundles as indications.
         */
        val LINK_CHAR_UUID: UUID = UUID.fromString("d791b002-2c8f-4f2a-9b1e-000000000001")
        /** The standard Client Characteristic Configuration descriptor (0x2902). */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        private const val DEFAULT_CHUNK = BleFraming.MIN_CHUNK // pre-MTU-negotiation safe size (23 - 3 ATT overhead)
        /** Asked for after connecting; a whole bundle (≤ 257 B framed) then fits one write. */
        private const val REQUEST_MTU = 247
        /** After dropping the peer's cached GATT table, before discovering it afresh. */
        private const val REFRESH_SETTLE_MS = 250L
        private const val ENCOUNTER_COOLDOWN_MS = 8_000L // min gap between encounter emits per peer
        /** Fallback link RSSI (dBm) for an inbound write before the scan path has a real reading. */
        private const val BLE_FALLBACK_RSSI = -70
        /**
         * If we haven't seen ANY signal from a peer (scan hit, GATT connect, inbound write,
         * outbound write) for this long, declare it offline. 20s balances responsiveness
         * against false-positives on devices where the OS throttles BLE scan duty cycle.
         */
        private const val PEER_STALE_THRESHOLD_MS = 20_000L
        /** How often the staleness sweeper wakes up. */
        private const val SWEEPER_TICK_MS = 4_000L
        /** Refresh drops hubs whose ~1 s beacon wasn't heard for this long. */
        private const val HUB_REFRESH_STALE_MS = 5_000L
        private var packetIdCounter = 4_000_000
    }

    /**
     * When the scan last reported anything (a DTN phone or a hub beacon); 0 = nothing yet. Read
     * by the Bluetooth page's "last scan" line (polled, so the scan path stays allocation-free).
     */
    @Volatile var lastScanResultMs: Long = 0L
        private set

    override val transportName: String = "BLE"

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    /**
     * Our client link to a peer phone (we are central), reused for every bundle while it is up,
     * whatever address the peer advertises by now. Tracked from the moment `connectGatt` returns, so
     * a failed or timed-out connect is always closed (see [BleLinkRules] for why).
     */
    private class GattLink(
        val node: String,
        val gatt: BluetoothGatt,
        /** When this try started; a quick retry shares the attempt's [deadlineMs]. */
        val startedMs: Long,
        val tries: Int,
        val deadlineMs: Long,
    ) {
        /** Up: discovery done, and subscribed + HELLO answered (or a write-only link to an older app). */
        @Volatile var ready = false
        @Volatile var closed = false
        @Volatile var mtuDone = false
        @Volatile var mtu = ServerLinkTable.DEFAULT_MTU
        @Volatile var chunk = DEFAULT_CHUNK
        /** The GATT status of the last state change (0 = none / success). */
        @Volatile var lastStatus = 0
        /** The peer's server has the link characteristic: it sends us its bundles as indications. */
        @Volatile var bothWays = false
        /** CONNECTED is reported twice for one connect on some stacks (two MTU requests before fix 3). */
        val connectedSeen = AtomicBoolean(false)
        val discoveryStarted = AtomicBoolean(false)
        val servicesHandled = AtomicBoolean(false)
        /** The connect attempt has its outcome (up, failed, or closed by us): only the first one counts. */
        val settled = AtomicBoolean(false)
        /** The pending chunk's ATT acknowledgement. */
        @Volatile var writeAck: CompletableDeferred<Boolean>? = null
        /** Fails the attempt when its budget runs out. */
        @Volatile var watchdog: Job? = null
        /** The peer's indications, reassembled. */
        val rx = FrameAssembler()
        val node8: String get() = node.takeLast(8)
    }

    /** Node ↔ client link (one per peer phone); callbacks find their own link through their GATT object. */
    private val links = LinkTable<BluetoothGatt, GattLink>()
    /** Guards `connectGatt` + registering its link, so a callback can never miss its link. */
    private val linkLock = Any()
    /** One write / indication sequence per peer phone at a time (a summary, a receipt and a DM never interleave). */
    private val sendLocks = ConcurrentHashMap<String, Mutex>()
    /** What the link did with each peer phone, for the Bluetooth page. */
    private val peerStats = ConcurrentHashMap<String, BlePeerStats>()
    /** NodeId → device, learned from advertisements (and from inbound handshakes). */
    private val deviceForNode = ConcurrentHashMap<String, BluetoothDevice>()

    // ── The peripheral end (fix 3) ──
    /** Centrals connected to our server: which phone each is (HELLO), subscribed, MTU. */
    private val serverLinks = ServerLinkTable()
    /** Address → the central's device object from the server callbacks (indications go to it). */
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    /** Address → the confirmation the indicated chunk in flight waits for. */
    private val indicationAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /** One indication in flight on our whole server (one chunk at a time, `onNotificationSent` paces it). */
    private val indicateLock = Mutex()
    /** Address → reassembly of that central's writes (we are peripheral). */
    private val rxByAddress = ConcurrentHashMap<String, FrameAssembler>()

    // ── Who connects (fix 3) ──
    private val backoff = ConnectBackoff()
    /** Node → since when it has been in range without a link with us (the higher id's 3 s wait). */
    private val noLinkSince = ConcurrentHashMap<String, Long>()
    /** Phones whose server lacks the link characteristic (an older app): both sides connect, as before. */
    private val olderApps: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Node → the last role decision logged (logged when it changes, not on every scan hit). */
    private val loggedDecision = ConcurrentHashMap<String, BleRoles.Decision>()

    /**
     * NodeId → timestamp of most recent scan callback for that peer. Updated on every scan
     * hit (not rate-limited like [lastEncounterEmit]) so the staleness sweeper can distinguish
     * "still advertising" from "went out of range".
     */
    private val peerLastSeenMs = ConcurrentHashMap<String, Long>()

    /**
     * NodeId → most recent REAL RSSI (dBm) observed from that peer's scan advertisements. BLE
     * GATT writes carry no RSSI, so inbound-message link metrics were previously hardcoded to
     * -60 dBm; we now stamp them with the last real scan reading so signal-aware routing
     * (Stability-Aware PROPHET, the Q-learning rssiNorm feature, the RSSI bars) sees true link
     * quality. BLE has no SNR concept, so SNR is reported as 0 (neutral) rather than a fake value.
     */
    private val lastRssiByNode = ConcurrentHashMap<String, Int>()

    /** Scope for background jobs (staleness sweep, connects). Recreated on each [connect]. */
    private var adapterScope: CoroutineScope? = null
    private var sweeperJob: Job? = null

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _linkReady = MutableSharedFlow<NodeId>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** A link with a phone came up (either role): bundles waiting for it can go now (the orchestrator flushes). */
    val linkReady: Flow<NodeId> = _linkReady.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    override val localNodeId: NodeId get() = identity.nodeId

    private val _linkInfo = MutableStateFlow(LinkInfo())
    /** Phones currently heard over BLE (refreshed by the sweeper), for the link details sheet. */
    val linkInfo: StateFlow<LinkInfo> = _linkInfo.asStateFlow()

    private fun publishLinkInfo() {
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val peers = (deviceForNode.keys + serverLinks.readyNodes()).toSortedSet().map { id ->
            LinkPeer(id, role = linkRole(id), detail = lastRssiByNode[id]?.let { "RSSI $it dBm" }.orEmpty(),
                stats = peerStats[id]?.describe { clock.format(java.util.Date(it)) }.orEmpty())
        }
        _linkInfo.value = when {
            !isConnected -> LinkInfo(summary = if (_connectionState.value == MeshConnectionState.CONNECTING) "Starting…" else "Off")
            peers.isEmpty() -> LinkInfo(summary = "Advertising and scanning — no DTN phones in range yet")
            else -> LinkInfo(summary = "${peers.size} DTN phone${if (peers.size == 1) "" else "s"} in range", peers = peers)
        }
    }

    /** The Bluetooth page's role line for one phone. */
    private fun linkRole(node: String): String {
        val client = synchronized(linkLock) { links.current(node) }?.takeIf { !it.closed }
        return when {
            client?.ready == true -> if (client.bothWays) "Linked · we connected" else "Linked · we connected (write only)"
            serverLinks.readyAddress(node) != null -> "Linked · it connected"
            client != null -> "Connecting…"
            else -> "Nearby"
        }
    }

    /** Whether a peer is currently reachable via BLE (seen in recent scans, or linked to us). */
    fun isPeerReachable(peerId: NodeId): Boolean =
        deviceForNode.containsKey(peerId.value) || serverLinks.readyAddress(peerId.value) != null

    /** A link with [peerId] is up right now, in either role: a send goes out at once. */
    fun hasReadyLink(peerId: NodeId): Boolean =
        centralLink(peerId.value) != null || serverLinks.readyAddress(peerId.value) != null

    /** Bring the link with [peerId] up in the background when the role rule lets this phone connect. Never waits. */
    fun requestLink(peerId: NodeId) = maybeConnect(peerId.value)

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        val a = adapter ?: run {
            Log.e(TAG, "No Bluetooth adapter")
            diagnostics.e(DiagSource.BLE, "No Bluetooth adapter on this phone")
            return false
        }
        if (!a.isEnabled) {
            Log.w(TAG, "Bluetooth is off")
            diagnostics.w(DiagSource.BLE, "Start skipped: Bluetooth is off")
            return false
        }
        _connectionState.value = MeshConnectionState.CONNECTING

        try {
            startGattServer()
            startAdvertising()
            startScanning()
            startStalenessSweeper()
            _connectionState.value = MeshConnectionState.CONNECTED
            diagnostics.i(DiagSource.BLE, "Started: GATT server, advertising and scanning")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE permission missing: ${e.message}")
            diagnostics.e(DiagSource.BLE, "Start failed: Bluetooth permission missing")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "BLE startup failed: ${e.message}", e)
            diagnostics.e(DiagSource.BLE, "Start failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        // Report every peer we were tracking as offline first. When only BLE is switched off
        // (other transports still up, orchestrator still running) this keeps the contacts table
        // honest instead of leaving BLE-only peers shown online over a radio that is gone.
        val now = System.currentTimeMillis()
        val tracked = peerLastSeenMs.keys.toList()
        deviceForNode.clear() // first, so these peers no longer count as BLE-reachable
        serverLinks.clear()
        tracked.forEach { id ->
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = NodeId(id), rssi = 0, snr = 0f, timestampMs = now, isOnline = false))
        }
        if (tracked.isNotEmpty() || gattServer != null) {
            diagnostics.i(DiagSource.BLE, "Stopped: scan and advertising off, ${tracked.size} phone(s) dropped")
        }
        runCatching { sweeperJob?.cancel() }; sweeperJob = null
        runCatching { adapterScope?.cancel() }; adapterScope = null
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        synchronized(linkLock) { links.clear() }.forEach { closeLink(it) }
        indicationAcks.values.forEach { it.complete(false) }
        indicationAcks.clear()
        // Drop the phones linked to us too, so their side sees the link go now (closing the server
        // alone can leave their connection up with our service gone).
        serverDevices.values.forEach { d -> runCatching { gattServer?.cancelConnection(d) } }
        runCatching { gattServer?.close() }
        gattServer = null
        serverLinks.clear(); serverDevices.clear(); rxByAddress.clear()
        deviceForNode.clear()
        lastEncounterEmit.clear(); peerLastSeenMs.clear(); peerStats.clear(); sendLocks.clear()
        backoff.clear(); noLinkSince.clear(); olderApps.clear(); loggedDecision.clear()
        hubBeacons.clear() // no scan, no hub beacons
        _linkInfo.value = LinkInfo(summary = "Off")
        _connectionState.value = MeshConnectionState.DISCONNECTED
    }

    /**
     * Periodically declares peers we haven't seen recently as offline.
     *
     * BLE `onConnectionStateChange(DISCONNECTED)` only fires when there is an active GATT
     * session; the common case (peer walks out of scan range without a GATT connection) never
     * triggers it, leaving the remote node stuck showing "online" on other phones. This sweep
     * emits an isOnline=false encounter after [PEER_STALE_THRESHOLD_MS] of scan silence.
     * It also gives every phone in range a chance to connect (the higher id's 3 s wait runs out).
     */
    private fun startStalenessSweeper() {
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        adapterScope = sc
        followWifiDirectHints(sc)
        sweeperJob = sc.launch {
            publishLinkInfo()
            while (isActive) {
                delay(SWEEPER_TICK_MS)
                runStalenessCheck(PEER_STALE_THRESHOLD_MS)
                hubBeacons.prune(System.currentTimeMillis())
                deviceForNode.keys.toList().forEach { maybeConnect(it) }
                publishLinkInfo()
            }
        }
    }

    /**
     * Immediately check every tracked peer against a caller-supplied threshold and emit
     * offline events for stale ones. Called by the Refresh button in the UI with an
     * aggressive short threshold (~3 s) — since we scan with SCAN_MODE_LOW_LATENCY every
     * in-range peer emits advertisements every ~100–500 ms, so 3 seconds of scan silence
     * reliably means the peer stopped advertising (their app hit Disconnect, even if the
     * OS BLE radio is still on).
     */
    fun forceStalenessCheck(thresholdMs: Long = 3_000L) {
        runStalenessCheck(thresholdMs)
        hubBeacons.prune(System.currentTimeMillis(), HUB_REFRESH_STALE_MS)
        publishLinkInfo()
    }

    /** Shared body of the sweeper and the manual refresh, threshold-parameterised. */
    private fun runStalenessCheck(thresholdMs: Long) {
        val now = System.currentTimeMillis()
        // Liveness = adverts or traffic (every bundle either way refreshes it), as before fix 3. A
        // link alone doesn't keep a phone "here": a phone whose app stopped can leave a link behind.
        val stale = peerLastSeenMs.entries.filter { now - it.value > thresholdMs }
        for ((peerIdStr, _) in stale) {
            // Capture the BluetoothDevice before we drop the mapping so we can tear down
            // its GATT session by MAC address. Without this, a cached GATT connection to
            // a peer whose app has hit Disconnect can silently accept writes at the LL
            // layer while the DTN service is gone — the "fake ACK" scenario.
            val staleDevice = deviceForNode.remove(peerIdStr)
            peerLastSeenMs.remove(peerIdStr)
            lastEncounterEmit.remove(peerIdStr)
            lastRssiByNode.remove(peerIdStr)
            noLinkSince.remove(peerIdStr)
            olderApps.remove(peerIdStr)
            backoff.reset(peerIdStr)
            loggedDecision.remove(peerIdStr)
            // Its client link goes too, whatever address it was opened to, and the link it opened to
            // us: nothing may keep a phone we no longer hear "reachable" (it relinks when heard again).
            synchronized(linkLock) { links.current(peerIdStr) }?.let { closeLink(it) }
            serverLinks.readyAddress(peerIdStr)?.let { dropServerLink(it, "not heard for ${thresholdMs / 1000} s") }
            staleDevice?.address?.let { addr -> rxByAddress.remove(addr) }
            _nodeEvents.tryEmit(NodeEncounterEvent(
                nodeId = NodeId(peerIdStr),
                rssi = 0,
                snr = 0f,
                timestampMs = now,
                isOnline = false,
            ))
            Log.d(TAG, "Peer stale (threshold=${thresholdMs}ms): ${peerIdStr.takeLast(8)}")
        }
    }

    /** Drop the link a central opened to us (it reconnects when the role rule lets it). */
    private fun dropServerLink(address: String, why: String) {
        serverLinks.disconnected(address)?.let { peripheralLinkDown(it, why) }
        indicationAcks.remove(address)?.complete(false)
        rxByAddress.remove(address)
        serverDevices.remove(address)?.let { d -> runCatching { gattServer?.cancelConnection(d) } }
    }

    // ── GATT server (peripheral / receive side) ──────────────────────────

    private fun startGattServer() {
        val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val bundle = BluetoothGattCharacteristic(
            BUNDLE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // Fix 3: our bundles go back to a central over this one as indications; it writes its HELLO here.
        val link = BluetoothGattCharacteristic(
            LINK_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        link.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(bundle)
        service.addCharacteristic(link)
        server.addService(service)
        gattServer = server
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Android reports every LE link of this phone here (our own client links too): a
                // bare connection is nobody's link until it subscribes and says HELLO.
                serverLinks.connected(address)
                serverDevices[address] = device
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            // Drop any partial reassembly for this device so the next transfer re-syncs cleanly
            // (the write characteristic alone never signals a client disconnect).
            rxByAddress.remove(address)
            indicationAcks.remove(address)?.complete(false)
            serverDevices.remove(address)
            serverLinks.disconnected(address)?.let { node -> peripheralLinkDown(node, BleLinkRules.statusName(status)) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // Only the central asks for an MTU; our indications use what it negotiated.
            serverLinks.mtu(device.address, mtu)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val cccd = descriptor.uuid == CCCD_UUID && descriptor.characteristic?.uuid == LINK_CHAR_UUID
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId,
                        if (cccd) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, value)
                }
            }
            if (!cccd) return
            val address = device.address
            serverDevices[address] = device
            val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
            val linkedNode = serverLinks.nodeOf(address)?.takeIf { serverLinks.readyAddress(it) == address }
            serverLinks.subscribed(address, on)?.let { peripheralLinkUp(it, device) }
            if (!on && linkedNode != null) peripheralLinkDown(linkedNode, "it unsubscribed")
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (descriptor.uuid == CCCD_UUID && serverLinks.isSubscribed(device.address)) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == LINK_CHAR_UUID) {
                // Answer first: the central's link is up once its HELLO is acknowledged.
                if (responseNeeded) {
                    runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
                }
                onHello(device, value)
                return
            }
            if (characteristic.uuid == BUNDLE_CHAR_UUID) {
                accumulateInbound(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // For an indication this is the central's ATT confirmation (or a failure).
            indicationAcks[device.address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** A central said which phone it is: with its subscription, that connection is our link to it. */
    private fun onHello(device: BluetoothDevice, value: ByteArray?) {
        val num = BleHello.decode(value) ?: run {
            diagnostics.w(DiagSource.BLE, "Unreadable HELLO (${value?.size ?: 0} B) ignored")
            return
        }
        val node = NodeId.fromNodeNum(num)
        if (node == identity.nodeId) return
        serverDevices[device.address] = device
        serverLinks.hello(device.address, node.value)?.let { peripheralLinkUp(it, device) }
    }

    /** A central's link to us is complete (subscribed + HELLO): our bundles go to it as indications. */
    private fun peripheralLinkUp(node: String, device: BluetoothDevice) {
        backoff.reset(node) // it linked to us
        noLinkSince.remove(node)
        loggedDecision.remove(node)
        // Reachable through this link even before (or without) a scan hit.
        deviceForNode.putIfAbsent(node, device)
        diagnostics.i(DiagSource.BLE, "Link up with ${node.takeLast(8)}: it connected (we're peripheral) · " +
            "MTU ${serverLinks.mtuOf(device.address)} · both ways")
        emitOnlineEncounter(NodeId(node), rssi = lastRssiByNode[node] ?: BLE_FALLBACK_RSSI)
        // Both phones connected at the same instant: the link the lower id opened stays.
        val ours = synchronized(linkLock) { links.current(node) }?.takeIf { !it.closed }
        if (ours != null && BleRoles.closeOursOnDuplicate(iAmLowerThan(node))) {
            diagnostics.i(DiagSource.BLE, "Duplicate link with ${node.takeLast(8)}: closing ours, keeping the one it opened (it has the lower id)")
            closeLink(ours)
        }
        _linkReady.tryEmit(NodeId(node))
        publishLinkInfo()
    }

    private fun peripheralLinkDown(node: String, why: String) {
        noLinkSince[node] = System.currentTimeMillis()
        diagnostics.i(DiagSource.BLE, "Link down with ${node.takeLast(8)} (it had connected, we were peripheral): $why")
        publishLinkInfo()
    }

    /** Append inbound bytes for a device (it writes to our server) and pass on complete bundles. */
    private fun accumulateInbound(device: BluetoothDevice, value: ByteArray) {
        val address = device.address
        val rx = rxByAddress.getOrPut(address) { FrameAssembler() }
        val bad = rx.badFrames
        // A sender writes a bundle's chunks back to back; leftovers of a transfer whose link died
        // must not be glued in front of the next one (FrameAssembler starts over after a 2 s gap).
        val frames = rx.accept(value, System.currentTimeMillis())
        // Sanity cap: a valid framed bundle is [2B len][≤255 wire]. A corrupt or aborted transfer
        // can leave bytes that decode to a bogus length — the buffer is reset so we re-sync.
        if (rx.badFrames != bad) Log.w(TAG, "BLE reassembly: bad frame length ${rx.lastBadLength} from $address — resetting buffer")
        // The phone that connection is, when it said HELLO (fix 3); else resolved as before.
        val hello = serverLinks.nodeOf(address)?.let { NodeId(it) }
        for (wire in frames) deliverInbound(wire, device, hello)
    }

    /**
     * One reassembled bundle from a neighbour: written to our server, or indicated to our client
     * link ([knownNeighbour] = the phone the link is with). Both take the same path from here.
     */
    private fun deliverInbound(wire: ByteArray, device: BluetoothDevice, knownNeighbour: NodeId?) {
        val address = device.address
        // Stamp the inbound message with the best REAL link RSSI we have for the sending device.
        // BLE GATT writes carry no RSSI; nodeFor(address) resolves the sender for already-known
        // peers and we reuse their last scan reading (fallback until the scan path learns them).
        // BLE has no SNR, so 0f (neutral) rather than the old fabricated 10 dB.
        val addrNode = knownNeighbour ?: nodeFor(address)
        val linkRssi = addrNode?.let { lastRssiByNode[it.value] } ?: BLE_FALLBACK_RSSI
        val msg = DtnWireCodec.decode(wire, rssi = linkRssi, snr = 0f) ?: return
        // A successful decode means the peer is definitely reachable — refresh the origin's
        // last-seen so the sweeper doesn't misfire during an active exchange.
        peerLastSeenMs[msg.originNodeId.value] = System.currentTimeMillis()

        // Identify the IMMEDIATE neighbour that sent it (the previous hop), NOT the wire header's
        // origin (which for a relayed bundle is some far-away node). A link that said HELLO (or
        // our own client link) names it outright.
        //
        // Otherwise: handshake bundles (ROUTING_SUMMARY / ROUTING_ACK) are always sent by the
        // directly connected peer, so their originNodeId IS that neighbour. We use that to LEARN
        // the device-address → node-id mapping here — critical on radios (e.g. Qualcomm/OnePlus)
        // that suspend active BLE scanning while a GATT link is up: on those phones we may
        // never receive the peer's scan-response service data, so the scan path alone would
        // leave the peer stuck "offline" even while we're actively exchanging data. Learning
        // the mapping from the handshake, plus emitting an online encounter for any inbound
        // bundle, guarantees the peer is recognised purely from received traffic.
        val neighbour: NodeId? = knownNeighbour ?: if (msg.messageType.isHandshake) msg.originNodeId else addrNode
        if (neighbour != null && neighbour != identity.nodeId) {
            deviceForNode[neighbour.value] = device
            // Use the neighbour's last REAL scan RSSI (fallback until the scan path learns it),
            // not a hardcoded constant, so the encounter carries true link quality.
            val neighbourRssi = lastRssiByNode[neighbour.value] ?: linkRssi
            emitOnlineEncounter(neighbour, rssi = neighbourRssi)
        }

        _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = neighbour, transport = TransportKind.BLE))
    }

    // ── Advertising ──────────────────────────────────────────────────────

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { Log.w(TAG, "Advertising unsupported"); return }
        advertiser = adv
        // LOW_LATENCY advertises roughly every 100 ms (vs BALANCED's 250 ms and LOW_POWER's
        // 1 s). Combined with LOW_LATENCY scanning on the peer side this gives near-immediate
        // detection and eliminates the "peer flickers offline while still in range" pattern
        // we saw with BALANCED. Battery cost is real but acceptable while the DTN foreground
        // service is running.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Publish our node id (4 bytes) + our Wi-Fi Direct group hint (6 bytes, see
        // WifiDirectHints) as service data so scanners identify us without connecting. We split
        // the advertisement to fit within the 31-byte legacy limit: Service UUID in the
        // advertisement, Service Data (2 + 16 + 10 = 28 bytes) in the scan response.
        val local = wifiDirectHints.local.value
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(SERVICE_PARCEL, WifiDirectHints.encode(identity.nodeNum32, local))
            .build()
        advertisedHint = local
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @Volatile private var advertisedHint: WifiDirectHints.Local? = null

    /** Re-advertise when this phone's Wi-Fi Direct group state changes (owner / members). */
    private fun followWifiDirectHints(sc: CoroutineScope) {
        sc.launch {
            wifiDirectHints.local.collect { local ->
                if (!isConnected || local == advertisedHint) return@collect
                runCatching { advertiser?.stopAdvertising(advertiseCallback) }
                runCatching { startAdvertising() }.onFailure { Log.w(TAG, "re-advertise failed", it) }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertise failed: $errorCode")
            diagnostics.e(DiagSource.BLE, "Advertising failed: error $errorCode")
        }
    }

    // ── Scanning (central / discover side) ───────────────────────────────

    private fun startScanning() {
        val sc = adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "Scanning unsupported"); return }
        scanner = sc
        // DTN phones (our GATT service UUID) and LoRa hub beacons (manufacturer data 0xFFFF that
        // starts with "DH"): one scan, no extra permission. A hub beacon is never a phone.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL).build(),
            ScanFilter.Builder().setManufacturerData(
                HubBeacons.COMPANY_ID,
                byteArrayOf(HubBeacons.MAGIC_0, HubBeacons.MAGIC_1),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            ).build(),
        )
        hubBeacons.scanStarted(System.currentTimeMillis())
        // LOW_LATENCY scans nearly continuously. BALANCED had multi-second gaps between
        // scan windows, which at edge-of-range meant whole advertisement bursts got missed
        // and the 25 s staleness threshold tripped. LOW_LATENCY reliably picks up an
        // advertising peer within a few hundred ms; combined with LOW_LATENCY advertising
        // on the other side, the offline flicker for in-range peers goes away.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        sc.startScan(filters, settings, scanCallback)
    }

    /** Address → last time we emitted an encounter for this peer (debounce). */
    private val lastEncounterEmit = ConcurrentHashMap<String, Long>()

    /**
     * Mark a peer online from ANY liveness signal — a scan hit OR an inbound GATT write.
     * [peerLastSeenMs] is always refreshed (it drives the staleness sweeper), but the
     * [NodeEncounterEvent] is rate-limited to [ENCOUNTER_COOLDOWN_MS] per peer so we don't
     * flood the orchestrator during an active transfer.
     */
    private fun emitOnlineEncounter(peer: NodeId, rssi: Int) {
        if (peer == identity.nodeId) return
        val now = System.currentTimeMillis()
        peerLastSeenMs[peer.value] = now
        val last = lastEncounterEmit[peer.value] ?: 0L
        if (now - last < ENCOUNTER_COOLDOWN_MS) return
        lastEncounterEmit[peer.value] = now
        _nodeEvents.tryEmit(NodeEncounterEvent(
            nodeId = peer,
            rssi = rssi,
            snr = 0f,
            timestampMs = now,
            isOnline = true,
            shortName = "BLE",
            transport = TransportKind.BLE,
        ))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val now = System.currentTimeMillis()
            lastScanResultMs = now
            // A LoRa hub's beacon: listed under Available hubs only. No device mapping, no GATT,
            // no encounter, no routing-table training — hubs are not phone peers.
            val hub = HubBeacons.parse(result.scanRecord?.getManufacturerSpecificData(HubBeacons.COMPANY_ID), result.rssi, now)
            if (hub != null) {
                hubBeacons.record(hub)
                return
            }
            val serviceData = result.scanRecord?.getServiceData(SERVICE_PARCEL) ?: return
            if (serviceData.size < 4) return
            val peer = NodeId.fromNodeNum(readInt32(serviceData))
            if (peer == identity.nodeId) return // ignore our own advertisement
            // Its Wi-Fi Direct group hint (null for builds that only advertise the node id).
            wifiDirectHints.onBleSighting(peer.value.removePrefix("!"), WifiDirectHints.decode(serviceData))

            deviceForNode[peer.value] = result.device
            lastRssiByNode[peer.value] = result.rssi // real link quality, reused for inbound writes
            emitOnlineEncounter(peer, result.rssi)
            // Two phones that see each other get one link, opened by the lower id (fix 3).
            maybeConnect(peer.value)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            diagnostics.e(DiagSource.BLE, "Scan failed: error $errorCode")
        }
    }

    // ── Who connects (fix 3) ─────────────────────────────────────────────

    private fun iAmLowerThan(node: String): Boolean =
        BleRoles.isLower(identity.nodeNum32, NodeId(node).toNodeNum32())

    /** Our client link to [node] when it is up (ready, not closed). */
    private fun centralLink(node: String): GattLink? =
        synchronized(linkLock) { links.current(node) }?.takeIf { BleLinkRules.reuse(it.ready, it.closed) }

    /**
     * Open the one link with [node] if the role rule ([BleRoles.decide]) lets this phone connect now.
     * Never waits: the connect runs in the background and [linkReady] fires when the link is up.
     * [retryOf] = the quick retry of a try that just failed (same attempt, same budget).
     */
    private fun maybeConnect(node: String, retryOf: GattLink? = null) {
        if (!isConnected) return
        val device = deviceForNode[node] ?: return
        val now = System.currentTimeMillis()
        val decision = synchronized(linkLock) {
            val current = links.current(node)?.takeIf { !it.closed }
            val serverLink = serverLinks.readyAddress(node) != null
            val d = BleRoles.decide(
                iAmLower = iAmLowerThan(node),
                clientLink = current != null,
                clientReady = current?.ready == true,
                serverLink = serverLink,
                olderApp = node in olderApps,
                noLinkSinceMs = if (current == null && !serverLink) noLinkSince.getOrPut(node) { now } else now,
                backoffUntilMs = if (retryOf != null) 0L else backoff.untilMs(node),
                nowMs = now,
            )
            if (d.connects) {
                openLink(node, device, tries = (retryOf?.tries ?: 0) + 1,
                    deadlineMs = retryOf?.deadlineMs ?: (now + BleLinkRules.CONNECT_BUDGET_MS))
            }
            d
        }
        // Role decisions are logged when they change (a connect, a wait), not on every scan hit.
        if (retryOf == null && loggedDecision.put(node, decision) != decision) {
            if (decision.connects) {
                diagnostics.d(DiagSource.BLE, "Connecting to ${node.takeLast(8)} (central): ${decision.why}")
            } else if (decision == BleRoles.Decision.WAIT_FOR_PEER) {
                diagnostics.d(DiagSource.BLE, "Waiting up to ${BleRoles.HIGHER_ID_WAIT_MS / 1000} s for " +
                    "${node.takeLast(8)} to connect: ${decision.why}")
            }
        }
    }

    /**
     * `connectGatt` over LE (never BR/EDR, a classic cause of status 133) and register the link in
     * the same breath, so a callback can't arrive for a link we don't know. Any older link to the
     * same phone (a previous address of it) is closed: one link per phone. A watchdog fails the try
     * when the attempt's budget runs out.
     */
    private fun openLink(node: String, device: BluetoothDevice, tries: Int, deadlineMs: Long): GattLink? = synchronized(linkLock) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "Connecting GATT to ${node.takeLast(8)} (try $tries)")
        val gatt = runCatching {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            val wait = backoff.failed(node, now)
            peerStats.compute(node) { _, s -> (s ?: BlePeerStats()).connectFailed("connectGatt refused", now) }
            diagnostics.w(DiagSource.BLE, "Connect to ${node.takeLast(8)} refused by this phone's Bluetooth · next try in ${wait / 1000} s")
            return@synchronized null
        }
        val link = GattLink(node, gatt, startedMs = now, tries = tries, deadlineMs = deadlineMs)
        links.open(node, gatt, link)?.let { old -> closeLink(old) }
        link.watchdog = adapterScope?.launch {
            delay((deadlineMs - now).coerceAtLeast(1L))
            connectFailed(link, "no answer within ${(System.currentTimeMillis() - now + 500L) / 1000} s")
        }
        link
    }

    /** A connect try failed: close it, then one quick retry within the budget, else back off. */
    private fun connectFailed(link: GattLink, why: String) {
        if (!link.settled.compareAndSet(false, true)) return
        closeLink(link)
        val now = System.currentTimeMillis()
        val took = now - link.startedMs
        peerStats.compute(link.node) { _, s -> (s ?: BlePeerStats()).connectFailed(why, now) }
        if (isConnected && BleLinkRules.retryConnect(link.tries, now, link.deadlineMs)) {
            diagnostics.w(DiagSource.BLE, "Connect to ${link.node8} failed after $took ms: $why — retrying")
            adapterScope?.launch {
                delay(BleLinkRules.RETRY_GAP_MS)
                maybeConnect(link.node, retryOf = link)
            }
            return
        }
        val wait = backoff.failed(link.node, now)
        diagnostics.w(DiagSource.BLE, "Connect to ${link.node8} failed after $took ms: $why · next try in ${wait / 1000} s")
    }

    /** The client link is up: discovery done, and subscribed + HELLO answered ([bothWays]) or write-only. */
    private fun clientReady(link: GattLink, bothWays: Boolean, writeOnlyWhy: String = "") {
        if (link.closed || !link.settled.compareAndSet(false, true)) return
        link.bothWays = bothWays
        link.ready = true
        link.watchdog?.cancel()
        val node = link.node
        backoff.reset(node)
        noLinkSince.remove(node)
        loggedDecision.remove(node)
        peerStats.compute(node) { _, s -> (s ?: BlePeerStats()).connected() }
        val took = System.currentTimeMillis() - link.startedMs
        diagnostics.i(DiagSource.BLE, "Link up with ${link.node8}: we connected (central) in $took ms" +
            (if (link.tries > 1) " (try ${link.tries})" else "") + " · MTU ${link.mtu} · " +
            if (bothWays) "both ways" else "write only ($writeOnlyWhy)")
        // Both phones connected at the same instant: the link the lower id opened stays.
        if (serverLinks.readyAddress(node) != null && BleRoles.closeOursOnDuplicate(iAmLowerThan(node))) {
            diagnostics.i(DiagSource.BLE, "Duplicate link with ${link.node8}: closing ours, keeping the one it opened (it has the lower id)")
            closeLink(link)
            return
        }
        _linkReady.tryEmit(NodeId(node))
        publishLinkInfo()
    }

    // ── GATT client (central side) ───────────────────────────────────────

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        // Global guard: any unexpected exception in the send path returns null instead of
        // crashing the orchestrator's consumer coroutine (which would kill all forwarding).
        return try {
            sendMessageInternal(message, targetPeer)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage crashed for ${targetPeer.value}", e)
            null
        }
    }

    private suspend fun sendMessageInternal(message: DtnMessage, targetPeer: NodeId): Int? {
        val node = targetPeer.value
        if (!isPeerReachable(targetPeer)) {
            Log.w(TAG, "Peer $node not in range")
            return null
        }

        // Encode first, so an encode failure (oversized payload, invalid UUID, …) costs nothing.
        val wire = try {
            DtnWireCodec.encode(message)
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed for ${message.id}: ${e.message}")
            return null
        }
        val framed = BleFraming.frame(wire)

        // One sequence per peer: the chunks over the link that is up, each acknowledged.
        return sendLockFor(node).withLock {
            val startedMs = System.currentTimeMillis()
            val central = centralLink(node)
            val peripheralAt = serverLinks.readyAddress(node)
            val peripheralDevice = peripheralAt?.let { serverDevices[it] }
            val via = BleRoles.sendVia(iAmLowerThan(node), central != null, peripheralDevice != null) ?: run {
                // No link up right now: never wait for one (fix 3). It comes up in the background
                // and its readiness triggers a flush; meanwhile the caller tries Wi-Fi Direct.
                maybeConnect(node)
                return@withLock null
            }
            val ok = when (via) {
                BleRoles.Via.CENTRAL -> sendAsCentral(central!!, framed)
                BleRoles.Via.PERIPHERAL -> sendAsPeripheral(node, peripheralAt!!, peripheralDevice!!, framed)
            }
            if (!ok) return@withLock null
            val pktId = packetIdCounter++
            // A successful send is the strongest possible liveness signal — mid-transfer we may
            // not even receive scan advertisements from the peer (the link is busy carrying our
            // data), so without this refresh the sweeper could false-flag an active peer offline.
            val nowMs = System.currentTimeMillis()
            peerLastSeenMs[node] = nowMs
            peerStats.compute(node) { _, s -> (s ?: BlePeerStats()).sent(nowMs) }
            diagnostics.d(DiagSource.BLE, "Sent ${message.messageType.name} ${message.id.take(8)} (${framed.size} B) to " +
                "${node.takeLast(8)} ${via.label} in ${nowMs - startedMs} ms")
            _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = pktId, status = DeliveryOutcome.DELIVERED))
            pktId
        }
    }

    private fun sendLockFor(node: String) = sendLocks.getOrPut(node) { Mutex() }

    private fun noteWriteFailed(node: String, why: String) {
        peerStats.compute(node) { _, s -> (s ?: BlePeerStats()).writeFailed(why, System.currentTimeMillis()) }
    }

    /** Write over our client link. A link that dies mid-write is closed and comes back in the background. */
    private suspend fun sendAsCentral(link: GattLink, framed: ByteArray): Boolean {
        if (writeFramed(link, framed)) return true
        closeLink(link)
        noLinkSince[link.node] = System.currentTimeMillis()
        noteWriteFailed(link.node, "write not acknowledged")
        diagnostics.w(DiagSource.BLE, "Send to ${link.node8} failed mid-bundle (${framed.size} B) as central — reconnecting in the background")
        maybeConnect(link.node)
        return false
    }

    /** Indicate over the link the peer opened to us. One that stops confirming is dropped (it reconnects). */
    private suspend fun sendAsPeripheral(node: String, address: String, device: BluetoothDevice, framed: ByteArray): Boolean {
        val result = indicateFramed(address, device, framed)
        if (result == Indicated.OK) return true
        val node8 = node.takeLast(8)
        if (result == Indicated.NOT_SENT) {
            noteWriteFailed(node, "indication not sent")
            diagnostics.w(DiagSource.BLE, "Send to $node8 (${framed.size} B) as peripheral: indication not sent")
            return false
        }
        noteWriteFailed(node, "indication not confirmed")
        diagnostics.w(DiagSource.BLE, "Send to $node8 (${framed.size} B) as peripheral: no confirmation in " +
            "${BleLinkRules.INDICATION_ACK_MS / 1000} s — dropping that link so it reconnects")
        serverDevices.putIfAbsent(address, device)
        dropServerLink(address, "indication not confirmed")
        return false
    }

    /**
     * `connectGatt` callbacks find their link here; a GATT object we already closed or replaced
     * finds nothing (see [LinkTable]).
     */
    private fun linkFor(gatt: BluetoothGatt): GattLink? = synchronized(linkLock) { links.linkOf(gatt) }

    /** Close [link] for good: forget it, fail its waiters, disconnect and release its GATT object. */
    private fun closeLink(link: GattLink) {
        if (link.closed) return
        link.closed = true
        link.ready = false
        link.settled.set(true)
        link.watchdog?.cancel()
        synchronized(linkLock) { links.drop(link.node, link) }
        link.writeAck?.complete(false)
        runCatching { link.gatt.disconnect() }
        runCatching { link.gatt.close() }
    }

    /** The framed bundle in chunk-sized pieces, each acknowledged by the peer. */
    private suspend fun writeFramed(link: GattLink, framed: ByteArray): Boolean {
        val characteristic = link.gatt.getService(SERVICE_UUID)?.getCharacteristic(BUNDLE_CHAR_UUID) ?: run {
            Log.w(TAG, "DTN characteristic not found on ${link.node}")
            return false
        }
        for (chunk in BleFraming.chunks(framed, link.chunk)) {
            if (!writeChunkAndWait(link, characteristic, chunk)) return false
        }
        return true
    }

    /**
     * Write one chunk with WRITE_TYPE_DEFAULT (write-with-response) and wait for the
     * peer's ATT ACK. This is the only way to know whether the peer actually received
     * the bytes — with WRITE_TYPE_NO_RESPONSE Android reports success as soon as the
     * write is queued to the radio, which fooled the delivery layer into marking
     * messages as delivered even when the receiver's DTN GATT server was gone (app
     * disconnected but Bluetooth still on).
     *
     * Timeout is generous but bounded ([BleLinkRules.WRITE_ACK_MS]) so we don't hang forever on
     * an unresponsive peer.
     */
    @Suppress("DEPRECATION")
    private suspend fun writeChunkAndWait(
        link: GattLink,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        if (link.closed) return false
        val waiter = CompletableDeferred<Boolean>()
        link.writeAck = waiter
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                link.gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = data
                link.gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!queued) {
            link.writeAck = null
            return false
        }
        // Real end-to-end confirmation: we ONLY report success if the peer ATT-acked.
        val acked = withTimeoutOrNull(BleLinkRules.WRITE_ACK_MS) { waiter.await() } ?: false
        link.writeAck = null
        return acked
    }

    private enum class Indicated { OK, NOT_SENT, NO_CONFIRMATION }

    /**
     * The framed bundle to a central that is linked to us, as indications of MTU − 3 bytes, one in
     * flight: each waits for the central's ATT confirmation (`onNotificationSent`), the hop-level
     * acknowledgement, like a write with response the other way.
     */
    @Suppress("DEPRECATION")
    private suspend fun indicateFramed(address: String, device: BluetoothDevice, framed: ByteArray): Indicated = indicateLock.withLock {
        val server = gattServer ?: return@withLock Indicated.NOT_SENT
        val characteristic = server.getService(SERVICE_UUID)?.getCharacteristic(LINK_CHAR_UUID)
            ?: return@withLock Indicated.NOT_SENT
        for (chunk in BleFraming.chunks(framed, BleFraming.chunkFor(serverLinks.mtuOf(address)))) {
            val waiter = CompletableDeferred<Boolean>()
            indicationAcks[address] = waiter
            val queued = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, true, chunk) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value = chunk
                    server.notifyCharacteristicChanged(device, characteristic, true)
                }
            }.getOrDefault(false)
            if (!queued) {
                indicationAcks.remove(address, waiter)
                return@withLock Indicated.NOT_SENT
            }
            val confirmed = withTimeoutOrNull(BleLinkRules.INDICATION_ACK_MS) { waiter.await() }
            indicationAcks.remove(address, waiter)
            when (confirmed) {
                true -> Unit
                false -> return@withLock Indicated.NOT_SENT // the stack reported a failure (link going down)
                null -> return@withLock Indicated.NO_CONFIRMATION
            }
        }
        Indicated.OK
    }

    /**
     * MTU answered (or never will): discover the peer's services afresh, once per link. The cached
     * table is dropped first: a peer that restarted its GATT server (or got this build, with the
     * link characteristic) has new handles, and a stale table made every write fail with status 1
     * (field log, 20:05:00).
     */
    private fun startDiscovery(link: GattLink) {
        if (link.closed || !link.discoveryStarted.compareAndSet(false, true)) return
        val refreshed = refreshCache(link.gatt)
        val discover = {
            if (!link.closed && !runCatching { link.gatt.discoverServices() }.getOrDefault(false)) {
                connectFailed(link, "service discovery didn't start")
            }
        }
        val sc = adapterScope
        if (refreshed && sc != null) sc.launch { delay(REFRESH_SETTLE_MS); discover() } else discover()
    }

    /** The hidden `BluetoothGatt.refresh()` (an unsupported but allowed API): forgets this peer's cached GATT table. */
    private fun refreshCache(gatt: BluetoothGatt): Boolean = runCatching {
        gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean ?: false
    }.getOrDefault(false)

    /** Subscribe to the peer's indications (its bundles to us): the CCCD write; [gattClientCallback] goes on. */
    @Suppress("DEPRECATION")
    private fun subscribe(link: GattLink, characteristic: BluetoothGattCharacteristic): Boolean {
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return false
        if (!runCatching { link.gatt.setCharacteristicNotification(characteristic, true) }.getOrDefault(false)) return false
        val value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                link.gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                link.gatt.writeDescriptor(cccd)
            }
        }.getOrDefault(false)
    }

    /** HELLO: tell the peer's server which phone this link is, so it can send us its bundles. */
    @Suppress("DEPRECATION")
    private fun writeHello(link: GattLink): Boolean {
        val characteristic = link.gatt.getService(SERVICE_UUID)?.getCharacteristic(LINK_CHAR_UUID) ?: return false
        val hello = BleHello.encode(identity.nodeNum32)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                link.gatt.writeCharacteristic(characteristic, hello, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = hello
                link.gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    /** An indication from the peer on our client link: a chunk of one of its bundles. */
    private fun onIndication(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != LINK_CHAR_UUID) return
        val link = linkFor(gatt) ?: return
        val now = System.currentTimeMillis()
        peerLastSeenMs[link.node] = now
        val bad = link.rx.badFrames
        val frames = link.rx.accept(value, now)
        if (link.rx.badFrames != bad) Log.w(TAG, "BLE reassembly: bad frame length ${link.rx.lastBadLength} from ${link.node8} — resetting buffer")
        for (wire in frames) deliverInbound(wire, gatt.device, NodeId(link.node))
    }

    /**
     * Every callback first finds its own link through its GATT object: a callback from a GATT
     * object we already closed or replaced finds nothing and can't touch the current link (the
     * address-keyed maps before Oct 2026 let an old object's failure close or orphan the new one).
     *
     * The link comes up in steps, each started by the previous one's callback: connected → MTU →
     * discovery → subscribe (CCCD) → HELLO → ready. A peer without the link characteristic (an older
     * app) gets a write-only link after discovery.
     */
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = linkFor(gatt)
            if (link == null) {
                // Not (or no longer) a link of ours: make sure it lets go of the radio.
                if (newState != BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Some stacks report CONNECTED twice for one connect: one MTU request per link (the
                // field log had two "MTU: no answer" lines for every connect).
                if (!link.connectedSeen.compareAndSet(false, true)) return
                // Successful GATT connection is a strong liveness signal — refresh so the
                // sweeper doesn't declare this peer offline while we're mid-transfer.
                peerLastSeenMs[link.node] = System.currentTimeMillis()
                val asked = runCatching { gatt.requestMtu(REQUEST_MTU) }.getOrDefault(false)
                val sc = adapterScope
                if (!asked || sc == null) {
                    startDiscovery(link)
                    return
                }
                sc.launch {
                    // Some stacks never answer the MTU request: go on with 20-byte chunks.
                    delay(BleLinkRules.MTU_WAIT_MS)
                    if (!link.mtuDone && !link.closed) {
                        diagnostics.d(DiagSource.BLE, "MTU: no answer from ${link.node8} in ${BleLinkRules.MTU_WAIT_MS / 1000} s, " +
                            "using $DEFAULT_CHUNK-byte chunks")
                        startDiscovery(link)
                    }
                }
                return
            }
            // Disconnected, or a connect that failed (133, 8, 62, …).
            // IMPORTANT: do NOT mark the peer offline here. This callback fires for a transient
            // connect failure (status=133 is common under BLE load) — that doesn't mean the peer
            // left. Peer liveness comes from scan advertisements; the scan-silence staleness
            // sweeper (startStalenessSweeper) is the sole authority on marking a peer offline.
            link.lastStatus = status
            if (!link.ready) {
                connectFailed(link, if (status != BluetoothGatt.GATT_SUCCESS) BleLinkRules.statusName(status) else "closed while connecting")
                return
            }
            closeLink(link)
            noLinkSince[link.node] = System.currentTimeMillis()
            Log.d(TAG, "GATT client link to ${link.node8} closed (status=$status) — liveness unaffected")
            val line = "Link down with ${link.node8} (we had connected, we were central): ${BleLinkRules.statusName(status)}"
            if (status != BluetoothGatt.GATT_SUCCESS) diagnostics.w(DiagSource.BLE, line) else diagnostics.i(DiagSource.BLE, line)
            publishLinkInfo()
            // Back up when the role rule says so: the lower id at once, the higher id after its wait.
            maybeConnect(link.node)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = linkFor(gatt) ?: return
            val first = !link.mtuDone
            link.mtuDone = true
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.mtu = mtu
                link.chunk = BleFraming.chunkFor(mtu)
            } else if (first) {
                diagnostics.d(DiagSource.BLE, "MTU with ${link.node8}: status $status, using $DEFAULT_CHUNK-byte chunks")
            }
            startDiscovery(link)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = linkFor(gatt) ?: return
            // Once per link (a cache refresh can make a stack report a discovery twice).
            if (!link.servicesHandled.compareAndSet(false, true)) return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(SERVICE_UUID) else null
            if (service?.getCharacteristic(BUNDLE_CHAR_UUID) == null) {
                if (status != BluetoothGatt.GATT_SUCCESS) link.lastStatus = status
                connectFailed(link, if (status != BluetoothGatt.GATT_SUCCESS) "discovery: ${BleLinkRules.statusName(status)}"
                    else "no DTN service on it")
                return
            }
            val linkChar = service.getCharacteristic(LINK_CHAR_UUID)
            if (linkChar == null) {
                // An older app (before fix 3): write-only, and it connects to us for its own bundles.
                olderApps.add(link.node)
                clientReady(link, bothWays = false, writeOnlyWhy = "its app is older: it connects to us for its own bundles")
                return
            }
            olderApps.remove(link.node)
            if (!subscribe(link, linkChar)) clientReady(link, bothWays = false, writeOnlyWhy = "couldn't subscribe")
            Log.d(TAG, "Services discovered for ${link.node8}: subscribing")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val link = linkFor(gatt) ?: return
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                clientReady(link, bothWays = false, writeOnlyWhy = "subscribe failed: ${BleLinkRules.statusName(status)}")
                return
            }
            // Subscribed: now tell its server which phone this link is.
            if (!writeHello(link)) clientReady(link, bothWays = false, writeOnlyWhy = "HELLO not sent")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val link = linkFor(gatt) ?: return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (characteristic.uuid == LINK_CHAR_UUID) {
                // The HELLO's answer: both ways from now on (write-only if its server refused it).
                clientReady(link, bothWays = ok, writeOnlyWhy = if (ok) "" else "HELLO refused: ${BleLinkRules.statusName(status)}")
                return
            }
            // Complete the pending waiter for this link — this is the real end-to-end
            // confirmation. status != GATT_SUCCESS means the peer rejected the write
            // (e.g. no matching characteristic → their DTN service is gone) or the link
            // died mid-write. Either way it's a real "not delivered" signal.
            link.writeAck?.complete(ok)
            if (!ok) {
                Log.w(TAG, "onCharacteristicWrite FAILED status=$status for ${link.node8}")
                diagnostics.w(DiagSource.BLE, "Write to ${link.node8} failed: ${BleLinkRules.statusName(status)}")
            }
        }

        // Android 13+: the value comes with the callback.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onIndication(gatt, characteristic, value)
        }

        // Android 12 and older call only this one.
        @Deprecated("Replaced by the overload with the value on Android 13+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onIndication(gatt, characteristic, characteristic.value ?: return)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun nodeFor(address: String): NodeId? =
        deviceForNode.entries.firstOrNull { it.value.address == address }?.key?.let { NodeId(it) }

    private fun readInt32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
