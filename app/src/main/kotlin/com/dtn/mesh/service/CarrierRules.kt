package com.dtn.mesh.service

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.DiMaxPropStrategy
import com.dtn.mesh.routing.MaxPropStrategy
import com.dtn.mesh.routing.ProphetStrategy
import com.dtn.mesh.routing.RoutingStrategy

/**
 * The carrier (mule) rules of one flush pass to one peer, in the order [DtnOrchestrator.sendToSingle]
 * applies them: which buffered bundles the peer is handed, and why the others wait. Pure (no Android,
 * no I/O), so the multi-hop simulation tests run exactly these rules with one real routing strategy
 * per simulated phone.
 *
 * - Never back to the bundle's origin, nor to the phone that handed it to us (the header only names
 *   the origin), unless that phone is the destination.
 * - A broadcast / group message goes to each peer once per encounter or presence session.
 * - Anything that waits for an end-to-end receipt (a mule copy, the same-hub hand-off, a relay
 *   publish, a hub flood) goes to the same peer at most once per 30 s; a direct delivery over a link
 *   that ends at the destination is never held back. A mule copy that a BLE / Wi-Fi Direct write
 *   handed over (acknowledged) goes to that peer once per session.
 * - Relay-only peers carry no one else's DMs; a phone reached only through our hub gets only the DMs
 *   addressed to it; our hub gets each DM once per hub session (unless its destination is reachable
 *   another way) and bypasses the routing strategy.
 * - The routing strategy vetoes mule traffic only ([approvedByStrategy], or Q-learning's
 *   [qLearningPass]); direct deliveries, broadcasts and hub hand-offs bypass it.
 * - A bundle goes out with one more hop ([onWire]); on the wire its TTL is what is left
 *   ([com.dtn.mesh.receiver.DtnWireCodec]), so it only ever counts down.
 */
object CarrierRules {

    /** The peer a pass runs for (the same for every bundle in it). */
    data class Peer(
        val id: NodeId,
        /** Only the internet relay reaches it right now: relay semantics, no custody. */
        val relay: Boolean = false,
        /** Our LoRa hub. */
        val isHub: Boolean = false,
        /** Another phone on our hub that no link of ours reaches. */
        val hubOnly: Boolean = false,
    )

    /** What this phone knows when it decides: send tracking, receipts, previous hops, links. */
    interface Facts {
        /** An end-to-end receipt already confirmed [msgId]'s delivery. */
        fun delivered(msgId: String): Boolean

        /** [peer] handed [msgId] to us (the first copy or a later duplicate): it already has it. */
        fun cameFrom(msgId: String, peer: String): Boolean

        /** We handed [msgId] to [peer] since [peer] last came online (its session). */
        fun sentThisSession(msgId: String, peer: String): Boolean

        /** We handed [msgId] to [peer] less than 30 s ago. */
        fun sentRecently(msgId: String, peer: String): Boolean

        /**
         * We handed [msgId] to [peer] since it last came online over a link that acknowledges the
         * hand-over itself (a BLE write with response, a Wi-Fi Direct TCP write): it has the copy.
         */
        fun handedOverThisSession(msgId: String, peer: String): Boolean

        /** A link of ours ends at [peer] (BLE, Wi-Fi Direct, the hub as the target). */
        fun reachesDirectly(peer: NodeId): Boolean

        /** [dest] is reachable without a hub flood: a radio link, our hub's roster, or the relay. */
        fun reachableElsewhere(dest: NodeId): Boolean
    }

    /** What happens to one bundle in this pass. */
    sealed interface Verdict {
        /** A receipt already confirmed delivery: clear our copy instead. */
        data object Clear : Verdict

        /** Not this pass, for [reason]. */
        data class Hold(val reason: HoldReason) : Verdict

        /** Hand it to the peer ([onWire]). */
        data object Send : Verdict
    }

