package com.dtn.mesh.receiver

import com.dtn.mesh.model.DtnMessage
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/**
 * The BLE client-link rules [BleTransportAdapter] follows, kept pure for the JVM tests.
 *
 * Field problem (Oct 2026, `_diag/fix1_report.md`): phones change their BLE address often (every
 * re-advertise, and Android re-enables the advertiser with a new one after each inbound connection).
 * Client connections were keyed by address, so every new address got a new connection while the old
 * one was never closed: links piled up on both phones (10 live links and 9 registered GATT clients
 * for a single nearby phone) until connects failed with status 133 for minutes, with the peer at
 * 0.5 m. Now there is one link per peer phone, reused for every bundle while it is up.
 *
 * Fix 3 (`_diag/fix3_report.md`): that one link now carries bundles both ways ([BleRoles]), and a
 * connect runs in the background: a send never waits for it.
 */
object BleLinkRules {
    /** Time for connect + MTU + service discovery + subscribe + HELLO, all tries together. */
    const val CONNECT_BUDGET_MS = 8_000L

    /** Connect tries per send: one quick retry after a failure (133, 8, 62, …) on the freshest address. */
    const val CONNECT_TRIES = 2

    /** Pause before the retry: lets the stack release the failed link (and the scan catch a new address). */
    const val RETRY_GAP_MS = 400L

    /** A bundle whose link died mid-write is resent once over a fresh link, within this budget. */
    const val RESEND_BUDGET_MS = 5_000L

    /** No MTU answer by then: discover services anyway and send in 20-byte chunks. */
    const val MTU_WAIT_MS = 2_000L

    /** The peer's ATT acknowledgement of one chunk (write with response). */
    const val WRITE_ACK_MS = 2_000L

    /** The central's ATT confirmation of one indicated chunk (fix 3: the peripheral's sends). */
    const val INDICATION_ACK_MS = 2_000L

    /** An inbound chunk this long after the previous one starts a new transfer (a torn one never sticks). */
    const val RX_GAP_RESET_MS = 2_000L

    /** Another connect try after a failed one: at most [CONNECT_TRIES], and only if it still fits the budget. */
    fun retryConnect(triesSoFar: Int, nowMs: Long, deadlineMs: Long): Boolean =
        triesSoFar < CONNECT_TRIES && nowMs + RETRY_GAP_MS < deadlineMs

    /**
     * Reuse the link we have to a peer: only one that finished service discovery and is still up,
     * whatever address the peer advertises by now (a new address is no reason for a new link).
     */
    fun reuse(ready: Boolean, closed: Boolean): Boolean = ready && !closed

    /** Inbound reassembly: does a chunk at [nowMs] start a new transfer? */
    fun startsNewTransfer(lastChunkMs: Long, nowMs: Long): Boolean =
        lastChunkMs > 0 && nowMs - lastChunkMs > RX_GAP_RESET_MS

    /** A GATT status as people read it, for the logs and the Bluetooth page. */
    fun statusName(status: Int): String = when (status) {
        0 -> "ok"
        8 -> "status 8 (link timeout: out of range)"
        19 -> "status 19 (closed by the other phone)"
        22 -> "status 22 (closed by this phone)"
        34 -> "status 34 (no response)"
        62 -> "status 62 (connection failed to establish)"
        133 -> "status 133 (connect failed)"
        147 -> "status 147 (connection timeout)"
        257 -> "status 257 (GATT failure)"
        else -> "status $status"
    }
}

/**
 * Node ↔ GATT-object bookkeeping with one link per node: [open] hands back the link it replaces (to
 * be closed), and a callback from a GATT object that is no longer current finds nothing ([linkOf]),
 * so it can't touch the current link's state. Identity-keyed, so it works for any handle type.
 */
class LinkTable<G : Any, L : Any> {
    private val byNode = HashMap<String, L>()
    private val byGatt = IdentityHashMap<G, L>()
    private val gattOf = IdentityHashMap<L, G>()

    /** [link] (over [gatt]) is now [node]'s link. @return the link it replaced, if any. */
    @Synchronized
    fun open(node: String, gatt: G, link: L): L? {
        val old = byNode.put(node, link)
        if (old != null && old !== link) gattOf.remove(old)?.let { byGatt.remove(it) }
        byGatt[gatt] = link
        gattOf[link] = gatt
        return old?.takeIf { it !== link }
    }

