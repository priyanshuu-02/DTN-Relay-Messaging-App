package com.dtn.mesh.routing

import com.dtn.mesh.learning.QLearningConfig
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.service.HoldReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Multi-hop audit of the six routing algorithms on the app's own routing code ([MeshSim]: one real
 * strategy set per phone, real summaries, the real carrier rules and wire codec).
 *
 * Training, 2 h with the housekeeping worker aging the tables every 15 min: B–C, C–D and D–E meet every
 * 15 min (often), G–E every 45 min (sometimes), A–F every 45 min, H meets everyone once early on
 * (rarely). F never meets B, C, D, E or G. Message: A creates a DM for E 5 min later, then the contacts
 * {A,B,F} → {B,C,G} → {C,D,H} → {D,E}, 10 min apart; E's receipt then travels back through
 * {C,D,H} → {B,C,G} → {A,B,F}.
 */
class MultiHopRoutingTest {

    companion object {
        const val S1 = "1 {A,B,F}"
        const val S2 = "2 {B,C,G}"
        const val S3 = "3 {C,D,H}"
        const val S4 = "4 {D,E}"
        const val R1 = "receipt {C,D,H}"
        const val R2 = "receipt {B,C,G}"
        const val R3 = "receipt {A,B,F}"
        const val LEFT = "2' {A,C,G}"
        const val PAIR = "{A,X,Y}"
        val MESSAGE_STEPS = setOf(S1, S2, S3, S4)

        /** The training history above. */
        fun train(sim: MeshSim) {
            for (round in 0 until 8) {
                val start = sim.now
                sim.meet("D", "E"); sim.minutes(1)
                sim.meet("C", "D"); sim.minutes(1)
                sim.meet("B", "C"); sim.minutes(1)
                if (round % 3 == 1) { sim.meet("G", "E"); sim.minutes(1) }
                if (round % 3 == 0) { sim.meet("A", "F"); sim.minutes(1) }
                when (round) {
                    0 -> { sim.meet("H", "E"); sim.minutes(1); sim.meet("H", "G"); sim.minutes(1) }
                    1 -> { sim.meet("H", "D"); sim.minutes(1); sim.meet("H", "C"); sim.minutes(1) }
                    2 -> { sim.meet("H", "B"); sim.minutes(1); sim.meet("H", "A"); sim.minutes(1); sim.meet("H", "F"); sim.minutes(1) }
                }
                sim.advance(start + 15 * 60_000L - sim.now)
            }
        }
    }

    class Run(val sim: MeshSim, val id: String)

    private fun scenario(type: StrategyType, qSeed: Long = 1L, qEpsilon: Double? = 0.0, receipts: Boolean = true): Run {
        val sim = MeshSim(type, qSeed = qSeed, qEpsilon = qEpsilon)
        train(sim)
        sim.minutes(5)
        val id = sim.dm("A", "E")
        sim.contact("A", "B", "F", step = S1); sim.minutes(10)
        sim.contact("B", "C", "G", step = S2); sim.minutes(10)
        sim.contact("C", "D", "H", step = S3); sim.minutes(10)
        sim.contact("D", "E", step = S4)
        if (receipts) {
            sim.minutes(10); sim.contact("C", "D", "H", step = R1)
            sim.minutes(10); sim.contact("B", "C", "G", step = R2)
            sim.minutes(10); sim.contact("A", "B", "F", step = R3)
        }
        return Run(sim, id)
    }

    // ── What every algorithm must keep ─────────────────────────────────────

    /** The carrier rules, whatever the algorithm decided. */
    private fun assertCarrierRules(r: Run, origin: String = "A") {
        val sim = r.sim
        val sends = sim.sends
        val created = sim.created.getValue(r.id)
        // A phone that holds or held the message never stores it again (duplicates are refused).
        val stored = sends.filter { it.stored }.groupingBy { it.to }.eachCount()
        assertTrue("stored twice: $stored", stored.values.all { it == 1 })
        for ((i, s) in sends.withIndex()) {
            // How the sender got its copy (null = it created it).
            val got = sends.firstOrNull { it.to == s.from && it.stored && it.atMs <= s.atMs }
            if (!s.direct) {
                // No ping-pong: never back to the origin or to a phone it came from.
                assertTrue("${s.from}→${s.to} back to the origin", s.to != origin)
                assertFalse("${s.from}→${s.to} back to a previous hop",
                    sends.take(i).any { it.from == s.to && it.to == s.from })
            }
            // One more hop per hand-over.
            assertEquals("${s.from}→${s.to} hop", (got?.hop ?: created.hopCount) + 1, s.hop)
            // TTL only counts down: what the sender's copy had left, minus the time it held it.
            val hadMin = got?.ttlMin?.toLong() ?: (created.ttlMs / 60_000L)
            val heldMs = s.atMs - (got?.atMs ?: created.createdAtMs)
            assertEquals("${s.from}→${s.to} TTL", ((hadMin * 60_000L - heldMs).coerceAtLeast(0L) / 60_000L).toInt(), s.ttlMin)
            assertTrue(s.ttlMin <= hadMin)
        }
        // No hub and no relay here: the 5 s fallback never adds a path.
        assertEquals(0, sim.fallbackPaths)
    }

