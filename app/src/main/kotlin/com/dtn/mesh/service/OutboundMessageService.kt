package com.dtn.mesh.service

import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.EnvelopeService
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
        val id = UUID.randomUUID().toString()
        val sealed = envelope.sealText(text, id, identity.nodeId, dest, channel)
        val (payload, flags) = when (sealed) {
            is EnvelopeService.Sealed.Ok -> sealed.payload to sealed.flags
            EnvelopeService.Sealed.NoKey -> return Result.NoKey
            is EnvelopeService.Sealed.TooLong -> return Result.TooLong(sealed.maxBytes)
        }
        val now = System.currentTimeMillis()
        val msg = DtnMessage(
            id = id,
            originNodeId = identity.nodeId,
            destinationNodeId = dest,
            payloadBytes = payload,
            createdAtMs = now,
            ttlMs = ttlMs,
            messageType = DtnMessageType.DATA,
            channel = channel,
            flags = flags,
        )
        val isBroadcast = dest == NodeId.BROADCAST
        val convKey = when {
            channel > 0 -> "grp:$channel"
            isBroadcast -> NodeId.BROADCAST.value
            else -> dest.value
        }
        // DMs await an end-to-end receipt (SENDING → DELIVERED). Broadcast/group have no receipt,
        // so they are shown as SENT immediately once queued/flooded.
        val initialStatus = if (channel > 0 || isBroadcast) {
            ChatMessageEntity.Status.SENT
        } else {
            ChatMessageEntity.Status.SENDING
        }
        chatDao.upsert(
            ChatMessageEntity(
                id = id,
                convKey = convKey,
                peerNodeId = if (channel > 0 || isBroadcast) null else dest.value,
                channel = channel,
                direction = ChatMessageEntity.Direction.OUT,
                text = text,
                status = initialStatus,
                createdAtMs = now,
            ),
        )
        val stored = queueManager.ingest(msg)
        if (stored) internet.publish(msg) // bridge to the internet relay too (no-op if not connected)
        return if (stored) Result.Sent(msg) else Result.Error
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