    /**
     * The verdict for handing [msg] to [peer]. [approved] = the bundles the routing strategy lets this
     * peer carry ([approvedByStrategy], or Q-learning's [qLearningPass]); it only applies to mule traffic.
     */
    fun decide(msg: DtnMessage, peer: Peer, facts: Facts, approved: Set<String>): Verdict {
        val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
        // A receipt already confirmed delivery elsewhere.
        if (!isBroadcast && facts.delivered(msg.id)) return Verdict.Clear
        val p = peer.id.value
        // Never echo back to the original sender.
        if (msg.originNodeId.value == p) return Verdict.Hold(HoldReason.PEER_IS_ORIGIN)
        val isDirectDelivery = msg.destinationNodeId.value == p
        // Relay-only peers never take custody of someone else's DM over the internet.
        if (peer.relay && !isDirectDelivery && !isBroadcast) return Verdict.Hold(HoldReason.NO_RELAY_CUSTODY)
        // A phone reached only through our hub: just the DMs addressed to it.
        if (peer.hubOnly && !isDirectDelivery) return Verdict.Hold(HoldReason.NOT_FOR_HUB_PHONE)
        // The hub: each DM once per hub session (it keeps a copy and replays it to the phone when it
        // registers), and never one whose destination we reach another way right now (that phone's
        // own pass delivers it without flooding it over LoRa).
        if (peer.isHub && !isBroadcast && !isDirectDelivery) {
            if (facts.sentThisSession(msg.id, p)) return Verdict.Hold(HoldReason.HUB_SESSION)
            if (facts.reachableElsewhere(msg.destinationNodeId)) return Verdict.Hold(HoldReason.REACHABLE_ELSEWHERE)
        }
        // Nor back to a previous hop: the header only names the origin, so an A→B→C→D chain would
        // have C bounce the bundle straight back to B. Every phone that handed us a copy counts (a
        // later duplicate from another carrier must not make the first one eligible again). Direct
        // delivery is exempt (the previous hop IS the destination).
        if (!isDirectDelivery && facts.cameFrom(msg.id, p)) return Verdict.Hold(HoldReason.CAME_FROM_PEER)
        // Broadcasts (group messages, announces) go to each peer once per encounter / presence session.
        if (isBroadcast && facts.sentThisSession(msg.id, p)) return Verdict.Hold(HoldReason.BROADCAST_DONE)
        // A carrier copy handed over a link that acknowledged it is with that phone: no re-offer every
        // 30 s while it stays in range (each one cost a BLE connect + write and counted as a forward),
        // only after it has gone offline and come back. Direct deliveries never get here.
        if (!isDirectDelivery && !isBroadcast && !peer.isHub && facts.handedOverThisSession(msg.id, p)) {
            return Verdict.Hold(HoldReason.CARRIER_HAS_IT)
        }
        // Rate limit: never for a direct delivery over a link that ends at the destination. Anything
        // that waits for an end-to-end receipt (mule, same-hub hand-off, relay, hub flood) retries on
        // the 30 s window.
        val directOverRadio = isDirectDelivery && facts.reachesDirectly(peer.id)
        if (!directOverRadio && !isBroadcast && facts.sentRecently(msg.id, p)) return Verdict.Hold(HoldReason.RECENTLY_SENT)
        // The strategy veto applies to relay (mule) traffic only.
        if (!isDirectDelivery && !isBroadcast && !peer.isHub && msg.id !in approved) return Verdict.Hold(HoldReason.ALGORITHM)
        return Verdict.Send
    }

    /** The bundle as it goes out: one more hop. TTL and everything else travel as they are. */
    fun onWire(msg: DtnMessage): DtnMessage = msg.copy(hopCount = (msg.hopCount + 1).coerceAtMost(255))

    /**
     * The bundles [active] lets [peer] carry as a mule, for every strategy but Q-learning (that one
     * decides per bundle with its engine: [qLearningPass]).
     */
    fun approvedByStrategy(
        active: RoutingStrategy,
        snapshot: List<DtnMessage>,
        peer: NodeId,
        onlinePeers: Set<String>,
    ): Set<String> = when (active) {
        is ProphetStrategy -> active.rankForForwardingWithTopology(snapshot, peer, onlinePeers)
        is MaxPropStrategy -> active.rankForForwardingWithTopology(snapshot, peer, onlinePeers)
        is DiMaxPropStrategy -> active.rankForForwardingWithTopology(snapshot, peer, onlinePeers)
        else -> active.rankForForwarding(snapshot, peer)
    }.map { it.message.id }.toSet()

    // ── Q-learning ─────────────────────────────────────────────────────────

    /** Q-learning never relays a bundle with this many hops (the other strategies' limit). */
    const val Q_MAX_HOPS = 10

    /**
     * In the DiMaxProp progress reward η·(C(A,D) − C(B,D)), a side that knows no path counts as this far
     * away (a few hops of `1 + λ`), so moving a bundle to a relay that knows a path earns a bounded
     * reward rather than an infinite one.
     */
    const val UNREACHABLE_COST = 3.0

    /** A Q-learning choice already made for (bundle, peer) in the last 30 s, kept as it was. */
    data class QKept(val action: ForwardingAction, val note: String)