    private fun pathOf(r: Run, to: String = "E"): String {
        val p = r.sim.path(r.id, to)
        return if (p.isEmpty()) "—" else (listOf(p.first().from) + p.map { it.to }).joinToString("→")
    }

    private fun assertDelivered(r: Run, path: String) {
        assertTrue("not delivered", r.sim.delivered(r.id, "E"))
        assertEquals(path, pathOf(r))
        assertEquals(path.count { it == '→' }, r.sim.path(r.id, "E").last().hop)
    }

    /** E's receipt reached every carrier: no copy is left anywhere. */
    private fun assertReceiptsCleared(r: Run) {
        assertEquals(emptyList<String>(), r.sim.phones.values.filter { it.holds(r.id) }.map { it.label })
    }

    private fun mule(r: Run) = r.sim.sends.filter { !it.direct && it.step in MESSAGE_STEPS }
    private fun storedAt(r: Run, label: String) = r.sim.sends.any { it.to == label && it.stored }

    // ── The six algorithms ────────────────────────────────────────────────

    @Test
    fun prophet_findsTheChainAndCopiesOnlyToBetterCarriersOnceItKnowsAPath() = prophetLike(StrategyType.PROPHET)

    @Test
    fun stableProphet_findsTheChainAndCopiesOnlyToBetterCarriersOnceItKnowsAPath() = prophetLike(StrategyType.STABLE)

    private fun prophetLike(type: StrategyType) {
        val r = scenario(type)
        assertDelivered(r, "A→B→C→D→E")
        assertCarrierRules(r)
        assertReceiptsCleared(r)
        val maxForwards = ProphetConfig().maxForwards
        for (s in mule(r)) {
            val m = s.metric!!
            // The live path's next hop, a better carrier (RFC 6693 GRTR), or the stage-1 spray.
            assertTrue("${s.from}→${s.to}: ${s.why}",
                m.nextHop == s.to || m.theirs!! > m.mine!! || (m.nextHop == null && m.copiesSoFar <= maxForwards))
        }
        // Never turns a strictly better carrier away (GRTR), unless E itself is in range.
        for (h in r.sim.algorithmDeclines()) {
            val m = h.metric!!
            assertTrue("${h.at}↛${h.peer}: ${h.why}", m.theirs!! <= m.mine!! || m.nextHop == "E")
        }
        // B gives G a copy: G meets E itself, so P(G,E) beats P(B,E) though the live path goes via C.
        assertTrue(mule(r).any { it.step == S2 && it.from == "B" && it.to == "G" })
        // H met E once, two hours ago: aged below C's P, declined.
        assertTrue(r.sim.algorithmDeclines().any { it.step == S3 && it.at == "C" && it.peer == "H" })
        // F gets a copy only from the stage-1 spray (no path known yet): the design trade-off.
        for (s in mule(r).filter { it.to == "F" }) {
            val m = s.metric!!
            assertEquals(null, m.nextHop)
            assertTrue(m.copiesSoFar <= maxForwards)
        }
    }

    @Test
    fun maxProp_deliversOverTheCheapestPathAmongThePhonesInRange() {
        val r = scenario(StrategyType.MAXPROP)
        assertDelivered(r, "A→B→G→C→D→E")
        assertCarrierRules(r)
        assertReceiptsCleared(r)
        // Every mule copy went to the first hop of the cheapest path among the phones in range.
        for (s in mule(r)) assertEquals("${s.from}→${s.to}: ${s.why}", s.to, s.metric!!.nextHop)
        for (h in r.sim.algorithmDeclines()) assertTrue("${h.at}↛${h.peer}: ${h.why}", h.metric!!.nextHop != h.peer)
        // C's cheapest path to E goes through D (not H, who met E once two hours ago).
        assertTrue(mule(r).any { it.step == S3 && it.from == "C" && it.to == "D" })
        assertTrue(r.sim.algorithmDeclines().any { it.step == S3 && it.at == "C" && it.peer == "H" })
    }

