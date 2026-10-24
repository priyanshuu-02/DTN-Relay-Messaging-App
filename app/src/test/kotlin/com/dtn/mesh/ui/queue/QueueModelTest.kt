package com.dtn.mesh.ui.queue

import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.BufferedMsgInfo
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelTest {

    private val me = "0cdc860b"
    private val bob = "1a2b3c4d"
    private val carrier = "2b3c4d5e"
    private val alice = "3c4d5e6f"
    private val hub = "da64a9b0"
    private val hours4 = 4 * 3_600_000L

    private fun bundle(
        id: String, origin: String, dest: String, type: String = "DATA", hops: Int = 0, forwards: Int = 0,
        channel: Int = 0, at: Long = 1_000L,
    ) = BufferedMsgInfo(
        msgId = id, origin = origin, dest = dest, hopCount = hops, forwardCount = forwards, ttlMin = 200, bufferedFor = 10,
        status = "BUFFERED", fullId = "$id-0000-0000", messageType = type, channel = channel, receivedAtMs = at,
        expiresAtMs = at + hours4, createdAtMs = at, ttlMs = hours4,
    )

    private fun route(
        id: String, source: String, dest: String, next: String? = null, path: String = "", delivered: Boolean = false,
        prev: String? = null,
    ) = RouteRecord(
        msgId = id, source = source, destination = dest, prevHop = prev, me = me, nextHop = next, delivered = delivered,
        lastPath = path, deliveredAtMs = if (delivered) 5_000L else 0L,
    )

    private fun event(id: String, event: LifecycleEvent, extra: String = "", hop: Int = -1, origin: String = "", dest: String = "") =
        LifecycleEntry(timestamp = "Oct 24 10:00:00", event = event, msgId = id, origin = origin, dest = dest, hopCount = hop,
            forwardCount = -1, ttlRemainingMin = -1, extra = extra, epochMs = 2_000L)

    private fun chat(id: String, to: String?, state: DeliveryState, text: String = "hello") = ChatMessage(
        msgId = "$id-0000", text = text, from = "me", toNodeId = to, isOutgoing = true, status = state.label, time = "10:00",
        createdAtMs = 1_000L, state = state, isMultiParty = to == null || to.startsWith("grp:"),
    )

    // ── DM segments ─────────────────────────────────────────────────────────

    @Test
    fun ownDm_isPendingUntilACarrierTakesIt_thenForwarded() {
        val q = QueueModel.build(
            bundles = listOf(bundle("aaaa0001", me, bob), bundle("aaaa0002", me, bob, forwards = 1)),
            routes = listOf(route("aaaa0001", me, bob), route("aaaa0002", me, bob, next = carrier, path = "BLE")),
            chats = listOf(chat("aaaa0001", "!$bob", DeliveryState.QUEUED), chat("aaaa0002", "!$bob", DeliveryState.IN_TRANSIT)),
            entries = listOf(event("aaaa0002", LifecycleEvent.FORWARDED, "handed to carrier $carrier via BLE · awaiting receipt [PROPHET]", hop = 1)),
            myId8 = me,
        )
        assertEquals(listOf("aaaa0001"), q.pending.map { it.msgId8 })
        assertEquals(listOf("aaaa0002"), q.forwarded.map { it.msgId8 })
        val fwd = q.forwarded.single()
        assertTrue(fwd.own)
        assertEquals(carrier, fwd.nextHop8)
        assertEquals("BLE", fwd.link)
        assertEquals("hello", fwd.text)
        assertEquals(1, fwd.hops)
        assertTrue(q.delivered.isEmpty())
        assertTrue(q.broadcasts.isEmpty())
    }

    @Test
    fun ownDm_publishedToTheRelayOnly_staysPending() {
        // The relay stores nothing: until the receipt, only our copy exists.
        val q = QueueModel.build(
            bundles = listOf(bundle("aaaa0003", me, bob)),
            routes = listOf(route("aaaa0003", me, bob)),
            chats = listOf(chat("aaaa0003", "!$bob", DeliveryState.QUEUED)),
            entries = listOf(event("aaaa0003", LifecycleEvent.FORWARDED, "DM → $bob via Internet relay · awaiting receipt")),
            myId8 = me,
        )
        val row = q.pending.single()
        assertTrue(row.relayTried)
        assertTrue(q.forwarded.isEmpty())
        assertTrue(QueueText.dmMeta(row, 1_000L, { it }).endsWith("on the internet relay, waiting for the receipt"))
    }

    @Test
    fun carriedDm_isPendingUntilThisPhoneHandsACopyOn() {
        val q = QueueModel.build(
            bundles = listOf(bundle("bbbb0001", alice, bob, hops = 1), bundle("bbbb0002", alice, bob, hops = 1, forwards = 1)),
            routes = listOf(
                route("bbbb0001", alice, bob, prev = alice, path = "BLE"),
                route("bbbb0002", alice, bob, prev = alice, next = carrier, path = "Wi-Fi Direct"),
            ),
            chats = emptyList(),
            entries = emptyList(),
            myId8 = me,
        )
        val pending = q.pending.single()
        assertEquals("bbbb0001", pending.msgId8)
        assertFalse(pending.own)
        assertEquals(DeliveryState.CARRYING, pending.state)
        assertNull(pending.text)
        assertEquals("Carrying for $alice · encrypted", QueueText.dmPreview(pending) { it })
        assertEquals(listOf("bbbb0002"), q.forwarded.map { it.msgId8 })
    }

    @Test
    fun delivered_comesFromTheBufferAndTheJournal_neverDmsAddressedToThisPhone() {
        val q = QueueModel.build(
            // The receipt for cccc0001 just came in; its buffer copy is still there.
            bundles = listOf(bundle("cccc0001", me, bob)),
            routes = listOf(
                route("cccc0001", me, bob, next = bob, path = "BLE", delivered = true),
                route("cccc0002", me, bob, next = hub, path = "LoRa hub flood", delivered = true),
                route("cccc0003", alice, bob, prev = alice, next = bob, path = "BLE", delivered = true),
                // Received by this phone: not something it queued.
                RouteRecord(msgId = "cccc0004", source = alice, destination = me, prevHop = alice, me = me, delivered = true, lastPath = "BLE"),
                // Handed on, no receipt yet.
                route("cccc0005", me, bob, next = carrier, path = "BLE"),
            ),
            chats = listOf(chat("cccc0002", "!$bob", DeliveryState.DELIVERED)),
            entries = emptyList(),
            myId8 = me,
        )
        assertEquals(setOf("cccc0001", "cccc0002", "cccc0003"), q.delivered.map { it.msgId8 }.toSet())
        assertTrue(q.pending.isEmpty())
        assertTrue(q.forwarded.isEmpty())
        val gone = q.delivered.first { it.msgId8 == "cccc0002" }
        assertFalse(gone.inBuffer)
        assertTrue(gone.own)
        assertEquals("hello", gone.text)
        assertEquals("LoRa hub", gone.link)
        assertTrue(q.delivered.first { it.msgId8 == "cccc0001" }.inBuffer)
        assertFalse(q.delivered.first { it.msgId8 == "cccc0003" }.own)
    }

    @Test
    fun dmAddressedToThisPhone_isNeverQueued() {
        val q = QueueModel.build(listOf(bundle("gggg0001", alice, me)), emptyList(), emptyList(), emptyList(), me)
        assertEquals(0, q.pending.size + q.forwarded.size + q.delivered.size + q.broadcasts.size)
    }

    @Test
    fun fragmentsOfOneMessage_areOneRow() {
        val q = QueueModel.build(
            bundles = listOf(bundle("hhhh0001", me, bob, at = 2_000L), bundle("hhhh0001", me, bob, forwards = 2, at = 1_000L)),
            routes = emptyList(), chats = emptyList(), entries = emptyList(), myId8 = me,
        )
        val row = q.pending.single()
        assertEquals(1_000L, row.sinceMs)
        assertEquals(2, row.forwards)
    }

    // ── Broadcasts & groups ─────────────────────────────────────────────────

    @Test
    fun broadcastSegment_holdsBroadcastAndGroupData_withThePhonesReached() {
        val q = QueueModel.build(
            bundles = listOf(bundle("dddd0001", me, "BROADCAST"), bundle("dddd0002", alice, "BROADCAST", channel = 7, hops = 2)),
            routes = listOf(
                route("dddd0001", me, "BROADCAST", next = carrier, path = "BLE"),
                route("dddd0002", alice, "BROADCAST", prev = carrier, next = hub, path = "LoRa hub flood"),
            ),
            chats = listOf(chat("dddd0001", null, DeliveryState.SPREADING, text = "hi all")),
            entries = listOf(
                event("dddd0001", LifecycleEvent.FORWARDED, "BCAST → $bob via Internet relay"),
                event("dddd0001", LifecycleEvent.FORWARDED, "broadcast copy → $carrier via BLE [PROPHET]"),
                event("dddd0002", LifecycleEvent.FORWARDED, "group copy → $hub via LoRa hub flood [PROPHET]"),
            ),
            myId8 = me,
        )
        assertEquals(setOf("dddd0001", "dddd0002"), q.broadcasts.map { it.msgId8 }.toSet())
        assertTrue(q.pending.isEmpty() && q.forwarded.isEmpty() && q.delivered.isEmpty())

        val ours = q.broadcasts.first { it.msgId8 == "dddd0001" }
        assertTrue(ours.own)
        assertEquals(DeliveryState.SPREADING, ours.state)
        assertEquals(setOf(carrier, bob), ours.reached.toSet())
        assertEquals("hi all", ours.text)

        val group = q.broadcasts.first { it.msgId8 == "dddd0002" }
        assertFalse(group.own)
        assertEquals(7, group.channel)
        assertEquals(DeliveryState.SPREADING, group.state)
        assertEquals(listOf(hub), group.reached)
        assertTrue(QueueText.broadcastMeta(group, 1_000L) { it == hub }.endsWith("Reached 1 hub"))
        assertEquals("From $alice · encrypted, this phone isn't in the group", QueueText.broadcastPreview(group, member = false) { it })
    }

    @Test
    fun ownBroadcast_waitsForPhonesUntilItsFirstHandOff() {
        val q = QueueModel.build(
            listOf(bundle("dddd0003", me, "BROADCAST")), listOf(route("dddd0003", me, "BROADCAST")),
            emptyList(), emptyList(), me,
        )
        val row = q.broadcasts.single()
        assertEquals(DeliveryState.QUEUED, row.state)
        assertEquals("Waiting for phones", QueueText.chip(row.state, broadcast = true))
        assertTrue(row.reached.isEmpty())
    }

    @Test
    fun controlTraffic_neverAppearsInAnySegment() {
        val control = listOf(
            bundle("eeee0001", alice, "BROADCAST", type = "ANNOUNCE"),
            // Discover off: a direct announce addressed to one contact.
            bundle("eeee0002", me, bob, type = "ANNOUNCE"),
            bundle("eeee0003", me, bob, type = "ROUTING_ACK"),
            bundle("eeee0004", alice, "BROADCAST", type = "ROUTING_SUMMARY"),
            bundle("eeee0005", alice, "BROADCAST", type = "BUNDLE_OFFER"),
            bundle("eeee0006", alice, "BROADCAST", type = "BUNDLE_REQUEST"),
            bundle("eeee0007", alice, "BROADCAST", type = "SOMETHING_NEW"),
        )
        val q = QueueModel.build(control + bundle("eeee0008", alice, "BROADCAST"), emptyList(), emptyList(), emptyList(), me)
        assertEquals(listOf("eeee0008"), q.broadcasts.map { it.msgId8 })
        assertTrue(q.pending.isEmpty() && q.forwarded.isEmpty() && q.delivered.isEmpty())
        control.forEach { assertNull(it.messageType, QueueModel.item(it, null, null, emptyList(), me)) }
    }

    @Test
    fun history_isTheStoreAndForwardRecords_dmsOnly() {
        val q = QueueModel.build(
            bundles = emptyList(), routes = emptyList(), chats = emptyList(),
            // Newest first, as the journal keeps them.
            entries = listOf(
                event("ffff0001", LifecycleEvent.BUFFER_CLEARED, "delivered — confirmed by end-to-end receipt (via $bob)"),
                event("ffff0002", LifecycleEvent.BUFFERED, "broadcast received", origin = alice, dest = "BROADCAST"),
                event("ffff0001", LifecycleEvent.BUFFERED, "queued — waiting for a link toward the recipient", origin = me, dest = bob),
            ),
            myId8 = me,
        )
        val item = q.history.single()
        assertEquals("ffff0001", item.msgId)
        assertEquals("Delivered" to Tone.GREEN, QueueText.historyResult(item, null))
        // No clearing event yet: the route's outcome fills in.
        val open = item.copy(clearEvent = null)
        assertEquals("Still buffered" to Tone.BLUE, QueueText.historyResult(open, null))
        assertEquals("Expired" to Tone.ORANGE, QueueText.historyResult(open, route("ffff0001", me, bob).copy(outcome = "EXPIRED")))
    }

    // ── Row texts ────────────────────────────────────────────────────────────

    @Test
    fun ttlAgeAndUrgency() {
        val now = 1_760_000_000_000L
        assertEquals("3h 59m", QueueText.ttl(now + 239 * 60_000L + 30_000L, now))
        assertEquals("45m", QueueText.ttl(now + 45 * 60_000L, now))
        assertEquals("<1m", QueueText.ttl(now + 30_000L, now))
        assertEquals("0m", QueueText.ttl(now - 1, now))
        assertEquals("now", QueueText.age(now - 3_000L, now))
        assertEquals("45s", QueueText.age(now - 45_000L, now))
        assertEquals("12m", QueueText.age(now - 12 * 60_000L, now))
        assertEquals("3h", QueueText.age(now - 3 * 3_600_000L, now))
        assertEquals("2d", QueueText.age(now - 2 * 86_400_000L - 5, now))
        assertEquals("3h 10m", QueueText.span(now - 190 * 60_000L, now))
        assertEquals(QueueText.Urgency.EXPIRING_SOON, QueueText.urgency(3))
        assertEquals(QueueText.Urgency.TIME_SENSITIVE, QueueText.urgency(20))
        assertEquals(QueueText.Urgency.QUEUED, QueueText.urgency(100))
    }

    @Test
    fun chipsReachAndTheMetaLine() {
        assertEquals("Waiting for a route", QueueText.chip(DeliveryState.QUEUED))
        assertEquals("Awaiting receipt", QueueText.chip(DeliveryState.IN_TRANSIT))
        assertEquals("Carrying", QueueText.chip(DeliveryState.CARRYING))
        assertEquals("Delivered ✓✓", QueueText.chip(DeliveryState.DELIVERED))
        assertEquals("Not handed to anyone yet", QueueText.reached(0, 0))
        assertEquals("Reached 1 phone", QueueText.reached(1, 0))
        assertEquals("Reached 3 phones", QueueText.reached(3, 0))
        assertEquals("Reached 2 phones · 1 hub", QueueText.reached(2, 1))
        assertEquals("Reached 2 hubs", QueueText.reached(0, 2))

        val now = 10_000_000L
        val row = DmRow(
            msgId8 = "x", origin8 = me, dest8 = bob, own = true, text = "hi", state = DeliveryState.IN_TRANSIT, hops = 1, forwards = 1,
            sinceMs = now, expiresAtMs = now + 239 * 60_000L + 30_000L, nextHop8 = hub, link = "LoRa hub", relayTried = false, inBuffer = true,
        )
        assertEquals("Hops 1 · TTL 3h 59m · → hub A9B0 (LoRa hub)", QueueText.dmMeta(row, now) { if (it == hub) "hub A9B0" else it })
        assertEquals("Hops 0 · TTL 3h 59m · not handed on yet",
            QueueText.dmMeta(row.copy(hops = 0, nextHop8 = null, link = null), now) { it })
    }

    @Test
    fun nodeNames() {
        val names = NodeNames(
            listOf(
                PeerInfo(nodeId = "!$bob", isOnline = false, lastSeenMs = 0L, customName = "Bob"),
                PeerInfo(nodeId = "!$hub", isOnline = true, lastSeenMs = 0L, isHub = true),
            ),
            me,
        )
        assertEquals("You", names.label(me))
        assertEquals("Bob", names.label(bob))
        assertEquals("hub A9B0", names.label(hub))
        assertEquals("deadbeef", names.label("deadbeef"))
        assertEquals("Everyone", names.label("BROADCAST"))
        assertEquals("Bob ($bob)", names.withId(bob))
        assertEquals("You", names.withId(me))
        assertEquals("!deadbeef", names.fullId("deadbeef"))
        assertTrue(names.isHub(hub))
        assertFalse(names.isHub(bob))
    }
}
