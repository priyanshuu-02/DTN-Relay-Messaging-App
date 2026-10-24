package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagLine
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.DtnFilterChip
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.SegmentedControl
import com.dtn.mesh.ui.components.TonalButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnSpacing
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

private val TERM_DIM = Color(0xFF94A3B8)
private val TERM_TEXT = Color(0xFFE2E8F0)

/**
 * Console (`dev/console`): the diagnostics log, newest first, in a dark terminal panel. All /
 * Warnings / Errors with counts, source chips (none picked = every source), search, Copy output
 * and Clear view, which only hides the lines on this screen — the log file is never touched.
 * The old "App log" is in here too: those lines have the source App.
 */
@Composable
internal fun ConsolePage(viewModel: DtnViewModel, onBack: () -> Unit) {
    val lines by viewModel.diagnosticsLines.collectAsState()
    var level by rememberSaveable { mutableStateOf(ConsoleLevel.ALL) }
    var picked by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var clearedAt by rememberSaveable { mutableLongStateOf(0L) }
    val sources = remember(picked) { picked.toSet() }
    val counts = remember(lines, sources, query, clearedAt) { ConsoleModel.counts(lines, sources, query, clearedAt) }
    val shown = remember(lines, level, sources, query, clearedAt) { ConsoleModel.visible(lines, level, sources, query, clearedAt) }
    // Every source in the log, plus any picked one whose lines have aged out (so it can be unpicked).
    val chipSources = remember(lines, sources) { ConsoleModel.sourcesIn(lines).let { present -> present + (sources - present.toSet()).sorted() } }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    DevPageFrame(
        "Console", onBack, snackbars, subtitle = "$DEV_SUBTITLE › Console",
        bottomBar = {
            Surface(color = DtnColors.Background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TonalButton("Copy output", enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                            clipboard.setText(AnnotatedString(ConsoleModel.copyText(shown)))
                            // Android 13+ confirms clipboard writes itself.
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                val n = minOf(shown.size, ConsoleModel.COPY_MAX)
                                scope.launch { snackbars.showSnackbar("Copied $n line${if (n == 1) "" else "s"}") }
                            }
                        })
                        Button(
                            onClick = { clearedAt = lines.firstOrNull()?.atMs ?: System.currentTimeMillis() },
                            enabled = lines.any { it.atMs > clearedAt },
                            shape = DtnShapes.Button,
                            colors = ButtonDefaults.buttonColors(containerColor = DtnColors.RedSoft, contentColor = DtnColors.RedText),
                            modifier = Modifier.weight(1f).heightIn(min = DtnSpacing.MainButtonHeight),
                        ) { Text("Clear view", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                    Text("Clear view only hides what's on this screen. The log file keeps every line (Export Data › Export all logs).",
                        style = DtnType.Meta, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoNote("This app's diagnostics log: every line has a level and a source, and never holds message text or keys. " +
                "The last 2,000 lines are here; the whole file is in Export Data › Export all logs.")
            SegmentedControl(
                ConsoleLevel.entries.map { "${it.label} (${counts.of(it)})" },
                level.ordinal, { level = ConsoleLevel.entries[it] },
            )
            DtnSearchField(query, { query = it }, "Search the log")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DtnFilterChip("All sources", selected = sources.isEmpty(), onClick = { picked = ArrayList() })
                chipSources.forEach { s ->
                    DtnFilterChip(ConsoleModel.sourceLabel(s), selected = s in sources, onClick = {
                        picked = ArrayList(if (s in sources) sources - s else sources + s)
                    })
                }
            }
            if (clearedAt > 0L) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("View cleared at ${ConsoleModel.time(clearedAt)}", style = DtnType.Meta, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clearedAt = 0L }) { Text("Show all again") }
                }
            }
            Surface(color = DtnColors.Terminal, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (shown.isEmpty()) {
                        item {
                            TerminalText(
                                when {
                                    lines.isEmpty() -> "No lines yet."
                                    clearedAt > 0L && counts.all == 0 -> "View cleared. New lines show up here."
                                    else -> "No lines match."
                                },
                                TERM_DIM,
                            )
                        }
                    } else {
                        items(shown) { TerminalLine(it) }
                    }
                }
            }
        }
    }
}

/** "[21:04:28]" grey, the level in its colour, the source, then the text. */
@Composable
private fun TerminalLine(l: DiagLine) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = TERM_DIM)) { append("[${ConsoleModel.time(l.atMs)}] ") }
            withStyle(SpanStyle(color = levelColor(l.level), fontWeight = FontWeight.Bold)) { append(l.level.name) }
            withStyle(SpanStyle(color = TERM_DIM)) { append(" ${l.source} · ") }
            withStyle(SpanStyle(color = TERM_TEXT)) { append(l.text) }
        },
        fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp, overflow = TextOverflow.Clip,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun TerminalText(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}

/** INFO cyan, WARN amber, ERROR red, DEBUG grey (on the dark panel). */
private fun levelColor(level: DiagLevel): Color = when (level) {
    DiagLevel.DEBUG -> TERM_DIM
    DiagLevel.INFO -> Color(0xFF67E8F9)
    DiagLevel.WARN -> Color(0xFFFBBF24)
    DiagLevel.ERROR -> Color(0xFFF87171)
}