    @Synchronized
    fun linkOf(gatt: G): L? = byGatt[gatt]

    @Synchronized
    fun current(node: String): L? = byNode[node]

    /** Forget [link] (closed or failed). @return true if it was still known. */
    @Synchronized
    fun drop(node: String, link: L): Boolean {
        val g = gattOf.remove(link) ?: return false
        byGatt.remove(g)
        if (byNode[node] === link) byNode.remove(node)
        return true
    }

    /** Every link, for shutting everything down. */
    @Synchronized
    fun clear(): List<L> {
        val all = gattOf.keys.toList()
        byNode.clear(); byGatt.clear(); gattOf.clear()
        return all
    }

    @Synchronized
    fun size(): Int = gattOf.size
}

/** What the BLE client did with one peer phone (the Bluetooth page's per-phone line). */
data class BlePeerStats(
    val connectTries: Int = 0,
    val connectFailures: Int = 0,
    /** Failures since the last success (a run of 133s shows here). */
    val failuresInARow: Int = 0,
    val bundlesSent: Int = 0,
    val lastOkMs: Long = 0L,
    val lastError: String = "",
    val lastErrorMs: Long = 0L,
) {
    fun connected(): BlePeerStats = copy(connectTries = connectTries + 1)

    fun connectFailed(error: String, atMs: Long): BlePeerStats = copy(
        connectTries = connectTries + 1, connectFailures = connectFailures + 1,
        failuresInARow = failuresInARow + 1, lastError = error, lastErrorMs = atMs,
    )

    fun sent(atMs: Long): BlePeerStats = copy(bundlesSent = bundlesSent + 1, failuresInARow = 0, lastOkMs = atMs)

    fun writeFailed(error: String, atMs: Long): BlePeerStats =
        copy(failuresInARow = failuresInARow + 1, lastError = error, lastErrorMs = atMs)

    /** e.g. "GATT: 12 sent · last OK 14:05:03 · last error status 133 (connect failed) 14:04:58". */
    fun describe(clock: (Long) -> String): String = buildList {
        add("GATT: $bundlesSent sent")
        if (connectTries > 0) add("$connectTries connect${if (connectTries == 1) "" else "s"}" +
            if (connectFailures > 0) " ($connectFailures failed)" else "")
        if (lastOkMs > 0) add("last OK ${clock(lastOkMs)}")
        if (lastErrorMs > 0) add("last error ${lastError.ifBlank { "?" }} ${clock(lastErrorMs)}" +
            if (failuresInARow > 1) " · $failuresInARow in a row" else "")
    }.joinToString(" · ")
}

/**
 * One BLE link per phone pair, used in both directions (Oct 2026 fix 3, `_diag/fix3_report.md`).
 *
 * Field problem: both phones opened a GATT client link to each other. Between the Redmi (MediaTek)
 * and the OnePlus the second client's MTU exchange and service discovery never got an answer, so
 * its connect hung for the whole 8 s budget, every ~8 s, while the other direction worked; the
 * flush waited for every one of those connects, and DMs went over Wi-Fi Direct after 25–44 s.
 *
 * Now only the lower node id connects (central); the higher id (peripheral) sends its bundles over
 * that same link as indications. The higher id waits [HIGHER_ID_WAIT_MS] for the lower id's link
 * and only then connects itself (the lower id's connect failed, it's backing off, or it can't hear
 * us). Nobody opens a client link to a phone that already has a link with us in either role.
 */
object BleRoles {
    /** The higher id waits this long (with the peer in range and no link) before it connects itself. */
    const val HIGHER_ID_WAIT_MS = 3_000L

    /** True when [me] is the lower node id (32-bit ids compared unsigned, as on the wire). */
    fun isLower(me: Int, peer: Int): Boolean = Integer.compareUnsigned(me, peer) < 0

    /** What this phone does about the link with one peer right now. */
    enum class Decision(val connects: Boolean, val why: String) {
        CONNECT_LOWER_ID(true, "we have the lower id"),
        CONNECT_PEER_SILENT(true, "no link from it after 3 s"),
        CONNECT_OLDER_APP(true, "its app is older: it doesn't open links both ways"),
        WAIT_FOR_PEER(false, "it has the lower id, so it connects"),
        HAVE_LINK(false, "a link is up"),
        CONNECTING(false, "a connect is under way"),
        BACKING_OFF(false, "backing off after failed connects"),
    }

