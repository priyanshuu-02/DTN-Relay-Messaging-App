@file:SuppressLint("MissingPermission")

package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Action
import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Group
import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Role
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Wi-Fi Direct transport: DTN phones in range form ONE Wi-Fi Direct group and exchange bundles
 * over TCP inside it — any number of phones, no invitation pop-ups.
 *
 * ## How phones link (Android 10+)
 * - One phone owns the group (a mini hotspot, `DIRECT-DT-<node>`). Its name and password are
 *   advertised in this phone's BLE advert ([WifiDirectHints]) and in a Wi-Fi P2P DNS-SD record
 *   (`_dtnmesh._tcp`, TXT `id`, `g`, `k`, `n`).
 * - Every other DTN phone joins it with that name + password ([WifiP2pConfig.Builder]): like
 *   joining a known Wi-Fi network, so nobody has to accept anything. The old "connect to a
 *   device" flow sent invitations that had to be accepted on the other phone (and reached TVs).
 * - Who owns the group is decided by [WifiDirectGroupPolicy] (lowest node id; empty groups merge).
 *
 * ## Inside the group (TCP :9734)
 * Frames: `[4B wireLength][wire bytes][4B idLen][sender node id]`.
 * - wireLength 0 = HELLO (identity only; learns node id ↔ IP and proves liveness).
 * - wireLength −2 = ROSTER, `[2B len][UTF-8 "hex@ip,hex@ip,…"]`: the owner lists every member so
 *   members can also reach each other directly. Older builds drop negative lengths.
 * - otherwise a DTN bundle.
 *
 * The chip shows "Linked" once at least one other DTN phone is reachable in the group; the link
 * details sheet lists them ([linkInfo]).
 */