    /** A fresh ε-greedy choice for (bundle, peer): what the orchestrator records, explains and rewards. */
    data class QDecision(
        val msg: DtnMessage,
        val state: ForwardingState,
        val action: ForwardingAction,
        val exploratory: Boolean,
        val diMaxProp: DiMaxPropStrategy.Decision,
        /** (Q_A + Q_B) / 2 of FORWARD and STORE in [state] when the choice was made. */
        val qForward: Double,
        val qStore: Double,
        /** The progress reward of a FORWARD; null for a hold or when neither side knows a path. */
        val reward: Double?,
    )

    /** One Q-learning pass for a peer: the bundles it may carry, the kept choices' notes, the fresh choices. */
    data class QPass(val approved: Set<String>, val keptNotes: Map<String, String>, val fresh: List<QDecision>)

    /**
     * Q-learning's mule decisions for [peer] (DiMaxProp paper §VI). For each bundle it could hand to this
     * carrier: the 12-feature state ([stateFor], given the carrier's DiMaxProp cost to the destination)
     * and the Double-Q engine's ε-greedy FORWARD / STORE. A state the engine has never been rewarded in
     * takes the DiMaxProp rule's choice (a prior, not an override). A choice made in the last 30 s is
     * kept ([kept]). Broadcasts and direct deliveries are approved (they aren't relay decisions);
     * bundles never offered to this carrier (its own, the one it gave us or already took from us this
     * session — [cameFrom] —, past the hop limit) are skipped; control traffic follows the DiMaxProp
     * rule (it never earns a delivery reward).
     */
    suspend fun qLearningPass(
        snapshot: List<DtnMessage>,
        peer: NodeId,
        onlinePeers: Set<String>,
        diMaxProp: DiMaxPropStrategy,
        engine: DoubleQLearningEngine,
        eta: Double,
        cameFrom: (msgId: String, peer: String) -> Boolean,
        kept: (String) -> QKept?,
        stateFor: suspend (DtnMessage, Double) -> ForwardingState,
    ): QPass {
        val approved = mutableSetOf<String>()
        val notes = HashMap<String, String>()
        val fresh = mutableListOf<QDecision>()
        val p = peer.value
        for (msg in snapshot) {
            if (msg.hopCount >= Q_MAX_HOPS) continue
            if (msg.destinationNodeId == NodeId.BROADCAST || msg.destinationNodeId.value == p) {
                approved.add(msg.id) // handled as broadcast / direct by the send loop
                continue
            }
            if (msg.originNodeId.value == p || cameFrom(msg.id, p)) continue
            val dest = msg.destinationNodeId.value
            if (msg.messageType != DtnMessageType.DATA) {
                if (diMaxProp.decide(dest, p, onlinePeers).verdict != DiMaxPropStrategy.Verdict.HOLD) approved.add(msg.id)
                continue
            }
            val k = kept(msg.id)
            if (k != null) {
                if (k.action == ForwardingAction.FORWARD) approved.add(msg.id)
                notes[msg.id] = k.note
                continue
            }
            val dm = diMaxProp.decide(dest, p, onlinePeers)
            val state = stateFor(msg, dm.theirs.cost)
            val prior = if (dm.verdict == DiMaxPropStrategy.Verdict.HOLD) ForwardingAction.STORE else ForwardingAction.FORWARD
            val (qf, qs) = engine.qValues(state)
            val (action, exploratory) = engine.selectActionEpsilonGreedy(state, tieBreak = prior)
            val reward = if (action == ForwardingAction.FORWARD) progressReward(dm.mine, dm.theirs, eta) else null
            if (action == ForwardingAction.FORWARD) approved.add(msg.id)
            fresh.add(QDecision(msg, state, action, exploratory, dm, qf, qs, reward))
        }
        return QPass(approved, notes, fresh)
    }

    /**
     * DiMaxProp progress reward η·(C(A,D) − C(B,D)) (paper eq. 13): positive when the bundle moves to a
     * relay that is closer, in DiMaxProp cost, to its destination. A side that knows no path counts as
     * [UNREACHABLE_COST] away; null when neither knows one (nothing to measure).
     */
    fun progressReward(mine: DiMaxPropStrategy.Path, theirs: DiMaxPropStrategy.Path, eta: Double): Double? {
        if (!mine.reachable && !theirs.reachable) return null
        val a = if (mine.reachable) mine.cost.coerceAtMost(UNREACHABLE_COST) else UNREACHABLE_COST
        val b = if (theirs.reachable) theirs.cost.coerceAtMost(UNREACHABLE_COST) else UNREACHABLE_COST
        return eta * (a - b)
    }
}