    @Test
    fun diMaxProp_forwardsOnlyToCarriersCloserToTheDestination() {
        costRule(StrategyType.DIMAXPROP)
    }

    @Test
    fun qLearning_atEpsilonZero_followsTheDiMaxPropPrior() {
        val r = costRule(StrategyType.QLEARNING)
        // A fresh table: every choice is the DiMaxProp rule's (both Q values equal).
        for ((_, d) in r.sim.qChoices.filter { it.value.msg.id == r.id && it.value.qForward == it.value.qStore }) {
            val prior = if (d.diMaxProp.verdict == DiMaxPropStrategy.Verdict.HOLD) "STORE" else "FORWARD"
            assertEquals(prior, d.action.name)
        }
    }

    private fun costRule(type: StrategyType): Run {
        val r = scenario(type)
        assertDelivered(r, "A→B→C→D→E")
        assertCarrierRules(r)
        assertReceiptsCleared(r)
        for (s in mule(r)) {
            val m = s.metric!!
            val mine = m.mine!!
            val theirs = m.theirs!!
            // Closer to E than this phone, or the first hop of its own cheapest path.
            assertTrue("${s.from}→${s.to}: ${s.why}", theirs < mine || m.nextHop == s.to || !mine.isFinite())
        }
        for (h in r.sim.algorithmDeclines()) {
            val m = h.metric!!
            assertTrue("${h.at}↛${h.peer}: ${h.why}", m.theirs!! >= m.mine!! && m.nextHop != h.peer)
        }
        // F (A's neighbour, never near E) is never chosen: its cost isn't below A's.
        assertFalse(storedAt(r, "F"))
        return r
    }

    @Test
    fun epidemic_copiesToEveryCarrierItMeets() {
        val r = scenario(StrategyType.EPIDEMIC)
        assertDelivered(r, "A→B→C→D→E")
        assertCarrierRules(r)
        assertReceiptsCleared(r)
        for (p in listOf("B", "C", "D", "F", "G", "H")) assertTrue("$p has no copy", storedAt(r, p))
        assertTrue(r.sim.algorithmDeclines().isEmpty())
    }

    // ── Extra cases ───────────────────────────────────────────────────────

    /** A meets E and B at once, right after creating the DM. */
    private fun directRun(type: StrategyType): Run {
        val sim = MeshSim(type)
        train(sim)
        sim.minutes(5)
        val id = sim.dm("A", "E")
        sim.contact("A", "B", "E", step = "{A,B,E}")
        return Run(sim, id)
    }

    @Test
    fun aDestinationInRange_getsItDirectly_noDetour() {
        for (type in StrategyType.values()) {
            val r = directRun(type)
            assertTrue(type.name, r.sim.delivered(r.id, "E"))
            val toE = r.sim.sends.first { it.to == "E" }
            assertEquals(type.name, "A", toE.from)
            assertEquals(type.name, 1, toE.hop)
            // A knowledge-based algorithm makes no detour copy (DiMaxProp's "destination online →
            // hold" applies to the other phones only). Epidemic copies to everyone it meets.
            if (type != StrategyType.EPIDEMIC) assertFalse(type.name, storedAt(r, "B"))
        }
    }

    /** No training at all: nobody knows any path. */
    private fun coldRun(type: StrategyType): Run {
        val sim = MeshSim(type)
        val id = sim.dm("A", "E")
        sim.contact("A", "B", "F", step = S1); sim.minutes(10)
        sim.contact("B", "C", "G", step = S2); sim.minutes(10)
        sim.contact("C", "D", "H", step = S3); sim.minutes(10)
        sim.contact("D", "E", step = S4)
        return Run(sim, id)
    }

    @Test
    fun coldStart_withNoPathKnown_explorationStillDelivers() {
        for (type in StrategyType.values()) {
            val r = coldRun(type)
            val sim = r.sim
            assertTrue(type.name, sim.delivered(r.id, "E"))
            assertCarrierRules(r)
            // The first hand-over can't be based on any knowledge: A knows no path yet.
            val first = sim.sends.first()
            when (type) {
                StrategyType.PROPHET, StrategyType.STABLE -> assertEquals(null, first.metric!!.nextHop)
                StrategyType.MAXPROP -> assertEquals(null, first.metric!!.mine)
                StrategyType.DIMAXPROP -> assertEquals("EXPLORE", first.metric!!.verdict)
                StrategyType.QLEARNING -> assertEquals("EXPLORE", sim.qChoices.values.first { it.msg.id == r.id }.diMaxProp.verdict.name)
                StrategyType.EPIDEMIC -> Unit
            }
        }
    }