@Singleton
class WifiDirectTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: com.dtn.mesh.model.LocalNodeIdentity,
    private val hints: WifiDirectHints,
) : DtnTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        /** TCP port for DTN message exchange over WiFi Direct. */
        private const val DTN_P2P_PORT = 9734
        private const val SERVICE_TYPE = "_dtnmesh._tcp"
        private const val SERVICE_INSTANCE = "dtnmesh"
        /** Peer + service discovery cadence while looking for a group. */
        private const val DISCOVERY_INTERVAL_MS = 12_000L
        /** Decision loop tick. */
        private const val TICK_MS = 3_000L
        /** Faster tick while a group is forming / being joined, and right after joining. */
        private const val FAST_TICK_MS = 1_000L
        /** After joining, greet the owner every fast tick for this long (its first HELLO often fails). */
        private const val FAST_PHASE_MS = 15_000L
        /** A phone / group not heard for this long is forgotten. */
        private const val SEEN_TTL_MS = 45_000L
        /** Joining or creating a group that hasn't formed by now is abandoned. */
        private const val FORM_TIMEOUT_MS = 30_000L
        /** A group we failed to join is skipped for this long, then tried again. */
        private const val FAILED_GROUP_BACKOFF_MS = 8_000L
        /** No HELLO/DATA from a linked phone for this long → offline. */
        private const val PEER_STALE_THRESHOLD_MS = 30_000L
        /** HELLO heartbeats (and the owner's roster) cadence. */
        private const val HEARTBEAT_MS = 10_000L
        private const val FRAME_HELLO = 0
        private const val FRAME_ROSTER = -2
        /** Packet ID counter for WiFi Direct sends (local). */
        private var packetIdCounter = 2_000_000

        /** Joining / creating a group by name + password ([WifiP2pConfig.Builder]) needs Android 10. */
        val SUPPORTED: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** ROSTER text `hex@ip,hex@ip` → (node id → ip). Pure (tested). */
        fun parseRoster(text: String): Map<String, String> = text.split(',').mapNotNull { item ->
            val hex = item.substringBefore('@', "").trim().lowercase()
            val ip = item.substringAfter('@', "").trim()
            if (hex.length == 8 && hex.all { it in '0'..'9' || it in 'a'..'f' } && ip.isNotEmpty()) "!$hex" to ip else null
        }.toMap()

        fun encodeRoster(members: Map<String, String>): String =
            members.entries.joinToString(",") { (id, ip) -> "${id.removePrefix("!")}@$ip" }
    }

    override val transportName: String = "WiFi Direct"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val myHex: String get() = identity.nodeId.value.removePrefix("!")

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /** True between [connect] and [disconnect] (survives Wi-Fi off/on). */
    @Volatile private var running = false
    /** Last P2P on/off state seen (null = not yet known). */
    @Volatile private var p2pEnabled: Boolean? = null

    // ── Group state ──────────────────────────────────────────────────────
    @Volatile private var role = Role.SEARCHING
    @Volatile private var roleSinceMs = 0L
    /** Owner of the group we're in / joining (node hex), or ours when we own it. */
    @Volatile private var groupOwnerHex: String? = null
    @Volatile private var groupName: String? = null
    @Volatile private var groupPassphrase: String? = null
    /** Nonce of the group WE own (derives its password); null if we don't own one we created. */
    @Volatile private var ownNonce: Int? = null
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var waitingSinceMs: Long? = null
    @Volatile private var idleSinceMs: Long? = null
    private val failedGroups = ConcurrentHashMap<String, Long>()
    /** Groups / phones heard via Wi-Fi P2P service discovery (node hex → …). */
    private val sdGroups = ConcurrentHashMap<String, Group>()
    private val sdPhones = ConcurrentHashMap<String, Long>()

    // ── Links inside the group ───────────────────────────────────────────
    /** Linked DTN phones: node id → IP inside the group. */
    private val connectedPeers = ConcurrentHashMap<String, String>()
    private val peerLastSeenMs = ConcurrentHashMap<String, Long>()
    private val peerRoles = ConcurrentHashMap<String, String>()

    private var serverSocket: ServerSocket? = null
    private var loopJob: Job? = null
    @Volatile private var lastDiscoveryMs = 0L
    @Volatile private var lastHeartbeatMs = 0L
    @Volatile private var rosterDirty = false
    @Volatile private var advertisedTxt: Map<String, String>? = null

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val _linkInfo = MutableStateFlow(LinkInfo(summary = if (SUPPORTED) "Off" else "Needs Android 10 or newer"))
    /** Group role + every linked phone, for the link details sheet. */
    val linkInfo: StateFlow<LinkInfo> = _linkInfo.asStateFlow()

    // Single stable identity shared with the BLE and LoRa-hub transports.
    override val localNodeId: NodeId get() = identity.nodeId

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    /** Check if a specific peer is reachable via WiFi Direct. */
    fun isPeerReachable(peerId: NodeId): Boolean = connectedPeers.containsKey(peerId.value)

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        if (!SUPPORTED) {
            Log.w(TAG, "Wi-Fi Direct groups need Android 10+ — not starting")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            return false
        }
        if (running) return true
        val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            Log.e(TAG, "WiFi P2P not supported on this device")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            return false
        }
        Log.i(TAG, "Starting Wi-Fi Direct (groups, no invitations)")
        manager = m
        channel = m.initialize(context, context.mainLooper) { Log.w(TAG, "Wi-Fi P2P channel lost") }
        return try {
            running = true
            p2pEnabled = null
            setRole(Role.SEARCHING)
            registerP2pReceivers()
            // Clean slate: a stale connect attempt or a group left over from an earlier run.
            runCatching { m.cancelConnect(channel, null) }
            runCatching { m.removeGroup(channel, null) }
            m.setDnsSdResponseListeners(channel, { _, _, _ -> }, { domain, txt, _ ->
                if (domain.contains(SERVICE_TYPE)) onServiceTxt(txt)
            })
            advertise()
            startServerSocket()
            loopJob = scope.launch { decisionLoop() }
            refreshState()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi Direct permission missing: ${e.message}")
            disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi Direct startup failed: ${e.message}", e)
            disconnect()
            false
        }
    }

    override fun disconnect() {
        Log.i(TAG, "Stopping Wi-Fi Direct")
        running = false
        runCatching { loopJob?.cancel() }; loopJob = null
        dropGroupPeers()
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.stopPeerDiscovery(ch, null) }
            runCatching { m.clearServiceRequests(ch, null) }
            runCatching { m.clearLocalServices(ch, null) }
            runCatching { m.cancelConnect(ch, null) }
            // Leave the group too, so the link really ends when the user turns this off.
            runCatching { m.removeGroup(ch, null) }
        }
        unregisterP2pReceivers()
        runCatching { serverSocket?.close() }; serverSocket = null
        hints.setLocal(WifiDirectHints.Local())
        hints.clear()
        sdGroups.clear(); sdPhones.clear(); failedGroups.clear()
        advertisedTxt = null
        clearGroup()
        role = Role.SEARCHING
        _connectionState.value = MeshConnectionState.DISCONNECTED
        publishLinkInfo()
    }

    private fun setRole(r: Role) {
        if (role != r) Log.i(TAG, "Role: $role → $r")
        role = r
        roleSinceMs = System.currentTimeMillis()
        if (r != Role.SEARCHING) waitingSinceMs = null
        if (r != Role.OWNER) idleSinceMs = null
    }

    private fun clearGroup() {
        groupOwnerHex = null; groupName = null; groupPassphrase = null; ownNonce = null; groupOwnerAddress = null
    }

    // ── Decision loop ────────────────────────────────────────────────────

    private suspend fun decisionLoop() {
        while (scope.isActive && running) {
            delay(if (inFastPhase(System.currentTimeMillis())) FAST_TICK_MS else TICK_MS)
            runCatching { tick(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "tick failed", it) }
        }
    }

    /** A group is forming or being joined, or we just joined and the owner doesn't know us yet. */
    private fun inFastPhase(now: Long): Boolean = when (role) {
        Role.JOINING, Role.CREATING -> true
        Role.MEMBER -> !ownerLinked() && now - roleSinceMs < FAST_PHASE_MS
        else -> false
    }

    private fun ownerLinked(): Boolean = groupOwnerAddress?.let { it in connectedPeers.values } ?: false

    private fun tick(now: Long) {
        if (p2pEnabled == false) { refreshState(); publishLinkInfo(); return }

        // Look around while we have no group (or own an empty one). Members and owners with
        // members don't scan: discovery makes the radio hop channels and disturbs the group.
        val wantDiscovery = role == Role.SEARCHING || (role == Role.OWNER && connectedPeers.isEmpty())
        if (wantDiscovery && now - lastDiscoveryMs >= DISCOVERY_INTERVAL_MS) {
            lastDiscoveryMs = now
            startDiscovery()
        }

        // A join / create that never formed a group: give up and look again.
        if ((role == Role.JOINING || role == Role.CREATING) && now - roleSinceMs > FORM_TIMEOUT_MS) {
            Log.w(TAG, "${if (role == Role.JOINING) "Joining $groupOwnerHex" else "Creating a group"} timed out")
            groupOwnerHex?.let { if (role == Role.JOINING) failedGroups[it] = now + FAILED_GROUP_BACKOFF_MS }
            runCatching { manager?.cancelConnect(channel, null) }
            if (role == Role.CREATING) runCatching { manager?.removeGroup(channel, null) }
            clearGroup()
            setRole(Role.SEARCHING)
        }

        if (now - lastHeartbeatMs >= HEARTBEAT_MS || rosterDirty) {
            heartbeat(now)
        } else if (role == Role.MEMBER && !ownerLinked() && now - roleSinceMs < FAST_PHASE_MS) {
            // Just joined: keep greeting the owner every fast tick instead of every 10 s, so the
            // link comes up as soon as the group interface has its address.
            groupOwnerAddress?.let { ip -> scope.launch { sendHello(ip) } }
        }

        val groups = freshGroups(now)
        val phones = freshPhones(now, groups)
        if (role == Role.SEARCHING) {
            waitingSinceMs = if (phones.any { it < myHex }) (waitingSinceMs ?: now) else null
        }
        if (role == Role.OWNER) {
            idleSinceMs = if (connectedPeers.isEmpty()) (idleSinceMs ?: now) else null
        }
        when (val a = WifiDirectGroupPolicy.decide(myHex, role, now, connectedPeers.size, groups, phones, waitingSinceMs, idleSinceMs)) {
            Action.None -> Unit
            is Action.Join -> join(a.group)
            Action.Create -> createGroup()
            is Action.LeaveAndJoin -> {
                Log.i(TAG, "Merging: leaving our empty group for ${a.group.ownerHex}'s")
                leaveGroup { join(a.group) }
            }
            Action.Disband -> {
                Log.i(TAG, "Nobody joined our group — giving it up")
                leaveGroup { }
            }
        }
        advertise()
        refreshState()
        publishLinkInfo()
    }

    /** DTN groups nearby: from BLE hints (derived credentials) and service discovery (explicit). */
    private fun freshGroups(now: Long): List<Group> {
        failedGroups.entries.removeAll { it.value < now }
        val fromBle = hints.recent(now, SEEN_TTL_MS).filter { it.active && it.owner }.map {
            Group(it.nodeHex, WifiDirectHints.networkName(it.nodeHex), WifiDirectHints.passphrase(it.nodeHex, it.nonce), it.members, it.seenAtMs)
        }
        val fromSd = sdGroups.values.filter { now - it.seenAtMs <= SEEN_TTL_MS }
        return (fromSd + fromBle)
            .groupBy { it.ownerHex }
            .map { (_, list) -> list.maxBy { it.seenAtMs } }
            .filter { it.ownerHex != myHex && !failedGroups.containsKey(it.ownerHex) && it.ownerHex != groupOwnerHex }
    }

    /** DTN phones nearby running Wi-Fi Direct that own no group (election candidates). */
    private fun freshPhones(now: Long, groups: List<Group>): List<String> {
        val owners = groups.map { it.ownerHex }.toSet()
        val fromBle = hints.recent(now, SEEN_TTL_MS).filter { it.active && !it.owner }.map { it.nodeHex }
        val fromSd = sdPhones.filter { now - it.value <= SEEN_TTL_MS }.keys
        return (fromBle + fromSd).distinct().filter { it != myHex && it !in owners }
    }

    private fun join(group: Group) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.stopPeerDiscovery(ch, null) }
        setRole(Role.JOINING)
        groupOwnerHex = group.ownerHex
        groupName = group.networkName
        groupPassphrase = group.passphrase
        ownNonce = null
        val config = WifiP2pConfig.Builder()
            .setNetworkName(group.networkName)
            .setPassphrase(group.passphrase)
            .enablePersistentMode(false)
            .build()
        Log.i(TAG, "Joining ${group.ownerHex}'s group ${group.networkName} (${group.members} member(s))")
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Join request accepted by the framework") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Joining ${group.ownerHex} failed: reason=$reason")
                failedGroups[group.ownerHex] = System.currentTimeMillis() + FAILED_GROUP_BACKOFF_MS
                if (role == Role.JOINING) { clearGroup(); setRole(Role.SEARCHING) }
            }
        })
    }

    private fun createGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.stopPeerDiscovery(ch, null) }
        val nonce = Random.nextInt().let { if (it == 0) 1 else it }
        val name = WifiDirectHints.networkName(myHex)
        val pass = WifiDirectHints.passphrase(myHex, nonce)
        setRole(Role.CREATING)
        groupOwnerHex = myHex; groupName = name; groupPassphrase = pass; ownNonce = nonce
        val config = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .build()
        Log.i(TAG, "Creating group $name")
        m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Group creation accepted by the framework") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Creating the group failed: reason=$reason")
                if (role == Role.CREATING) { clearGroup(); setRole(Role.SEARCHING) }
            }
        })
    }

    private fun leaveGroup(then: () -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        dropGroupPeers()
        clearGroup()
        setRole(Role.SEARCHING)
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { then() }
            override fun onFailure(reason: Int) { then() }
        })
    }

    // ── Advertising + discovery ──────────────────────────────────────────

    /** Publish our state over BLE (hint) and DNS-SD (TXT) — re-registered only when it changes. */
    private fun advertise() {
        val members = if (role == Role.OWNER) connectedPeers.size else 0
        val ownsJoinable = role == Role.OWNER && ownNonce != null
        hints.setLocal(WifiDirectHints.Local(active = running, ownerNonce = if (ownsJoinable) ownNonce else null, members = members))

        val txt = buildMap {
            put("v", "2")
            put("id", myHex)
            val name = groupName
            val pass = groupPassphrase
            if (role == Role.OWNER && name != null && pass != null) {
                put("g", name); put("k", pass); put("n", members.toString())
            }
        }
        if (txt == advertisedTxt) return
        val m = manager ?: return
        val ch = channel ?: return
        advertisedTxt = txt
        val info = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, txt)
        m.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = add()
            override fun onFailure(reason: Int) = add()
            private fun add() {
                m.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "Advertised $txt") }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Advertising failed: reason=$reason")
                        advertisedTxt = null // retried next tick
                    }
                })
            }
        })
    }

    private fun startDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { startServiceDiscovery(m, ch) }
            override fun onFailure(reason: Int) {
                // BUSY (2) is normal while a group is forming.
                if (reason == WifiP2pManager.BUSY) Log.d(TAG, "Peer discovery busy") else Log.w(TAG, "Peer discovery failed: reason=$reason")
            }
        })
    }

    /** Ask nearby phones for their DTN records. Re-adding the request each round keeps OEM stacks answering. */
    private fun startServiceDiscovery(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        m.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = addRequest()
            override fun onFailure(reason: Int) = addRequest()
            private fun addRequest() {
                m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        m.discoverServices(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {}
                            override fun onFailure(reason: Int) { Log.d(TAG, "Service discovery not started: reason=$reason") }
                        })
                    }
                    override fun onFailure(reason: Int) { Log.d(TAG, "Service request failed: reason=$reason") }
                })
            }
        })
    }

    private fun onServiceTxt(txt: Map<String, String>) {
        val hex = txt["id"]?.lowercase() ?: return
        if (hex == myHex || txt["v"] == null || txt["v"] == "1") return // v1 builds don't do groups
        val now = System.currentTimeMillis()
        val g = txt["g"]
        val k = txt["k"]
        if (g != null && k != null) {
            sdGroups[hex] = Group(hex, g, k, txt["n"]?.toIntOrNull() ?: 0, now)
            sdPhones.remove(hex)
        } else {
            sdPhones[hex] = now
            sdGroups.remove(hex)
        }
    }

    // ── Links: HELLO heartbeats, roster, staleness ───────────────────────

    private fun heartbeat(now: Long) {
        lastHeartbeatMs = now
        val sendRoster = rosterDirty || role == Role.OWNER
        rosterDirty = false
        // Members keep greeting the owner until it knows us: the first HELLO often fails because
        // the group interface has no address yet.
        val owner = groupOwnerAddress
        if (role == Role.MEMBER && owner != null && owner !in connectedPeers.values) {
            scope.launch { sendHello(owner) }
        }
        for ((peerId, ip) in connectedPeers.toMap()) {
            scope.launch { if (sendHello(ip)) peerLastSeenMs[peerId] = System.currentTimeMillis() }
        }
        if (sendRoster && role == Role.OWNER && connectedPeers.isNotEmpty()) {
            val all = connectedPeers.toMutableMap()
            owner?.let { all[identity.nodeId.value] = it }
            val text = encodeRoster(all)
            for (ip in connectedPeers.values.toSet()) scope.launch { sendFrame(ip, FRAME_ROSTER, text.toByteArray(Charsets.UTF_8)) }
        }
        val stale = peerLastSeenMs.entries.filter { now - it.value > PEER_STALE_THRESHOLD_MS }
        for ((peerId, _) in stale) {
            peerLastSeenMs.remove(peerId)
            connectedPeers.remove(peerId)
            peerRoles.remove(peerId)
            rosterDirty = true
            emitPeer(peerId, online = false)
            Log.d(TAG, "Peer stale (no activity): ${peerId.takeLast(8)}")
        }
    }

    /** Every phone reached over the (former) group goes offline. */
    private fun dropGroupPeers() {
        val ids = connectedPeers.keys.toList()
        connectedPeers.clear()
        peerLastSeenMs.clear()
        peerRoles.clear()
        ids.forEach { emitPeer(it, online = false) }
    }

    private fun emitPeer(id: String, online: Boolean) {
        _nodeEvents.tryEmit(NodeEncounterEvent(
            nodeId = NodeId(id),
            rssi = if (online) -30 else 0, snr = if (online) 20f else 0f, // placeholders, not measured
            timestampMs = System.currentTimeMillis(),
            isOnline = online,
            transport = TransportKind.WIFI_DIRECT,
        ))
    }

    private fun refreshState() {
        val linked = (role == Role.OWNER || role == Role.MEMBER) && connectedPeers.isNotEmpty()
        _connectionState.value = when {
            !running -> MeshConnectionState.DISCONNECTED
            linked -> MeshConnectionState.CONNECTED
            else -> MeshConnectionState.CONNECTING // searching, joining, or waiting for Wi-Fi
        }
    }

    private fun publishLinkInfo() {
        val n = connectedPeers.size
        val linked = if (n == 1) "1 phone linked" else "$n phones linked"
        val summary = when {
            !running -> "Off"
            p2pEnabled == false -> "Waiting for Wi-Fi to come back on"
            role == Role.OWNER && n > 0 -> "This phone runs the group · $linked"
            role == Role.OWNER -> "Group ready — waiting for DTN phones to join"
            role == Role.MEMBER && n > 0 -> "In ${groupOwnerHex?.let { "!$it's" } ?: "a"} group · $linked"
            role == Role.MEMBER -> "Joined a group — saying hello to its phones…"
            role == Role.JOINING -> "Joining !${groupOwnerHex.orEmpty()}'s group…"
            role == Role.CREATING -> "Creating a group…"
            else -> "Searching for phones to link…"
        }
        val facts = buildList {
            groupName?.let { add("Group $it") }
            add("DTN phones in range join one group automatically — nobody has to accept an invitation.")
        }
        val peers = connectedPeers.entries.sortedBy { it.key }.map { (id, ip) ->
            LinkPeer(id, role = peerRoles[id] ?: "Member", detail = ip)
        }
        _linkInfo.value = LinkInfo(summary, facts, peers)
    }

    // ── Server socket (receive side) ─────────────────────────────────────

    private fun startServerSocket() {
        scope.launch {
            try {
                serverSocket = ServerSocket(DTN_P2P_PORT)
                Log.i(TAG, "Server listening on port $DTN_P2P_PORT")
                while (serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch { handleIncomingConnection(clientSocket) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server socket error", e)
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        try {
            socket.soTimeout = 5000 // don't pin an IO thread forever on a stalled peer
            val remoteIp = socket.inetAddress?.hostAddress
            val dis = DataInputStream(socket.getInputStream())
            val length = dis.readInt()
            var roster: String? = null
            val wireBytes: ByteArray
            when {
                length == FRAME_ROSTER -> {
                    val n = dis.readUnsignedShort()
                    if (n > 4096) { socket.close(); return }
                    roster = String(ByteArray(n).also { dis.readFully(it) }, Charsets.UTF_8)
                    wireBytes = ByteArray(0)
                }
                length < 0 || length > DtnMessage.LORA_MAX_FRAME_BYTES * 10 -> {
                    Log.w(TAG, "Bad packet length ($length), dropping")
                    socket.close()
                    return
                }
                else -> wireBytes = ByteArray(length).also { if (length > 0) dis.readFully(it) }
            }

            // SECURITY: bound the id length read off the socket (a huge value used to allocate
            // a multi-GB array → OutOfMemoryError, which `catch (Exception)` doesn't catch).
            val senderIdLength = dis.readInt()
            if (senderIdLength < 1 || senderIdLength > 64) {
                Log.w(TAG, "Bad sender id length ($senderIdLength), dropping")
                socket.close()
                return
            }
            val senderId = String(ByteArray(senderIdLength).also { dis.readFully(it) })
            socket.close()
            val fromNode = NodeId(senderId)
            if (fromNode == identity.nodeId || remoteIp == null) return

            // Learn the real id → IP mapping from the live connection; greet back new peers.
            val isNewPeer = !connectedPeers.containsKey(senderId)
            connectedPeers[senderId] = remoteIp
            peerLastSeenMs[senderId] = System.currentTimeMillis()
            peerRoles[senderId] = when {
                role == Role.OWNER -> "Member"
                remoteIp == groupOwnerAddress -> "Group owner"
                else -> "Member"
            }
            if (isNewPeer) {
                if (remoteIp == groupOwnerAddress) groupOwnerHex = senderId.removePrefix("!")
                refreshState() // first: the orchestrator checks reachability when it sees the peer
                emitPeer(senderId, online = true)
                rosterDirty = true
                scope.launch { sendHello(remoteIp) }
                advertise(); publishLinkInfo()
                Log.i(TAG, "Linked with ${senderId.takeLast(8)} at $remoteIp")
            }

            when {
                roster != null -> onRoster(parseRoster(roster))
                wireBytes.isNotEmpty() -> {
                    val msg = DtnWireCodec.decode(wireBytes = wireBytes, rssi = -30, snr = 20f) ?: return
                    // Attribute the immediate sender so reverse-echo suppression / 1-hop summary trust apply.
                    _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = fromNode, transport = TransportKind.WIFI_DIRECT))
                    Log.d(TAG, "Received ${wireBytes.size}B from $senderId via P2P")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling P2P connection", e)
        }
    }

    /** The owner's member list: link up directly with every other member. */
    private fun onRoster(members: Map<String, String>) {
        val me = identity.nodeId.value
        for ((id, ip) in members) {
            if (id == me || connectedPeers.containsKey(id)) continue
            scope.launch {
                if (sendHello(ip) && running && !connectedPeers.containsKey(id)) {
                    connectedPeers[id] = ip
                    peerLastSeenMs[id] = System.currentTimeMillis()
                    peerRoles[id] = if (ip == groupOwnerAddress) "Group owner" else "Member"
                    refreshState()
                    emitPeer(id, online = true)
                    publishLinkInfo()
                    Log.i(TAG, "Linked with ${id.takeLast(8)} at $ip (from the roster)")
                }
            }
        }
    }

    /** HELLO (zero-length bundle) — identity only. True when the write went through. */
    private fun sendHello(peerIp: String): Boolean = sendFrame(peerIp, FRAME_HELLO, ByteArray(0))

    private fun sendFrame(peerIp: String, kind: Int, body: ByteArray): Boolean = try {
        val myId = identity.nodeId.value.toByteArray()
        Socket().use { sock ->
            sock.connect(InetSocketAddress(peerIp, DTN_P2P_PORT), 3000)
            DataOutputStream(sock.getOutputStream()).apply {
                writeInt(kind)
                if (kind == FRAME_ROSTER) { writeShort(body.size); write(body) }
                writeInt(myId.size)
                write(myId)
                flush()
            }
        }
        true
    } catch (_: Exception) { false }

    // ── Send (client side) ───────────────────────────────────────────────

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        val peerIp = connectedPeers[targetPeer.value] ?: return null
        return try {
            val wireBytes = DtnWireCodec.encode(message)
            val myId = identity.nodeId.value.toByteArray()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peerIp, DTN_P2P_PORT), 5000)
                val dos = DataOutputStream(socket.getOutputStream())
                // Protocol: [4 bytes wireLength][wireBytes][4 bytes senderIdLength][senderIdBytes]
                dos.writeInt(wireBytes.size)
                dos.write(wireBytes)
                dos.writeInt(myId.size)
                dos.write(myId)
                dos.flush()
            }
            peerLastSeenMs[targetPeer.value] = System.currentTimeMillis()
            val pktId = packetIdCounter++
            // A direct TCP write inside the group = delivered to that phone.
            _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = pktId, status = DeliveryOutcome.DELIVERED))
            Log.d(TAG, "Sent ${wireBytes.size}B to ${targetPeer.value} at $peerIp")
            pktId
        } catch (e: Exception) {
            Log.e(TAG, "Send failed to ${targetPeer.value}", e)
            null
        }
    }

    // ── WiFi P2P BroadcastReceiver ───────────────────────────────────────

    private var receiversRegistered = false

    private fun registerP2pReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(p2pReceiver, filter)
        receiversRegistered = true
    }

    private fun unregisterP2pReceivers() {
        if (!receiversRegistered) return
        runCatching { context.unregisterReceiver(p2pReceiver) }
        receiversRegistered = false
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!running) return
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val was = p2pEnabled
                    p2pEnabled = enabled
                    if (!enabled) {
                        Log.w(TAG, "Wi-Fi P2P is off — waiting for Wi-Fi")
                        dropGroupPeers()
                        clearGroup()
                        setRole(Role.SEARCHING)
                    } else if (was == false) {
                        // Wi-Fi came back: the framework forgot our local service — advertise again
                        // and resume looking for a group straight away.
                        Log.i(TAG, "Wi-Fi P2P back on — searching again")
                        advertisedTxt = null
                        lastDiscoveryMs = 0L
                        advertise()
                    }
                    refreshState(); publishLinkInfo()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
                }
            }
        }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (!running) return
        val formed = info?.groupFormed == true
        when {
            formed && info!!.isGroupOwner -> {
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                if (role != Role.OWNER) {
                    setRole(Role.OWNER)
                    groupOwnerHex = myHex
                    idleSinceMs = System.currentTimeMillis()
                    Log.i(TAG, "Group up — this phone owns it (${groupName ?: "?"})")
                }
                // A group we didn't create ourselves (e.g. accepted from an older build): learn its
                // name + password from the framework so others can still join it.
                manager?.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                    if (group != null && ownNonce == null) {
                        groupName = group.networkName
                        groupPassphrase = group.passphrase
                    }
                    advertise(); publishLinkInfo()
                }
            }
            formed -> {
                groupOwnerAddress = info!!.groupOwnerAddress?.hostAddress
                if (role != Role.MEMBER) {
                    setRole(Role.MEMBER)
                    Log.i(TAG, "Joined the group — owner at $groupOwnerAddress")
                }
                ownNonce = null
                groupOwnerAddress?.let { ip -> scope.launch { sendHello(ip) } }
                advertise()
            }
            role == Role.OWNER || role == Role.MEMBER -> {
                Log.i(TAG, "The group ended")
                dropGroupPeers()
                clearGroup()
                setRole(Role.SEARCHING)
                lastDiscoveryMs = 0L // look again right away
                advertise()
            }
            // JOINING / CREATING: an intermediate "not formed yet" — the form timeout handles failure.
        }
        refreshState(); publishLinkInfo()
    }
}