    /**
     * @param clientLink we have a client link to the peer (connecting or up), [clientReady] = up.
     * @param serverLink the peer is linked to our server (subscribed + HELLO).
     * @param olderApp the peer's server lacks the link characteristic (a build before fix 3): both
     *   phones connect to each other, as before.
     * @param noLinkSinceMs since when the peer has been in range without a link with us.
     */
    fun decide(
        iAmLower: Boolean,
        clientLink: Boolean,
        clientReady: Boolean,
        serverLink: Boolean,
        olderApp: Boolean,
        noLinkSinceMs: Long,
        backoffUntilMs: Long,
        nowMs: Long,
    ): Decision = when {
        serverLink -> Decision.HAVE_LINK
        clientLink -> if (clientReady) Decision.HAVE_LINK else Decision.CONNECTING
        nowMs < backoffUntilMs -> Decision.BACKING_OFF
        iAmLower -> Decision.CONNECT_LOWER_ID
        olderApp -> Decision.CONNECT_OLDER_APP
        nowMs - noLinkSinceMs >= HIGHER_ID_WAIT_MS -> Decision.CONNECT_PEER_SILENT
        else -> Decision.WAIT_FOR_PEER
    }

    /** Which end of the link this phone is: [CENTRAL] = we connected, [PERIPHERAL] = it connected to us. */
    enum class Via(val label: String) { CENTRAL("as central"), PERIPHERAL("as peripheral") }

    /** The link a bundle goes over now: when both are up, the one the lower id opened. Null = none is up. */
    fun sendVia(iAmLower: Boolean, centralReady: Boolean, peripheralReady: Boolean): Via? = when {
        centralReady && peripheralReady -> if (iAmLower) Via.CENTRAL else Via.PERIPHERAL
        centralReady -> Via.CENTRAL
        peripheralReady -> Via.PERIPHERAL
        else -> null
    }

    /**
     * Both phones connected at the same instant, so there are two links: the one the lower id opened
     * stays. True when the duplicate is ours (we opened it and have the higher id): we close it.
     */
    fun closeOursOnDuplicate(iAmLower: Boolean): Boolean = !iAmLower
}

/** Per-phone connect back-off (fix 3): 1, 2, 4, 8, 16, then 30 s after failed connects in a row. */
class ConnectBackoff(private val firstMs: Long = FIRST_MS, private val maxMs: Long = MAX_MS) {
    private class State(val failures: Int, val untilMs: Long)
    private val byNode = HashMap<String, State>()

    /** A connect to [node] failed at [nowMs]. @return how long until the next try. */
    @Synchronized
    fun failed(node: String, nowMs: Long): Long {
        val n = (byNode[node]?.failures ?: 0) + 1
        val wait = delayMs(n)
        byNode[node] = State(n, nowMs + wait)
        return wait
    }

    /** A link with [node] came up (either role): its next failure starts at [firstMs] again. */
    @Synchronized
    fun reset(node: String) { byNode.remove(node) }

    @Synchronized
    fun untilMs(node: String): Long = byNode[node]?.untilMs ?: 0L

    @Synchronized
    fun failuresInARow(node: String): Int = byNode[node]?.failures ?: 0

    @Synchronized
    fun clear() = byNode.clear()

    /** The wait after [failuresInARow] failed connects in a row. */
    fun delayMs(failuresInARow: Int): Long {
        if (failuresInARow <= 0) return 0L
        return (firstMs shl (failuresInARow - 1).coerceAtMost(20)).coerceAtMost(maxMs)
    }

    companion object {
        const val FIRST_MS = 1_000L
        const val MAX_MS = 30_000L
    }
}

/**
 * The peripheral end of the one link per phone pair (fix 3): centrals connected to our GATT server,
 * by Bluetooth address. A connection is a link to a phone once that phone subscribed to our
 * indications and wrote its HELLO ([BleHello]), in either order; until then the address is just a
 * connection (an older app's client, or one still setting up). One link per phone: a HELLO from a
 * newer address takes over. Android reports every LE link of the phone to the server callback, so
 * a bare connection never counts.
 */