    /** B takes a copy at {A,B,F} and is never seen again; A meets C and G later. */
    private fun leftRun(type: StrategyType, check: (SimPhone, String) -> Unit = { _, _ -> }): Run {
        val sim = MeshSim(type)
        train(sim)
        sim.minutes(5)
        val id = sim.dm("A", "E")
        sim.contact("A", "B", "F", step = S1)
        check(sim["A"], id)
        sim.minutes(10); sim.contact("A", "C", "G", step = LEFT)
        sim.minutes(10); sim.contact("C", "D", "H", step = S3)
        sim.minutes(10); sim.contact("D", "E", step = S4)
        sim.minutes(10); sim.contact("G", "E", step = "{G,E}")
        return Run(sim, id)
    }

    @Test
    fun aCarrierThatLeaves_custodyStaysWithTheEarlierCarrier() {
        for (type in StrategyType.values()) {
            // A handed over a copy, not custody: it still holds one after B left.
            val r = leftRun(type) { a, id -> assertTrue(type.name, a.holds(id)) }
            // A hands it on again: C, G or both, whichever the algorithm picks.
            assertTrue(type.name, r.sim.sends.any { it.step == LEFT && it.from == "A" && it.stored })
            assertTrue(type.name, r.sim.delivered(r.id, "E"))
            assertFalse(type.name, "B" in pathOf(r))
            assertCarrierRules(r)
        }
    }

    /** X and Y always meet E together (the same history, the same numbers); A meets both at once. */
    private fun pairRun(type: StrategyType): MeshSim {
        val sim = MeshSim(type, labels = listOf("A", "X", "Y", "E"))
        repeat(6) { sim.meet("X", "Y", "E", rssi = -70); sim.minutes(5) }
        sim.minutes(5)
        val id = sim.dm("A", "E")
        sim.contact("A", "X", "Y", step = PAIR, rssi = -70)
        sim.minutes(10)
        sim.contact("X", "Y", "E", step = "{X,Y,E}", rssi = -70)
        assertTrue(type.name, sim.delivered(id, "E"))
        return sim
    }

    private fun pairCopies(sim: MeshSim) = sim.sends.filter { it.step == PAIR && it.stored }.map { it.to }

    @Test
    fun twoEquallyGoodCandidates_theChoiceIsDeterministic() {
        for (type in StrategyType.values()) {
            val a = pairRun(type)
            val got = pairCopies(a)
            assertEquals(type.name, got, pairCopies(pairRun(type)))
            assertTrue(type.name, got.isNotEmpty())
            // MaxProp hands a copy to the first hop of its cheapest path (a tie goes to the first one it
            // reaches; as the numbers move during the contact the other can become it too). The others
            // copy to every carrier closer to E than this phone, so both get one at once.
            if (type == StrategyType.MAXPROP) {
                val first = a.sends.first { it.step == PAIR }
                assertEquals(type.name, first.metric!!.nextHop, first.to)
            } else {
                assertEquals(type.name, setOf("X", "Y"), got.toSet())
            }
        }
    }

    /** A DM that has already travelled 8 hops; returns the phones holding it at 10 hops after {C,D,H}. */
    private fun hopLimitRun(type: StrategyType): Pair<Run, List<String>> {
        val sim = MeshSim(type)
        train(sim)
        sim.minutes(5)
        val id = sim.dm("A", "E", hopCount = 8)
        sim.contact("A", "B", "F", step = S1); sim.minutes(10)
        sim.contact("B", "C", "G", step = S2); sim.minutes(10)
        sim.contact("C", "D", "H", step = S3)
        val atLimit = sim.phones.values.filter { it.holds(id) && it.rows.getValue(id).msg.hopCount >= 10 }.map { it.label }
        return Run(sim, id) to atLimit
    }

    @Test
    fun theHopLimit_stopsRelaying_butNotTheDeliveryToTheDestination() {
        for (type in StrategyType.values()) {
            val (r, atLimit) = hopLimitRun(type)
            val sim = r.sim
            assertCarrierRules(r)
            // A copy at 10 hops (the strategies' limit) is never relayed, only handed to its destination.
            for (s in sim.sends) {
                val senderHop = s.hop - 1
                if (senderHop >= 10) assertTrue("${type.name}: ${s.from}→${s.to} relayed at hop $senderHop", s.direct)
            }
            assertTrue(type.name, atLimit.isNotEmpty())
            assertTrue(type.name, sim.held.any { it.reason == HoldReason.ALGORITHM && it.at in atLimit })
            // The limit stops relaying, not the delivery: a 10-hop holder that meets E hands it over.
            sim.minutes(10)
            sim.contact(atLimit.first(), "E", step = "limit meets E")
            assertTrue(type.name, sim.delivered(r.id, "E"))
        }
    }

