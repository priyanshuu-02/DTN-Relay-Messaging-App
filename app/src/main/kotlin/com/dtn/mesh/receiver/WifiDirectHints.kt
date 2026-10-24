package com.dtn.mesh.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi Direct group hints carried in every phone's BLE advertisement, so DTN phones find each
 * other's Wi-Fi Direct groups even where Wi-Fi P2P service discovery is unreliable (it is on
 * several OEM builds), and join them without an invitation prompt.
 *
 * BLE scan-response service data: `[4B node id][1B flags][4B group nonce][1B group channel]`
 * - flags bit0 = this phone owns a DTN Wi-Fi Direct group
 * - flags bit1 = Wi-Fi Direct is running on this phone (searching or in a group)
 * - flags bits2-5 = members in the owned group (0-15)
 * - group channel (Oct 2026 fix 2) = the owned group's Wi-Fi channel ([channelOf]), 0 = unknown, so
 *   a joiner scans only that channel instead of every one. Builds before it send 9 bytes.
 *
 * The group's network name and passphrase are derived from the owner's node id and the nonce
 * ([networkName], [passphrase]), so any DTN phone that hears the advert can join with them. That
 * makes the link password public to nearby DTN phones, which is intended: bundle contents are
 * end-to-end encrypted, the password only keeps non-DTN devices off the group. Builds that read
 * only the first 4 bytes (the node id) keep working unchanged.
 */
@Singleton
class WifiDirectHints @Inject constructor() {

    /** What this phone advertises about its own Wi-Fi Direct state. */
    data class Local(val active: Boolean = false, val ownerNonce: Int? = null, val members: Int = 0, val channel: Int = 0)

    /** A DTN phone heard over BLE and its Wi-Fi Direct state. */
    data class Remote(
        val nodeHex: String,
        val active: Boolean,
        val owner: Boolean,
        val nonce: Int,
        val members: Int,
        val seenAtMs: Long,
        /** Its group's channel (0 = unknown). */
        val channel: Int = 0,
    )

    private val _local = MutableStateFlow(Local())
    val local: StateFlow<Local> = _local.asStateFlow()

    private val remote = ConcurrentHashMap<String, Remote>()

    fun setLocal(value: Local) { _local.value = value }

    fun onBleSighting(nodeHex: String, data: Decoded?, nowMs: Long = System.currentTimeMillis()) {
        remote[nodeHex] = Remote(
            nodeHex = nodeHex,
            active = data?.active ?: false,
            owner = data?.owner ?: false,
            nonce = data?.nonce ?: 0,
            members = data?.members ?: 0,
            seenAtMs = nowMs,
            channel = data?.channel ?: 0,
        )
    }

    /** Phones heard within [maxAgeMs]. */
    fun recent(nowMs: Long, maxAgeMs: Long): List<Remote> {
        remote.entries.removeAll { nowMs - it.value.seenAtMs > 10 * maxAgeMs }
        return remote.values.filter { nowMs - it.seenAtMs <= maxAgeMs }
    }

    fun clear() = remote.clear()

    /** Decoded Wi-Fi Direct part of a BLE service-data payload. */
    data class Decoded(val active: Boolean, val owner: Boolean, val nonce: Int, val members: Int, val channel: Int = 0)

    companion object {
        private const val FLAG_OWNER = 0x01
        private const val FLAG_ACTIVE = 0x02

        /** Network name of the group owned by [ownerHex] (must start with "DIRECT-xy"). */
        fun networkName(ownerHex: String): String = "DIRECT-DT-${ownerHex.lowercase()}"

        /** Passphrase of the group owned by [ownerHex] with [nonce] (20 hex chars). */
        fun passphrase(ownerHex: String, nonce: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("DTN-wfd-v1|${ownerHex.lowercase()}|${"%08x".format(nonce)}".toByteArray(Charsets.US_ASCII))
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }

        /** The one-byte channel number of a group frequency: 2.4 GHz 1–14, 5 GHz 32–177; 0 = unknown / 6 GHz. */
        fun channelOf(frequencyMhz: Int): Int = when {
            frequencyMhz == 2484 -> 14
            frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
            frequencyMhz in 5160..5885 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5000) / 5
            else -> 0
        }

        /** [channelOf] back to MHz (0 = unknown). */
        fun frequencyOf(channel: Int): Int = when (channel) {
            in 1..13 -> 2407 + 5 * channel
            14 -> 2484
            in 32..177 -> 5000 + 5 * channel
            else -> 0
        }

        /** BLE service data for this phone: node id, then its Wi-Fi Direct state. */
        fun encode(nodeNum: Int, local: Local): ByteArray {
            var flags = 0
            if (local.active) flags = flags or FLAG_ACTIVE
            if (local.active && local.ownerNonce != null) flags = flags or FLAG_OWNER
            flags = flags or ((local.members.coerceIn(0, 15)) shl 2)
            val nonce = local.ownerNonce ?: 0
            val channel = if (local.active && local.ownerNonce != null) local.channel.coerceIn(0, 255) else 0
            return byteArrayOf(
                (nodeNum ushr 24).toByte(), (nodeNum ushr 16).toByte(), (nodeNum ushr 8).toByte(), nodeNum.toByte(),
                flags.toByte(),
                (nonce ushr 24).toByte(), (nonce ushr 16).toByte(), (nonce ushr 8).toByte(), nonce.toByte(),
                channel.toByte(),
            )
        }

        /** The Wi-Fi Direct part of [serviceData], or null for a builds that only send the node id. */
        fun decode(serviceData: ByteArray): Decoded? {
            if (serviceData.size < 9) return null
            val flags = serviceData[4].toInt() and 0xFF
            val nonce = ((serviceData[5].toInt() and 0xFF) shl 24) or ((serviceData[6].toInt() and 0xFF) shl 16) or
                ((serviceData[7].toInt() and 0xFF) shl 8) or (serviceData[8].toInt() and 0xFF)
            val owner = flags and FLAG_OWNER != 0
            return Decoded(
                active = flags and FLAG_ACTIVE != 0,
                owner = owner,
                nonce = nonce,
                members = (flags ushr 2) and 0x0F,
                // Builds before fix 2 send 9 bytes: channel unknown.
                channel = if (owner && serviceData.size >= 10) serviceData[9].toInt() and 0xFF else 0,
            )
        }
    }
}
