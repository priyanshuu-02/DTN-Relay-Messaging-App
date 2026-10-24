package com.dtn.mesh.service

import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.EnvelopeService
import com.dtn.mesh.crypto.Fragmenter
import com.dtn.mesh.database.dao.ChatDao
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.receiver.InternetTransportAdapter
import com.dtn.mesh.routing.AlgorithmCode
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.settings.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for creating outgoing messages. It seals the plaintext end-to-end
 * ([EnvelopeService]), records the plaintext in the user-visible chat log ([ChatDao]), and hands
 * the opaque bundle to the store-carry-forward buffer ([MessageQueueManager]). The orchestrator
 * and transports below this layer only ever see ciphertext + routing header.
 */
@Singleton
class OutboundMessageService @Inject constructor(
    private val identity: DtnIdentity,
    private val envelope: EnvelopeService,
    private val queueManager: MessageQueueManager,
    private val chatDao: ChatDao,
    private val internet: InternetTransportAdapter,
    private val lifecycle: MessageLifecycleLog,
    private val strategySelector: StrategySelector,
    private val settings: SettingsRepository,
    private val contactDao: ContactDao,
    private val benchmark: com.dtn.mesh.learning.BenchmarkTracker,
) {
    private companion object {
        /** Lifetime of an announce broadcast to everyone (Discover on). */
        const val BROADCAST_ANNOUNCE_TTL_MS = 10 * 60_000L
        /** Lifetime of a direct announce to one contact; one is sent at most this often per contact. */
        const val DIRECT_ANNOUNCE_TTL_MS = 30 * 60_000L
    }

    /** Contact node id → when we last queued our direct announce to them. */
    private val lastDirectAnnounceMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    sealed interface Result {
        data class Sent(val message: DtnMessage) : Result
        /**
         * Kept in the chat but not sealed or sent: the QR-verified contact's keys changed. It goes
         * out once the user accepts the new keys or re-scans their QR ([resendHeld]).
         */
        data class Held(val chatId: String) : Result
        /** No encryption key for this peer/group yet (they haven't announced / you haven't joined). */
        object NoKey : Result
        data class TooLong(val maxBytes: Int) : Result
        object Error : Result
    }

    /**
     * Seal + enqueue a text message.
     * @param dest peer node id for a DM, or [NodeId.BROADCAST] for broadcast/group.
     * @param channel 0 for DM/broadcast, >0 for a group channel.
     */
    suspend fun sendText(text: String, dest: NodeId, channel: Int, ttlMs: Long): Result {
        val now = System.currentTimeMillis()
        val peer = if (channel > 0 || dest == NodeId.BROADCAST) null else dest.value
        if (peer != null && runCatching { settings.hasPendingKeys(peer) }.getOrDefault(false)) return hold(text, dest, now)
        return sealAndQueue(text, dest, channel, ttlMs, existingId = null, chatCreatedAtMs = now)
    }

    /**
     * Seal and queue the DMs to [peer] that waited for its key change to be resolved (the user
     * accepted the new keys or re-scanned the QR), each under its own chat id. @return how many went out.
     */
    suspend fun resendHeld(peer: NodeId, ttlMs: Long): Int {
        var sent = 0
        for (row in runCatching { chatDao.getHeld(peer.value) }.getOrDefault(emptyList())) {
            val r = sealAndQueue(row.text, peer, 0, ttlMs, existingId = row.id, chatCreatedAtMs = row.createdAtMs)
            if (r is Result.Sent) sent++
        }
        return sent
    }

    /** Store a DM as HELD: in the chat (Queued), not sealed, not in the buffer. */
    private suspend fun hold(text: String, dest: NodeId, now: Long): Result {
        val plain = AlgorithmCode.prefix(strategySelector.activeType, text.toByteArray(Charsets.UTF_8))
        val budget = envelope.maxPlaintextBytes(dest, 0)
        val fragments = (plain.size + budget - 1) / budget
        if (fragments > Fragmenter.MAX_FRAGMENTS) return Result.TooLong(budget * Fragmenter.MAX_FRAGMENTS)
        // The id it will travel under: a plain id, or the canonical parent id of its fragments.
        val id = if (fragments <= 1) UUID.randomUUID().toString() else Fragmenter.canonicalParent(UUID.randomUUID().toString())
        chatDao.upsert(ChatMessageEntity(id, dest.value, dest.value, 0, ChatMessageEntity.Direction.OUT, text,
            ChatMessageEntity.Status.HELD, now))
        return Result.Held(id)
    }

    /**
     * Seal + enqueue [text]. [existingId] re-uses a held message's chat id (it was never on the
     * wire); [chatCreatedAtMs] keeps its place in the chat. The bundle's lifetime starts now.
     */
    private suspend fun sealAndQueue(
        text: String, dest: NodeId, channel: Int, ttlMs: Long, existingId: String?, chatCreatedAtMs: Long,
    ): Result {
        val now = System.currentTimeMillis()
        val isBroadcast = dest == NodeId.BROADCAST
        val convKey = when {
            channel > 0 -> "grp:$channel"
            isBroadcast -> NodeId.BROADCAST.value
            else -> dest.value
        }
        val peer = if (channel > 0 || isBroadcast) null else dest.value
        // A DM starts with one byte naming the routing algorithm selected here, inside the
        // encryption, so the receiver's Message info can show it ([AlgorithmCode]).
        val plain = if (peer != null) AlgorithmCode.prefix(strategySelector.activeType, text.toByteArray(Charsets.UTF_8))
        else text.toByteArray(Charsets.UTF_8)
        val budget = envelope.maxPlaintextBytes(dest, channel)

        // ── Single frame (the common case) ──
        if (plain.size <= budget) {
            val id = existingId ?: UUID.randomUUID().toString()
            val (payload, flags) = when (val s = envelope.sealBytes(plain, id, identity.nodeId, dest, channel, 0, 1)) {
                is EnvelopeService.Sealed.Ok -> s.payload to s.flags
                EnvelopeService.Sealed.NoKey -> return Result.NoKey
                is EnvelopeService.Sealed.TooLong -> return Result.TooLong(s.maxBytes)
            }
            val msg = DtnMessage(
                id = id, originNodeId = identity.nodeId, destinationNodeId = dest, payloadBytes = payload,
                createdAtMs = now, ttlMs = ttlMs, messageType = DtnMessageType.DATA, channel = channel, flags = flags,
            )
            // DMs await an end-to-end receipt (SENDING → DELIVERED); broadcast/group have no single
            // receipt, so they are shown as SENT once queued/flooded.
            val status = if (channel > 0 || isBroadcast) ChatMessageEntity.Status.SENT else ChatMessageEntity.Status.SENDING
            chatDao.upsert(ChatMessageEntity(id, convKey, peer, channel, ChatMessageEntity.Direction.OUT, text, status, chatCreatedAtMs))
            val stored = queueManager.ingest(msg)
            if (stored) {
                journalCreated(msg, fragments = 1)
                bridgeToRelay(msg)
                // Benchmarks: a DM this phone created is one it handles.
                if (peer != null) benchmark.recordHandled(strategySelector.name, msg.id, msg.createdAtMs)
            }
            return if (stored) Result.Sent(msg) else Result.Error
        }

        // ── Fragment: split into independently-sealed bundles that share a canonical parent id ──
        val chunks = plain.toList().chunked(budget).map { it.toByteArray() }
        if (chunks.size > Fragmenter.MAX_FRAGMENTS) return Result.TooLong(budget * Fragmenter.MAX_FRAGMENTS)
        val canonical = existingId ?: Fragmenter.canonicalParent(UUID.randomUUID().toString())
        val total = chunks.size
        var first: DtnMessage? = null
        for ((i, chunk) in chunks.withIndex()) {
            val fragId = Fragmenter.fragmentId(canonical, i)
            val (payload, flags) = when (val s = envelope.sealBytes(chunk, fragId, identity.nodeId, dest, channel, i, total)) {
                is EnvelopeService.Sealed.Ok -> s.payload to s.flags
                EnvelopeService.Sealed.NoKey -> return Result.NoKey
                is EnvelopeService.Sealed.TooLong -> return Result.TooLong(s.maxBytes)
            }
            val msg = DtnMessage(
                id = fragId, originNodeId = identity.nodeId, destinationNodeId = dest, payloadBytes = payload,
                createdAtMs = now, ttlMs = ttlMs, messageType = DtnMessageType.DATA, channel = channel,
                fragmentIndex = i, fragmentTotal = total, flags = flags,
            )
            if (queueManager.ingest(msg)) {
                // Fragments share the first 8 id characters, so the journal tracks them as one.
                if (first == null) journalCreated(msg, fragments = total)
                bridgeToRelay(msg)
                // Benchmarks count bundles: each fragment is delivered (and acknowledged) on its own.
                if (peer != null) benchmark.recordHandled(strategySelector.name, msg.id, msg.createdAtMs)
            }
            if (first == null) first = msg
        }
        // One chat row for the whole message, keyed by the canonical parent id (status SENT: a
        // multi-fragment message is acknowledged per fragment, not tracked as one delivery).
        chatDao.upsert(ChatMessageEntity(canonical, convKey, peer, channel, ChatMessageEntity.Direction.OUT, text,
            ChatMessageEntity.Status.SENT, chatCreatedAtMs))
        return first?.let { Result.Sent(it) } ?: Result.Error
    }

    /**
     * Publish our signed identity (name + keys) so peers can encrypt to us (TOFU).
     *
     * - Discover on: broadcast to everyone — nearby phones, hubs and the internet relay.
     * - Discover off: a direct announce to each contact in the chat list, at most once per
     *   [DIRECT_ANNOUNCE_TTL_MS] per contact ([force] sends one now, e.g. after a name change).
     *   Nobody else learns who we are; people add us by scanning our QR.
     */
    suspend fun sendAnnounce(displayName: String, force: Boolean = false) {
        if (runCatching { settings.discoverableOnce() }.getOrDefault(false)) {
            val msg = buildAnnounce(displayName, NodeId.BROADCAST, BROADCAST_ANNOUNCE_TTL_MS)
            queueManager.ingest(msg)
            bridgeToRelay(msg) // propagate our keys over the internet relay too (if connected)
            return
        }
        val now = System.currentTimeMillis()
        for (node in directAnnounceTargets()) {
            if (!force && now - (lastDirectAnnounceMs[node] ?: 0L) < DIRECT_ANNOUNCE_TTL_MS) continue
            sendAnnounceTo(NodeId(node), displayName)
        }
    }

    /**
     * Right after this phone switched to its derived node id (decision 14): a direct announce to
     * every added contact now, plus the broadcast one when Discover is on. @return contacts announced to.
     */
    suspend fun announceIdentityChange(displayName: String): Int {
        val targets = directAnnounceTargets()
        targets.forEach { sendAnnounceTo(NodeId(it), displayName) }
        if (runCatching { settings.discoverableOnce() }.getOrDefault(false)) {
            val msg = buildAnnounce(displayName, NodeId.BROADCAST, BROADCAST_ANNOUNCE_TTL_MS)
            queueManager.ingest(msg)
            bridgeToRelay(msg)
        }
        return targets.size
    }

    /** Added contacts that get our direct announce (never blocked ones). */
    private suspend fun directAnnounceTargets(): List<String> = ContactRules.directAnnounceTargets(
        added = runCatching { contactDao.getAddedContacts() }.getOrDefault(emptyList()).map { it.nodeId },
        blocked = runCatching { settings.blockedNodesOnce() }.getOrDefault(emptySet()),
        me = identity.nodeId.value,
    )

    /**
     * Queue our signed identity for one contact only (just added them, or Discover is off). It
     * travels like a DM: carriers pass it on but only [peer] pins the keys. Never to a blocked contact.
     */
    suspend fun sendAnnounceTo(peer: NodeId, displayName: String) {
        if (peer == identity.nodeId) return
        if (peer.value in runCatching { settings.blockedNodesOnce() }.getOrDefault(emptySet())) return
        lastDirectAnnounceMs[peer.value] = System.currentTimeMillis()
        queueManager.ingest(buildAnnounce(displayName, peer, DIRECT_ANNOUNCE_TTL_MS))
    }

    private fun buildAnnounce(displayName: String, dest: NodeId, ttlMs: Long): DtnMessage {
        val payload = Announce.build(
            nodeNum = identity.nodeNum32,
            xPub = identity.xPub, edPub = identity.edPub, edSeed = identity.edSeed,
            name = displayName, issuedAtSec = (System.currentTimeMillis() / 1000L).toInt(),
        )
        return DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = dest,
            payloadBytes = payload,
            createdAtMs = System.currentTimeMillis(),
            ttlMs = ttlMs,
            messageType = DtnMessageType.ANNOUNCE,
        )
    }

    /**
     * Broadcasts, group messages and announces are fanned out over the relay once, by the origin,
     * so every relay subscriber gets them immediately. DMs are NOT published here: the
     * orchestrator's flush delivers them to the destination's node topic when it is present on
     * the relay, keeps them until the end-to-end receipt returns, and retries otherwise.
     */
    private fun bridgeToRelay(msg: DtnMessage) {
        if (msg.destinationNodeId != NodeId.BROADCAST) return
        internet.publishBroadcast(msg) {
            // Control traffic (announces) stays out of the message journal.
            if (msg.messageType == DtnMessageType.DATA) {
                lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                    origin = msg.originNodeId.value, dest = "BROADCAST",
                    extra = "${if (msg.channel > 0) "group message" else "broadcast"} published to everyone on the internet relay")
                lifecycle.recordRelayPublish(msg.id, "Internet relay")
            }
        }
    }

    /** Journal a message we just created: what it is, that it's queued, and the route record. */
    private fun journalCreated(m: DtnMessage, fragments: Int) {
        val dest = m.destinationNodeId
        val multiParty = dest == NodeId.BROADCAST
        val kind = when {
            m.channel > 0 -> "group message (encrypted with the group key)"
            multiParty -> "public broadcast (not encrypted)"
            else -> "direct message (end-to-end encrypted)"
        }
        lifecycle.log(LifecycleEvent.CREATED, m.id,
            origin = m.originNodeId.value, dest = dest.value, ttlRemainingMs = m.ttlMs,
            extra = kind + if (fragments > 1) " · $fragments fragments" else "")
        lifecycle.log(LifecycleEvent.BUFFERED, m.id,
            origin = m.originNodeId.value, dest = dest.value,
            extra = if (multiParty) "queued — goes to everyone this phone meets until it expires"
            else "queued — waiting for a link toward the recipient")
        lifecycle.recordAsOrigin(m.id, source = m.originNodeId.value, dest = dest.value,
            me = m.originNodeId.value, channel = m.channel)
    }
}