class ServerLinkTable {
    private class Entry(var mtu: Int = DEFAULT_MTU, var subscribed: Boolean = false, var node: String? = null)
    private val byAddress = HashMap<String, Entry>()
    /** node → the address of its ready link. */
    private val readyAt = HashMap<String, String>()

    @Synchronized
    fun connected(address: String) { byAddress.getOrPut(address) { Entry() } }

    /** The ATT MTU the central negotiated (the server's `onMtuChanged`). */
    @Synchronized
    fun mtu(address: String, mtu: Int) { byAddress.getOrPut(address) { Entry() }.mtu = mtu }

    /** The central at [address] (un)subscribed. @return the phone whose link this made ready, if any. */
    @Synchronized
    fun subscribed(address: String, on: Boolean): String? {
        val e = byAddress.getOrPut(address) { Entry() }
        val wasReady = isReady(address, e)
        e.subscribed = on
        if (!on) {
            e.node?.let { if (readyAt[it] == address) readyAt.remove(it) }
            return null
        }
        return becameReady(address, e, wasReady)
    }

    /** The central at [address] said it is [node]. @return [node] if this made its link ready. */
    @Synchronized
    fun hello(address: String, node: String): String? {
        val e = byAddress.getOrPut(address) { Entry() }
        val wasReady = isReady(address, e)
        val previous = e.node
        if (previous != null && previous != node && readyAt[previous] == address) readyAt.remove(previous)
        e.node = node
        return becameReady(address, e, wasReady && previous == node)
    }

    /** The connection at [address] is gone. @return the phone whose ready link it was, if any. */
    @Synchronized
    fun disconnected(address: String): String? {
        val e = byAddress.remove(address) ?: return null
        val node = e.node ?: return null
        if (readyAt[node] != address) return null
        readyAt.remove(node)
        return node
    }

    /** The address of [node]'s ready link (it connected to us), or null. */
    @Synchronized
    fun readyAddress(node: String): String? = readyAt[node]

    /** The phone the central at [address] said it is (HELLO), or null. */
    @Synchronized
    fun nodeOf(address: String): String? = byAddress[address]?.node

    @Synchronized
    fun mtuOf(address: String): Int = byAddress[address]?.mtu ?: DEFAULT_MTU

    @Synchronized
    fun isSubscribed(address: String): Boolean = byAddress[address]?.subscribed == true

    @Synchronized
    fun readyNodes(): Set<String> = readyAt.keys.toSet()

    @Synchronized
    fun clear() { byAddress.clear(); readyAt.clear() }

    private fun isReady(address: String, e: Entry): Boolean = e.node?.let { readyAt[it] == address } == true

    private fun becameReady(address: String, e: Entry, wasReady: Boolean): String? {
        val node = e.node ?: return null
        if (!e.subscribed) return null
        readyAt[node] = address
        return if (wasReady) null else node
    }

    companion object {
        /** Until the central negotiates one: 23 → 20-byte chunks. */
        const val DEFAULT_MTU = 23
    }
}

/**
 * HELLO (fix 3): the central writes `[ver 1][4B node id BE]` to the link characteristic right after
 * subscribing, so the peripheral knows which phone that connection is (addresses rotate, and the
 * central's address is not the one it advertises). Readers drop other versions, short values and the
 * ids 0 / 0xFFFFFFFF, and ignore trailing bytes (later fields are appended).
 */
object BleHello {
    const val VERSION: Byte = 1
    const val SIZE = 5

    fun encode(nodeNum: Int): ByteArray = byteArrayOf(
        VERSION, (nodeNum ushr 24).toByte(), (nodeNum ushr 16).toByte(), (nodeNum ushr 8).toByte(), nodeNum.toByte(),
    )

    /** The sender's node number, or null when [value] isn't a HELLO this build understands. */
    fun decode(value: ByteArray?): Int? {
        if (value == null || value.size < SIZE || value[0] != VERSION) return null
        val n = ((value[1].toInt() and 0xFF) shl 24) or ((value[2].toInt() and 0xFF) shl 16) or
            ((value[3].toInt() and 0xFF) shl 8) or (value[4].toInt() and 0xFF)
        return n.takeIf { it != 0 && it != -1 }
    }
}

