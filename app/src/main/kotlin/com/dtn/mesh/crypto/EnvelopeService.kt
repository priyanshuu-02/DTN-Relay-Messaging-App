package com.dtn.mesh.crypto

import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end envelope: seals outgoing plaintext and opens incoming ciphertext. Relays/hubs never
 * touch this — they route on the cleartext header only.
 *
 * Suite selection:
 *  - unicast (DM)        → suite 1, X25519(my priv, peer xPub) → AES-256-GCM. Needs the peer's key.
 *  - broadcast channel 0 → suite 0, plaintext (public "to everyone" announcements).
 *  - group channel > 0   → suite 2, pre-shared channel key → AES-256-GCM. Needs group membership.
 */
@Singleton
class EnvelopeService @Inject constructor(
    private val identity: DtnIdentity,
    private val contactDao: ContactDao,
    private val groupKeyStore: GroupKeyStore,
) {
    sealed interface Sealed {
        data class Ok(val payload: ByteArray, val flags: Int) : Sealed
        /** We don't have the key needed to encrypt to this destination/channel yet. */
        object NoKey : Sealed
        data class TooLong(val maxBytes: Int) : Sealed
    }

    /** Max plaintext bytes that fit one single LoRa frame for the suite chosen by (dest, channel). */
    suspend fun maxPlaintextBytes(dest: NodeId, channel: Int): Int =
        if (channel <= 0 && dest == NodeId.BROADCAST) DtnMessage.MAX_SINGLE_PACKET_PAYLOAD
        else DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN

    suspend fun sealText(text: String, messageId: String, origin: NodeId, dest: NodeId, channel: Int): Sealed {
        val plain = text.toByteArray(Charsets.UTF_8)
        val originNum = origin.toNodeNum32()
        val destNum = dest.toNodeNum32()
        val mid = uuid16(messageId)

        return when {
            channel > 0 -> {
                val key = groupKeyStore.key(channel) ?: return Sealed.NoKey
                val max = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN
                if (plain.size > max) return Sealed.TooLong(max)
                val flags = flagsFor(DtnCrypto.SUITE_CHANNEL)
                val aad = aad(messageId, origin, dest, channel, flags)
                Sealed.Ok(DtnCrypto.seal(key, mid, originNum, destNum, DtnCrypto.SUITE_CHANNEL, 0, aad, plain), flags)
            }
            dest == NodeId.BROADCAST -> {
                val max = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD
                if (plain.size > max) return Sealed.TooLong(max)
                Sealed.Ok(plain, flagsFor(DtnCrypto.SUITE_PLAIN))
            }
            else -> {
                val peerX = contactDao.getByNodeId(dest.value)?.xPub ?: return Sealed.NoKey
                val max = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN
                if (plain.size > max) return Sealed.TooLong(max)
                val root = DtnCrypto.x25519(identity.xPriv, peerX)
                val flags = flagsFor(DtnCrypto.SUITE_DM)
                val aad = aad(messageId, origin, dest, 0, flags)
                Sealed.Ok(DtnCrypto.seal(root, mid, originNum, destNum, DtnCrypto.SUITE_DM, 0, aad, plain), flags)
            }
        }
    }

    /** Decrypt [msg] if we can. Returns plaintext, or null (not for us / no key / tamper). */
    suspend fun open(msg: DtnMessage): String? {
        val suite = msg.flags and 0x07
        val mid = uuid16(msg.id)
        val originNum = msg.originNodeId.toNodeNum32()
        val destNum = msg.destinationNodeId.toNodeNum32()
        val aad = DtnWireCodec.aadHeader(msg)
        return when (suite) {
            DtnCrypto.SUITE_PLAIN -> runCatching { String(msg.payloadBytes, Charsets.UTF_8) }.getOrNull()
            DtnCrypto.SUITE_DM -> {
                if (msg.destinationNodeId != identity.nodeId) return null
                val peerX = contactDao.getByNodeId(msg.originNodeId.value)?.xPub ?: return null
                val root = DtnCrypto.x25519(identity.xPriv, peerX)
                DtnCrypto.open(root, mid, originNum, destNum, DtnCrypto.SUITE_DM, msg.fragmentIndex, aad, msg.payloadBytes)
                    ?.let { String(it, Charsets.UTF_8) }
            }
            DtnCrypto.SUITE_CHANNEL -> {
                val key = groupKeyStore.key(msg.channel) ?: return null
                DtnCrypto.open(key, mid, originNum, destNum, DtnCrypto.SUITE_CHANNEL, msg.fragmentIndex, aad, msg.payloadBytes)
                    ?.let { String(it, Charsets.UTF_8) }
            }
            else -> null
        }
    }

    /** AAD = masked header for a to-be-sent DATA bundle (ttl/hop zeroed by [DtnWireCodec.aadHeader]). */
    private fun aad(messageId: String, origin: NodeId, dest: NodeId, channel: Int, flags: Int): ByteArray {
        val template = DtnMessage(
            id = messageId, originNodeId = origin, destinationNodeId = dest,
            payloadBytes = ByteArray(0), createdAtMs = 0L, ttlMs = 0L,
            messageType = DtnMessageType.DATA, channel = channel, flags = flags,
        )
        return DtnWireCodec.aadHeader(template)
    }

    private fun flagsFor(suite: Int): Int = (suite and 0x07) or (1 shl 6) // protocol version 1

    private fun uuid16(id: String): ByteArray {
        val u = try { UUID.fromString(id) } catch (e: Exception) { UUID.nameUUIDFromBytes(id.toByteArray()) }
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }
}
