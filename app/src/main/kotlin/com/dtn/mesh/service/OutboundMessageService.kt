package com.dtn.mesh.service

import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.EnvelopeService
import com.dtn.mesh.crypto.Fragmenter
import com.dtn.mesh.database.dao.ChatDao
import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.receiver.InternetTransportAdapter
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
) {
    sealed interface Result {
        data class Sent(val message: DtnMessage) : Result
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
        val isBroadcast = dest == NodeId.BROADCAST
        val convKey = when {
            channel > 0 -> "grp:$channel"
            isBroadcast -> NodeId.BROADCAST.value
            else -> dest.value
        }
        val peer = if (channel > 0 || isBroadcast) null else dest.value
        val plain = text.toByteArray(Charsets.UTF_8)
        val budget = envelope.maxPlaintextBytes(dest, channel)

        // ── Single frame (the common case) ──
        if (plain.size <= budget) {
            val id = UUID.randomUUID().toString()
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
            chatDao.upsert(ChatMessageEntity(id, convKey, peer, channel, ChatMessageEntity.Direction.OUT, text, status, now))
            val stored = queueManager.ingest(msg)
            if (stored) internet.publish(msg) // bridge to the internet relay too (no-op if not connected)
            return if (stored) Result.Sent(msg) else Result.Error
        }

        // ── Fragment: split into independently-sealed bundles that share a canonical parent id ──
        val chunks = plain.toList().chunked(budget).map { it.toByteArray() }
        if (chunks.size > Fragmenter.MAX_FRAGMENTS) return Result.TooLong(budget * Fragmenter.MAX_FRAGMENTS)
        val canonical = Fragmenter.canonicalParent(UUID.randomUUID().toString())
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
            if (first == null) first = msg
            if (queueManager.ingest(msg)) internet.publish(msg)
        }
        // One chat row for the whole message, keyed by the canonical parent id (status SENT: a
        // multi-fragment message is acknowledged per fragment, not tracked as one delivery).
        chatDao.upsert(ChatMessageEntity(canonical, convKey, peer, channel, ChatMessageEntity.Direction.OUT, text, ChatMessageEntity.Status.SENT, now))
        return first?.let { Result.Sent(it) } ?: Result.Error
    }

    /** Broadcast a signed identity announce so peers can learn our keys (TOFU). */
    suspend fun sendAnnounce(displayName: String, ttlMs: Long = 10 * 60_000L): DtnMessage {
        val payload = Announce.build(
            xPub = identity.xPub, edPub = identity.edPub, edSeed = identity.edSeed,
            name = displayName, issuedAtSec = (System.currentTimeMillis() / 1000L).toInt(),
        )
        val msg = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = identity.nodeId,
            destinationNodeId = NodeId.BROADCAST,
            payloadBytes = payload,
            createdAtMs = System.currentTimeMillis(),
            ttlMs = ttlMs,
            messageType = DtnMessageType.ANNOUNCE,
        )
        queueManager.ingest(msg)
        internet.publish(msg) // propagate our keys over the internet relay too (if connected)
        return msg
    }
}
