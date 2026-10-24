package com.dtn.mesh.ui.queue

import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTraceTest {

    private val me = "0cdc860b"
    private val bob = "1a2b3c4d"
    private val priya = "2b3c4d5e"
    private val vrishank = "3c4d5e6f"
    private val hub = "da64a9b0"
    private val names = NodeNames(
        listOf(
            PeerInfo(nodeId = "!$bob", isOnline = false, lastSeenMs = 0L, customName = "Bob"),
            PeerInfo(nodeId = "!$priya", isOnline = true, lastSeenMs = 0L, customName = "Priya"),
            PeerInfo(nodeId = "!$vrishank", isOnline = true, lastSeenMs = 0L, customName = "Vrishank"),
            PeerInfo(nodeId = "!$hub", isOnline = true, lastSeenMs = 0L, isHub = true),
        ),
        me,
    )
    private val time: (Long) -> String = { "T$it" }

    private fun route(
        source: String = me, dest: String = bob, next: String? = null, prev: String? = null, path: String = "",
        delivered: Boolean = false, strategy: String = "",
    ) = RouteRecord(
        msgId = "m1000001", source = source, destination = dest, prevHop = prev, me = me, nextHop = next, delivered = delivered,
        lastPath = path, strategy = strategy, createdAtMs = 1_000L, deliveredAtMs = if (delivered) 61_000L else 0L,
    )

    private fun event(event: LifecycleEvent, extra: String = "", hop: Int = -1, ttl: Int = -1) = LifecycleEntry(
        timestamp = "Oct 24 10:00:00", event = event, msgId = "m1000001", origin = me, dest = bob, hopCount = hop,
        forwardCount = -1, ttlRemainingMin = ttl, extra = extra, epochMs = 1_000L,
    )

    private fun ownChat(state: DeliveryState, text: String = "secret words") = ChatMessage(
        msgId = "m1000001-aaaa-bbbb", text = text, from = "me", toNodeId = "!$bob", isOutgoing = true, status = state.label,
        time = "10:00", createdAtMs = 1_000L, state = state,
    )

    // ── Route path ───────────────────────────────────────────────────────────

    @Test
    fun routePath_ownDirectDelivery() {
        val r = route(next = bob, path = "BLE", delivered = true)
        val events = listOf(event(LifecycleEvent.FORWARDED, "direct to $bob via BLE ✓ delivered [PROPHET]", hop = 1))
        assertEquals("You —BLE→ Bob ✓", MessageTrace.routePath(r, events, names).asText())
    }

    @Test
    fun routePath_throughAHub_theHopAfterItIsNotSeenHere() {
        val events = listOf(event(LifecycleEvent.FORWARDED, "handed to LoRa hub $hub (floods it over LoRa) · awaiting receipt [PROPHET]", hop = 1))
        val path = MessageTrace.routePath(route(next = hub, path = "LoRa hub flood"), events, names)
        assertEquals("You —LoRa hub→ hub A9B0 ⋯ Bob", path.asText())
        assertTrue(path.stops[1].isHub)
        assertFalse(path.hops[1].seen)
        // The receipt came back: the destination is marked reached.
        assertEquals("You —LoRa hub→ hub A9B0 ⋯ Bob ✓",
            MessageTrace.routePath(route(next = hub, path = "LoRa hub flood", delivered = true), events, names).asText())
    }

    @Test
    fun routePath_receivedThroughACarrier() {
        val r = route(source = vrishank, dest = me, prev = priya, path = "BLE", delivered = true)
        val events = listOf(event(LifecycleEvent.DELIVERED, "received from $priya via BLE · decrypted", hop = 2))
        assertEquals("Vrishank ⋯ Priya —BLE→ You ✓", MessageTrace.routePath(r, events, names).asText())
    }

    @Test
    fun routePath_carriedAndHandedOn_takesEachLinkFromTheJournal() {
        // lastPath now holds the onward link; the journal still says how it arrived.
        val r = route(source = vrishank, dest = bob, prev = vrishank, next = priya, path = "BLE")
        val events = listOf(
            event(LifecycleEvent.BUFFERED, "carrying for $bob (received from $vrishank via Wi-Fi Direct, encrypted)", hop = 1),
            event(LifecycleEvent.FORWARDED, "handed to carrier $priya via BLE · awaiting receipt [DIMAXPROP]", hop = 2),
        )
        assertEquals("Vrishank —Wi-Fi Direct→ You —BLE→ Priya ⋯ Bob", MessageTrace.routePath(r, events, names).asText())
    }

    @Test
    fun routePath_notSentYet() {
        val path = MessageTrace.routePath(route(), emptyList(), names)
        assertEquals("You ⋯ Bob", path.asText())
        assertTrue(path.hops.single().waiting)
    }

    // ── Routing decision ─────────────────────────────────────────────────────

    @Test
    fun decision_isTheSavedOne_neverRecomputedWithTheAlgorithmSelectedNow() {
        val saved = route(next = priya, path = "BLE", strategy = "DIMAXPROP")
            .copy(decision = "C(me→dest) 0.90 > C(Priya→dest) 0.40 → forward", decisionAtMs = 1_234L)
        val d = MessageTrace.decision(saved, activeStrategy = "PROPHET")
        assertEquals("DIMAXPROP", d.routedBy)
        assertEquals("C(me→dest) 0.90 > C(Priya→dest) 0.40 → forward", d.numbers)
        assertEquals(1_234L, d.savedAtMs)
        assertNull(d.note)

        // Nobody routed it yet: only then is the algorithm selected now named.
        val waiting = MessageTrace.decision(route(), activeStrategy = "PROPHET")
        assertNull(waiting.numbers)
        assertNull(waiting.routedBy)
        assertTrue(waiting.note!!.contains("now: PROPHET"))

        // A relay hand-off isn't a routing decision.
        assertNull(MessageTrace.decision(route(next = bob, strategy = "INTERNET RELAY"), "PROPHET").routedBy)
    }

    @Test
    fun decision_receivedDm_showsTheSendersAlgorithm_broadcastHasNone() {
        val received = route(source = vrishank, dest = me, prev = vrishank, path = "BLE", delivered = true).copy(senderStrategy = "DiMaxProp")
        val d = MessageTrace.decision(received, "PROPHET")
        assertEquals("DiMaxProp", d.senderAlgorithm)
        assertNull(d.routedBy)
        assertNull(d.numbers)
        assertTrue(d.note!!.contains("destination"))

        val bcast = MessageTrace.decision(route(dest = "BROADCAST", next = priya, strategy = "PROPHET"), "PROPHET")
        assertNull(bcast.routedBy)
        assertTrue(bcast.note!!.contains("no routing algorithm"))
    }

    // ── Status, details, copy, export ────────────────────────────────────────

    @Test
    fun explanation_followsTheState() {
        val delivered = MessageTrace.build("m1000001", route(next = bob, path = "BLE", delivered = true),
            ownChat(DeliveryState.DELIVERED), emptyList(), names, null, "PROPHET", time)
        assertEquals("Delivered T61000 — took 1 min 00 s. Last link: BLE.", delivered.explanation)

        val sent = MessageTrace.build("m1000001", route(next = priya, path = "BLE"), ownChat(DeliveryState.IN_TRANSIT),
            listOf(event(LifecycleEvent.FORWARDED, "handed to carrier $priya via BLE · awaiting receipt [PROPHET]")), names, null, "PROPHET", time)
        assertEquals("Handed to Priya via BLE. It shows Delivered once the recipient's phone sends its receipt back.", sent.explanation)

        val relay = MessageTrace.build("m1000001", route(), ownChat(DeliveryState.QUEUED),
            listOf(event(LifecycleEvent.FORWARDED, "DM → $bob via Internet relay · awaiting receipt")), names, null, "PROPHET", time)
        assertTrue(relay.explanation.startsWith("Published to the internet relay (Oct 24 10:00:00)"))
    }

    @Test
    fun detailsAndCopyAll_neverIncludeTheMessageText() {
        val events = listOf(
            event(LifecycleEvent.CREATED, "direct message (end-to-end encrypted)", ttl = 240),
            event(LifecycleEvent.FORWARDED, "direct to $bob via BLE ✓ delivered [PROPHET]", hop = 1),
        )
        val m = MessageTrace.build("m1000001", route(next = bob, path = "BLE", delivered = true, strategy = "PROPHET")
            .copy(decision = "The destination $bob was in range: delivered straight over BLE.", decisionAtMs = 2_000L),
            ownChat(DeliveryState.DELIVERED), events, names, null, "EPIDEMIC", time)
        val details = m.details.associate { it.label to it.value }
        assertEquals("m1000001-aaaa-bbbb", details["Message ID"])
        assertEquals("You", details["From"])
        assertEquals("Bob ($bob)", details["To"])
        assertEquals("Direct message — end-to-end encrypted", details["Conversation"])
        assertEquals("T1000", details["Sent"])
        assertEquals("T61000", details["Delivered"])
        assertEquals("Bob ($bob)", details["Delivered to"])
        assertEquals("240 min", details["Initial TTL"])
        assertEquals("1", details["Hops"])
        assertEquals("secret words", m.text)

        val copy = MessageTrace.copyAllText(m)
        assertTrue(copy.contains("Route (seen from this phone): You —BLE→ Bob ✓"))
        assertTrue(copy.contains("Routed by: PROPHET"))
        assertFalse(copy.contains("secret words"))

        val export = MessageTrace.exportText(m, "now", time)
        events.forEach { assertTrue(export.contains(it.toLogLine())) }
        assertTrue(export.contains("Saved routing decision (T2000):"))
        assertTrue(export.contains("  The destination $bob was in range: delivered straight over BLE."))
        assertFalse(export.contains("secret words"))
    }

    @Test
    fun carriedAndReceivedDms_withoutAChatRow() {
        val carried = MessageTrace.build("m1000001", route(source = vrishank, dest = bob, prev = vrishank, path = "BLE"),
            null, emptyList(), names, null, "PROPHET", time)
        assertEquals(DeliveryState.CARRYING, carried.state)
        assertNull(carried.text)
        assertEquals("Encrypted for the recipient — this phone only carries it.", carried.contentNote)
        assertTrue(carried.details.any { it.label == "First seen here" })

        // E.g. from a blocked contact: receipted, not stored as a chat.
        val received = MessageTrace.build("m1000001", route(source = vrishank, dest = me, prev = vrishank, path = "BLE", delivered = true),
            null, emptyList(), names, null, "PROPHET", time)
        assertEquals(DeliveryState.RECEIVED, received.state)
        val d = received.details.associate { it.label to it.value }
        assertEquals("Vrishank ($vrishank)", d["From"])
        assertEquals("You", d["To"])
        assertEquals("Received via BLE from Vrishank.", received.explanation)
    }

    @Test
    fun ownGroupMessage_listsItsMembersReceipts() {
        val receipt = com.dtn.mesh.service.MessageLifecycleLog.GroupReceipt(priya, 9_000L, "LoRa hub", hub)
        val r = route(dest = "BROADCAST", next = priya, path = "BLE").copy(channel = 7, groupReceipts = listOf(receipt))
        val members = listOf(com.dtn.mesh.ui.MemberReceipt(priya, receipt), com.dtn.mesh.ui.MemberReceipt(bob))
        val chat = ownChat(DeliveryState.RECEIVED_BY_SOME).copy(toNodeId = "grp:7", isMultiParty = true,
            members = members, group = com.dtn.mesh.ui.GroupProgress(1, 2))
        val m = MessageTrace.build("m1000001", r, chat, emptyList(), names, "Family", "PROPHET", time)
        assertEquals(DeliveryState.RECEIVED_BY_SOME, m.state)
        assertTrue(m.explanation.startsWith("Received by 1 of 2 known members"))
        assertEquals("1 of 2 members", m.details.associate { it.label to it.value }["Received by"])
        assertEquals(listOf(priya, bob), m.members.map { it.member8 })
        assertEquals("✓ T9000 · via LoRa hub (hub A9B0)", MessageTrace.memberLine(m.members[0], time))
        assertEquals("Not yet", MessageTrace.memberLine(m.members[1], time))
        assertFalse(MessageTrace.copyAllText(m).contains("secret words"))
    }

    @Test
    fun aFallbackHandOff_isInTheDetails() {
        val r = route(next = hub, path = "LoRa hub").copy(fallbackVia = "LoRa hub,Internet relay", fallbackAfter = "BLE")
        val m = MessageTrace.build("m1000001", r, ownChat(DeliveryState.IN_TRANSIT), emptyList(), names, null, "PROPHET", time)
        assertEquals("LoRa hub + Internet relay after BLE (still queued 5 s after it was buffered)",
            m.details.associate { it.label to it.value }["Fallback"])
    }

    @Test
    fun journey_coloursAndPills() {
        val steps = MessageTrace.journey(
            listOf(
                event(LifecycleEvent.CREATED, "direct message (end-to-end encrypted)", ttl = 240),
                event(LifecycleEvent.BUFFERED, "queued — waiting for a link toward the recipient"),
                event(LifecycleEvent.FORWARDED, "handed to carrier $priya via BLE · awaiting receipt [PROPHET]", hop = 1),
                event(LifecycleEvent.ACK_RECEIVED, "delivery receipt from $bob via BLE"),
                event(LifecycleEvent.BUFFER_CLEARED, "delivered — confirmed by end-to-end receipt (via $bob)"),
            ),
        )
        assertEquals(listOf(Tone.BLUE, Tone.ORANGE, Tone.ORANGE, Tone.GREEN, Tone.GREY), steps.map { it.tone })
        assertEquals(listOf("Internal event", "Internal event", "Handed on", "Receipt", "Cleared"), steps.map { it.pill })
        assertEquals("Created", steps[0].title)
        assertEquals("direct message (end-to-end encrypted) · TTL 240 min", steps[0].detail)
        assertTrue(steps[2].detail.endsWith("hop 1"))
        assertEquals("Delivered",
            MessageTrace.journey(listOf(event(LifecycleEvent.FORWARDED, "direct to $bob via BLE ✓ delivered [PROPHET]"))).single().pill)
    }
}