    /** Q-learning over [runs] seeds at [epsilon] (null = a fresh engine, ε = 1): (delivered, F got a copy). */
    private fun qStats(epsilon: Double?, runs: Int = 40): Pair<Int, Int> {
        var delivered = 0
        var toF = 0
        for (seed in 1..runs) {
            val r = scenario(StrategyType.QLEARNING, qSeed = seed.toLong(), qEpsilon = epsilon, receipts = false)
            if (r.sim.delivered(r.id, "E")) delivered++
            if (storedAt(r, "F")) toF++
        }
        return delivered to toF
    }

    @Test
    fun qLearning_atItsUsualExplorationRate_stillDeliversNearlyAlways() {
        val (delivered, toF) = qStats(QLearningConfig().epsilonMin)
        // ε = 0.05 (the floor it decays to): 1 decision in 20 is a random pick, half of them the wrong
        // way, so each of the ~3 decisive hand-overs fails 2.5 % of the time (≈ 93 % delivered).
        assertTrue("delivered $delivered of 40", delivered >= 34)
        assertTrue("F got a copy in $toF of 40 runs", toF <= 6)
    }

    // ── The results for the report (build/multihop/summary.md) ────────────

    @Test
    fun writesTheResultsTable() {
        val out = StringBuilder()
        out.appendLine("| Algorithm | Delivered | Path | Hops | Copies (carriers) | Sends (dup) | Declined (metric) |")
        out.appendLine("|---|---|---|---|---|---|---|")
        val runs = StrategyType.values().associateWith { scenario(it) }
        for ((type, r) in runs) {
            val p = r.sim.path(r.id, "E")
            val declined = r.sim.algorithmDeclines().filter { it.step in MESSAGE_STEPS }
                .joinToString("; ") { "${it.at}↛${it.peer} (${it.why})" }
            val sends = r.sim.sends.filter { it.step in MESSAGE_STEPS }
            out.appendLine("| ${type.name} | ${if (r.sim.delivered(r.id, "E")) "yes" else "no"} | ${pathOf(r)} | " +
                "${p.lastOrNull()?.hop ?: "—"} | ${r.sim.carriers("A", "E").joinToString(", ")} | " +
                "${sends.size} (${sends.count { !it.stored }}) | ${declined.ifBlank { "—" }} |")
        }
        out.appendLine()
        out.appendLine("| Algorithm | Direct {A,B,E} | Cold start | Carrier left | Two equal | Hop limit |")
        out.appendLine("|---|---|---|---|---|---|")
        for (type in StrategyType.values()) {
            val d = directRun(type)
            val direct = "from ${d.sim.sends.first { it.to == "E" }.from}, hop 1" + if (storedAt(d, "B")) ", B copy" else ""
            val c = coldRun(type)
            val cold = "${pathOf(c)}, copies ${c.sim.carriers("A", "E").joinToString("")}"
            val l = leftRun(type)
            val left = "${pathOf(l)}; A→${l.sim.sends.filter { it.step == LEFT && it.from == "A" && it.stored }.joinToString("") { it.to }}"
            val pair = pairCopies(pairRun(type)).joinToString(" then ")
            val (h, atLimit) = hopLimitRun(type)
            val limit = "held at 10 hops: ${atLimit.joinToString("")}; relays at ≥ 10: ${h.sim.sends.count { it.hop - 1 >= 10 && !it.direct }}"
            out.appendLine("| ${type.name} | $direct | $cold | $left | $pair | $limit |")
        }
        out.appendLine()
        for ((type, r) in runs) {
            out.appendLine("```")
            out.appendLine(type.name)
            out.append(r.sim.trace(r.id, "A", "E"))
            out.appendLine("```")
        }
        val (q5, f5) = qStats(QLearningConfig().epsilonMin)
        val (q100, f100) = qStats(null)
        out.appendLine("Q-learning ε = 0.05: delivered in $q5 of 40 seeds, F got a copy in $f5")
        out.appendLine("Q-learning with a fresh table (ε = 1.0): delivered in $q100 of 40 seeds, F got a copy in $f100")
        File("build/multihop").mkdirs()
        File("build/multihop/summary.md").writeText(out.toString())
        assertTrue(out.isNotEmpty())
    }
}