/** The `[2B len][wire]` framing of a bundle over BLE, and its chunks (writes and indications alike). */
object BleFraming {
    /** No ATT value is longer than this, whatever the MTU. */
    const val MAX_CHUNK = 512
    /** The default ATT MTU (23) minus the 3-byte ATT header. */
    const val MIN_CHUNK = 20

    fun frame(wire: ByteArray): ByteArray {
        require(wire.isNotEmpty() && wire.size <= 0xFFFF) { "bundle of ${wire.size} B can't be framed" }
        return byteArrayOf((wire.size ushr 8).toByte(), wire.size.toByte()) + wire
    }

    /** The chunk size for an ATT MTU: MTU − 3 (a write's / an indication's header), 20 … 512. */
    fun chunkFor(mtu: Int): Int = (mtu - 3).coerceIn(MIN_CHUNK, MAX_CHUNK)

    fun chunks(framed: ByteArray, chunk: Int): List<ByteArray> {
        val size = chunk.coerceAtLeast(1)
        return (framed.indices step size).map { framed.copyOfRange(it, minOf(it + size, framed.size)) }
    }
}

/**
 * Reassembles `[2B len][wire]` frames from one sender's chunks: its writes to our server, or its
 * indications to our client link. A chunk more than [BleLinkRules.RX_GAP_RESET_MS] after the previous
 * one starts a new transfer (a torn one never sticks); a length outside 1..[maxFrame] throws the
 * buffer away so the next transfer re-syncs.
 */
class FrameAssembler(private val maxFrame: Int = DtnMessage.LORA_MAX_FRAME_BYTES) {
    private val buf = ByteArrayOutputStream()
    private var lastChunkMs = 0L

    /** How many times a corrupt length threw the buffer away, and the last such length (for the log). */
    @Volatile var badFrames = 0
        private set
    @Volatile var lastBadLength = 0
        private set

    /** Add [chunk] (received at [nowMs]). @return the wire bytes of every bundle it completed. */
    @Synchronized
    fun accept(chunk: ByteArray, nowMs: Long): List<ByteArray> {
        if (BleLinkRules.startsNewTransfer(lastChunkMs, nowMs)) buf.reset()
        lastChunkMs = nowMs
        buf.write(chunk)
        val bytes = buf.toByteArray()
        val out = ArrayList<ByteArray>(1)
        var start = 0
        while (bytes.size - start >= 2) {
            val total = ((bytes[start].toInt() and 0xFF) shl 8) or (bytes[start + 1].toInt() and 0xFF)
            if (total <= 0 || total > maxFrame) {
                lastBadLength = total
                badFrames++
                start = bytes.size // throw it all away
                break
            }
            if (bytes.size - start - 2 < total) break // more chunks pending
            out += bytes.copyOfRange(start + 2, start + 2 + total)
            start += 2 + total
        }
        buf.reset()
        if (start < bytes.size) buf.write(bytes, start, bytes.size - start)
        return out
    }
}

/**
 * Which direct link carries a bundle to a phone in range, right now (fix 3). BLE is the primary link,
 * but only a BLE link that is up NOW counts: without one, Wi-Fi Direct (if linked) gets it at once,
 * while the BLE link comes up in the background (when it's ready, a flush follows). Nothing here
 * waits: before fix 3 a send waited up to 8 s for a BLE connect, and Wi-Fi Direct only got its turn
 * after that timeout.
 */
object DirectLinkChoice {
    /**
     * @property order the links to write over now, in this order (the next only if a write fails).
     * @property connectBle Bluetooth reaches the phone but no link is up: start (or keep) a connect.
     */
    data class Plan(val order: List<SendPath>, val connectBle: Boolean)

    fun plan(only: Set<SendPath>, bleInRange: Boolean, bleReady: Boolean, wifiDirectLinked: Boolean): Plan {
        val ble = SendPath.BLE in only && bleInRange
        val order = buildList {
            if (ble && bleReady) add(SendPath.BLE)
            if (SendPath.WIFI_DIRECT in only && wifiDirectLinked) add(SendPath.WIFI_DIRECT)
        }
        return Plan(order, connectBle = ble && !bleReady)
    }
}
