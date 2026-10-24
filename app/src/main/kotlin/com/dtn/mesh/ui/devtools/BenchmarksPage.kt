package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.learning.BenchmarkStat
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.Bar
import com.dtn.mesh.ui.components.BarChart
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DestructiveButton
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.StatusDot
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

private val ROW_HEIGHT = 48.dp
private val NAME_COL = 116.dp
private val NUM_COL = 76.dp

/** The table's number columns: header and cell text. */
private val COLUMNS: List<Pair<String, (BenchmarkStat) -> String>> = listOf(
    "Handled" to { s: BenchmarkStat -> "${s.handled}" },
    "Delivered" to { s: BenchmarkStat -> "${s.deliveries}" },
    "Rate ↓" to { s: BenchmarkStat -> BenchmarkModel.rate(s) },
    "Avg delay" to { s: BenchmarkStat -> BenchmarkModel.delay(s) },
    "Avg hops" to { s: BenchmarkStat -> BenchmarkModel.hops(s) },
    "Forwards" to { s: BenchmarkStat -> "${s.forwards}" },
    "Overhead" to { s: BenchmarkStat -> BenchmarkModel.overhead(s) },
)

/**
 * Benchmarks (`dev/benchmarks`): per algorithm, the direct messages this phone handled (sent or
 * carried), how many it saw delivered, the delivery rate, average delay and hops, forwards and the
 * overhead (forwards per delivery), plus a bar chart of the delivery rates. The counters live in
 * memory since the app started (or the last Reset); nothing is saved.
 */
@Composable
internal fun BenchmarksPage(viewModel: DtnViewModel, onBack: () -> Unit) {
    val stats by viewModel.benchmarks.collectAsState()
    val active by viewModel.activeStrategy.collectAsState()
    val rows = remember(stats) { BenchmarkModel.sorted(stats) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    DevPageFrame("Benchmarks", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "note") {
                Spacer(Modifier.height(4.dp))
                InfoNote("Using data collected on this phone since the app started. The counters live in memory only: they " +
                    "start again from zero when the app restarts or you tap Reset. Each message counts for the algorithm " +
                    "selected when this phone took it on.")
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(DtnIcons.BarChart, "No data yet",
                        "Send or carry some direct messages. Each algorithm gets a row once it has handled one (now: ${Algorithms.label(active)}).")
                }
            } else {
                item(key = "table") {
                    SectionHeader("By algorithm")
                    BenchmarkTable(rows, active)
                }
                item(key = "chart") {
                    SectionHeader("Delivery rate by strategy")
                    DtnCard {
                        val rated = rows.filter { it.deliveryRate != null }
                        if (rated.isEmpty()) {
                            Text("No delivery rate yet: no algorithm has handled a message.", style = DtnType.RowSubtitle)
                        } else {
                            BarChart(
                                bars = rated.map { s ->
                                    Bar(Algorithms.find(s.strategy)?.short ?: s.strategy, (s.deliveryRate ?: 0.0).toFloat(),
                                        DtnColors.algorithm(s.strategy), BenchmarkModel.rate(s))
                                },
                                max = 1f, axisLabel = "100%",
                            )
                        }
                    }
                }
                val thin = BenchmarkModel.needMoreData(rows)
                if (thin.isNotEmpty()) {
                    item(key = "more") {
                        InfoNote("Need more data: ${thin.joinToString()} handled fewer than ${BenchmarkModel.MIN_HANDLED} messages, " +
                            "so their rate isn't meaningful yet.", kind = NoteKind.CAUTION)
                    }
                }
            }
            item(key = "legend") {
                SectionHeader("What the columns mean")
                DtnCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Legend("Handled", "Direct messages this phone sent or carried. Messages addressed to this phone don't count: " +
                            "other phones routed them.")
                        Legend("Delivered", "Of those, the ones this phone saw arrive: handed straight to the recipient, or " +
                            "confirmed by the recipient's delivery receipt.")
                        Legend("Rate", "Delivered ÷ handled. Messages still on their way count as not delivered yet.")
                        Legend("Avg delay", "From when a message was written until this phone saw it delivered (a receipt " +
                            "has to travel back too).")
                        Legend("Avg hops", "Hops of the messages this phone handed straight to the recipient.")
                        Legend("Forwards · Overhead", "Copies handed on over a radio link, and forwards per delivery " +
                            "(lower = less airtime per delivered message).")
                    }
                }
            }
            item(key = "reset") {
                DestructiveButton("Reset results", onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset the benchmarks?",
            text = "Every algorithm's counters start again from zero. Messages, routes and the routing tables aren't touched.",
            confirmLabel = "Reset",
            onConfirm = { confirmReset = false; viewModel.resetBenchmarks() },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * The table: algorithm names (colour dot) in a fixed column, the numbers scroll sideways. The best
 * rate is the top row, tinted soft blue; the algorithm selected now is in bold.
 */
@Composable
private fun BenchmarkTable(rows: List<BenchmarkStat>, active: String) {
    val activeStat = Algorithms.find(active)?.statName
    DtnCard(contentPadding = PaddingValues(vertical = 8.dp)) {
        Row {
            Column(Modifier.width(NAME_COL)) {
                HeaderCell("Strategy", NAME_COL, TextAlign.Start)
                rows.forEachIndexed { i, s -> NameCell(s, top = i == 0, isActive = s.strategy == activeStat) }
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Column {
                    Row { COLUMNS.forEach { (title, _) -> HeaderCell(title, NUM_COL, TextAlign.End) } }
                    rows.forEachIndexed { i, s ->
                        val tint = if (i == 0) DtnColors.PrimarySoft else Color.Transparent
                        Row(Modifier.background(tint).height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                            COLUMNS.forEachIndexed { c, (_, cell) ->
                                Text(cell(s), fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = if (c == 2) FontWeight.Bold else FontWeight.Normal,
                                    color = if (c == 2) DtnColors.algorithm(s.strategy) else DtnColors.TextTitle,
                                    textAlign = TextAlign.End, maxLines = 1,
                                    modifier = Modifier.width(NUM_COL).padding(end = 12.dp))
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(top = 4.dp))
        Text("Swipe the numbers sideways for every column.", style = DtnType.Meta, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextSubtitle, letterSpacing = 0.5.sp,
        textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).height(32.dp).padding(start = if (align == TextAlign.Start) 12.dp else 0.dp,
            end = if (align == TextAlign.End) 12.dp else 0.dp, top = 8.dp))
}

@Composable
private fun NameCell(s: BenchmarkStat, top: Boolean, isActive: Boolean) {
    val label = Algorithms.label(s.strategy)
    Row(
        modifier = Modifier.width(NAME_COL).height(ROW_HEIGHT).background(if (top) DtnColors.PrimarySoft else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: handled ${s.handled}, delivered ${s.deliveries}, rate ${BenchmarkModel.rate(s)}" +
                    if (isActive) ", selected now" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(DtnColors.algorithm(s.strategy))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold, color = DtnColors.TextTitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isActive) Text("selected now", style = DtnType.Meta.copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

@Composable
private fun Legend(title: String, text: String) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
        Text(text, style = DtnType.Meta)
    }
}
