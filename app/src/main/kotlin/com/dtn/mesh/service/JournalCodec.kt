package com.dtn.mesh.service

/**
 * Line format for the persistent message journal (see [MessageLifecycleLog]). Pure JVM code so
 * it is unit-tested. One record per line, tab-separated, fields escaped:
 *
 *  - `E` event: `E \t epochMs \t EVENT \t msgId8 \t origin8 \t dest8 \t hop \t fwd \t ttlMin \t extra`
 *  - `R` route: `R \t msgId8 \t fullId \t source \t dest \t channel \t prev \t me \t next \t delivered
 *               \t updatedMs \t strategy \t createdMs \t lastPath \t outcome \t deliveredMs
 *               \t decision \t decisionMs \t senderStrategy \t groupReceipts \t fallbackVia
 *               \t fallbackAfter` (fields 16 on are optional: older lines without them still load,
 *               and older builds ignore the newer ones). groupReceipts = `member,atMs,path,hop`
 *               entries joined by `;`.
 *
 * Unknown / malformed lines are skipped, so the file can evolve without breaking old installs.
 */
object JournalCodec {

    /** Group receipts as `member,atMs,path,hop;…` (the values never contain `,` or `;`). */
    fun encodeReceipts(list: List<MessageLifecycleLog.GroupReceipt>): String = list.joinToString(";") { g ->
        listOf(g.member, g.atMs.toString(), g.path, g.hop).joinToString(",") { it.replace(",", " ").replace(";", " ") }
    }

    fun decodeReceipts(s: String): List<MessageLifecycleLog.GroupReceipt> =
        if (s.isBlank()) emptyList() else s.split(';').mapNotNull { e ->
            val p = e.split(',')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val member = p[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MessageLifecycleLog.GroupReceipt(member, at, p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty())
        }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    fun encodeEvent(e: LifecycleEntry): String = listOf(
        "E", e.epochMs.toString(), e.event.name, e.msgId, e.origin, e.dest,
        e.hopCount.toString(), e.forwardCount.toString(), e.ttlRemainingMin.toString(), e.extra,
    ).joinToString("\t") { esc(it) }

    fun encodeRoute(r: MessageLifecycleLog.RouteRecord): String = listOf(
        "R", r.msgId, r.fullId, r.source, r.destination, r.channel.toString(), r.prevHop.orEmpty(), r.me,
        r.nextHop.orEmpty(), if (r.delivered) "1" else "0", r.timestampMs.toString(), r.strategy,
        r.createdAtMs.toString(), r.lastPath, r.outcome, r.deliveredAtMs.toString(),
        r.decision, r.decisionAtMs.toString(), r.senderStrategy,
        encodeReceipts(r.groupReceipts), r.fallbackVia, r.fallbackAfter,
    ).joinToString("\t") { esc(it) }

    /** Decoded line: exactly one of [event] / [route] is set. */
    class Decoded(val event: LifecycleEntry? = null, val route: MessageLifecycleLog.RouteRecord? = null)

    fun decode(line: String, formatTime: (Long) -> String): Decoded? {
        val f = line.split('\t').map { unesc(it) }
        return when (f.firstOrNull()) {
            "E" -> {
                if (f.size < 10) return null
                val epoch = f[1].toLongOrNull() ?: return null
                val event = runCatching { LifecycleEvent.valueOf(f[2]) }.getOrNull() ?: return null
                Decoded(event = LifecycleEntry(
                    timestamp = formatTime(epoch), event = event, msgId = f[3], origin = f[4], dest = f[5],
                    hopCount = f[6].toIntOrNull() ?: -1, forwardCount = f[7].toIntOrNull() ?: -1,
                    ttlRemainingMin = f[8].toIntOrNull() ?: -1, extra = f[9], epochMs = epoch,
                ))
            }
            "R" -> {
                if (f.size < 16) return null
                Decoded(route = MessageLifecycleLog.RouteRecord(
                    msgId = f[1], fullId = f[2], source = f[3], destination = f[4],
                    channel = f[5].toIntOrNull() ?: 0, prevHop = f[6].ifEmpty { null }, me = f[7],
                    nextHop = f[8].ifEmpty { null }, delivered = f[9] == "1",
                    timestampMs = f[10].toLongOrNull() ?: 0L, strategy = f[11],
                    createdAtMs = f[12].toLongOrNull() ?: 0L, lastPath = f[13], outcome = f[14],
                    deliveredAtMs = f[15].toLongOrNull() ?: 0L,
                    decision = f.getOrNull(16).orEmpty(),
                    decisionAtMs = f.getOrNull(17)?.toLongOrNull() ?: 0L,
                    senderStrategy = f.getOrNull(18).orEmpty(),
                    groupReceipts = decodeReceipts(f.getOrNull(19).orEmpty()),
                    fallbackVia = f.getOrNull(20).orEmpty(),
                    fallbackAfter = f.getOrNull(21).orEmpty(),
                ))
            }
            else -> null
        }
    }
}
