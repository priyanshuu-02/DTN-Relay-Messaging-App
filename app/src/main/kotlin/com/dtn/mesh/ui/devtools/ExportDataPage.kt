package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.export.ExportDataset
import com.dtn.mesh.export.ExportFolder
import com.dtn.mesh.export.ExportRange
import com.dtn.mesh.export.ExportResult
import com.dtn.mesh.export.ExportSelection
import com.dtn.mesh.export.RangeChoice
import com.dtn.mesh.export.RangePreset
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.ChipRow
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.queue.shareFile
import com.dtn.mesh.ui.settings.formatBytes
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** History rows shown before "Show all". */
private const val FIRST_EXPORTS = 5

/** What each dataset switch says it writes (never message text). */
private fun ExportDataset.subtitle(): String = when (this) {
    ExportDataset.ENCOUNTERS -> "Every meeting with a phone or hub: times, duration, signal"
    ExportDataset.CONTACTS -> "Each contact's routing state: probabilities, encounters, deliveries. No names"
    ExportDataset.DECISIONS -> "Every forward / wait decision with its state, action and reward"
    ExportDataset.MESSAGES -> "Buffer records: ids, hops, TTL, status and size. Never the text"
    ExportDataset.Q_TABLES -> "Both Q-tables and this run's metadata (JSON)"
    ExportDataset.MESSAGE_EVENTS -> "The message journal's events: ids, hops, TTL, links"
    ExportDataset.ALL_LOGS -> "Diagnostics log, engine console, message journal, this app's system log"
}

private fun ExportDataset.icon(): ImageVector = when (this) {
    ExportDataset.ENCOUNTERS -> DtnIcons.Peers
    ExportDataset.CONTACTS -> DtnIcons.Person
    ExportDataset.DECISIONS -> DtnIcons.Network
    ExportDataset.MESSAGES -> DtnIcons.Chats
    ExportDataset.Q_TABLES -> DtnIcons.BarChart
    ExportDataset.MESSAGE_EVENTS -> DtnIcons.EventLog
    ExportDataset.ALL_LOGS -> DtnIcons.DevTools
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

/**
 * Export Data (`dev/export`): dataset switches, a date range (it limits encounters, decisions,
 * messages and message events), Generate export, Export all logs (the diagnostics log + its
 * backup, the engine console, the message journal and this app's own logcat) and the export
 * history, where Share zips a folder and opens the share sheet. Nothing is uploaded on its own,
 * and no export ever holds message text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportDataPage(viewModel: DtnViewModel, onBack: () -> Unit) {
    var selectionText by rememberSaveable { mutableStateOf(ExportSelection.encode(ExportSelection.DEFAULT)) }
    val selection = remember(selectionText) { ExportSelection.decode(selectionText) }
    var rangeText by rememberSaveable { mutableStateOf(RangeChoice.encode(RangeChoice())) }
    val range = remember(rangeText) { RangeChoice.decode(rangeText) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    /** "generate", "logs" or the name of the folder being zipped; null when idle. */
    var busy by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<ExportFolder>?>(null) }
    var last by remember { mutableStateOf<ExportFolder?>(null) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(Unit) { history = viewModel.exportHistory() }

    fun run(kind: String, job: () -> Deferred<ExportResult>) {
        busy = kind
        scope.launch {
            val r = runCatching { job().await() }.getOrElse { ExportResult(null, "Export failed (${it.javaClass.simpleName})") }
            busy = null
            history = viewModel.exportHistory()
            val folder = r.folder
            if (folder != null) {
                last = folder
                snackbars.showSnackbar(
                    if (r.failed.isEmpty()) "Saved ${folder.files.size} file${if (folder.files.size == 1) "" else "s"} · ${formatBytes(folder.bytes)}"
                    else "Saved, but ${r.failed.joinToString()} couldn't be written",
                )
            } else {
                snackbars.showSnackbar(r.error ?: "Export failed")
            }
        }
    }

    fun share(folder: ExportFolder) {
        busy = folder.name
        scope.launch {
            val zip = viewModel.zipExport(folder)
            busy = null
            if (zip == null || !shareFile(context, zip, "DTN Mesh Relay export ${folder.name}", mimeType = "application/zip",
                    chooserTitle = "Share export")) {
                snackbars.showSnackbar("Couldn't share this export")
            }
        }
    }

    DevPageFrame("Export Data", onBack, snackbars) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "note") {
                Spacer(Modifier.height(4.dp))
                InfoNote("Nothing is uploaded automatically. Exports are written to this app's storage, and Share sends one as a " +
                    ".zip through the app you pick. No export ever holds message text.")
            }
            item(key = "datasets") {
                SectionHeader("Choose data to export")
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    ExportDataset.entries.forEach { d ->
                        SettingRow(
                            d.label, icon = d.icon(), subtitle = d.subtitle(),
                            checked = d in selection.datasets,
                            onCheckedChange = { on -> selectionText = ExportSelection.encode(selection.with(d, on)) },
                        )
                    }
                }
            }
            item(key = "range") {
                SectionHeader("Date range")
                RangeCard(range, zone, enabled = selection.usesRange,
                    onPreset = { p -> rangeText = RangeChoice.encode(range.copy(preset = p)) },
                    onPick = { picking = it })
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (busy == "generate") "Generating…" else "Generate export",
                        enabled = busy == null && !selection.isEmpty,
                        onClick = { run("generate") { viewModel.startExport(selection, range.resolve(System.currentTimeMillis(), zone)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlineButton(
                        if (busy == "logs") "Exporting logs…" else "Export all logs",
                        enabled = busy == null,
                        onClick = { run("logs") { viewModel.startExportAllLogs() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Export all logs saves the diagnostics log (and its backup), the engine console, the message journal and " +
                        "this app's own Android system log, whole, into one folder.", style = DtnType.Meta,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            last?.let { f ->
                item(key = "last") {
                    DtnCard {
                        Text("Saved to", style = DtnType.Meta)
                        Text(f.path, style = DtnType.Mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${f.files.size} files · ${formatBytes(f.bytes)}", style = DtnType.RowSubtitle, modifier = Modifier.weight(1f))
                            ShareButton(enabled = busy == null, sharing = busy == f.name, label = "Share this export", onClick = { share(f) })
                        }
                    }
                }
            }
            item(key = "history") {
                val list = history
                SectionHeader("Export history" + (list?.let { " · ${it.size}" } ?: ""))
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    when {
                        list == null -> Text("Loading…", style = DtnType.RowSubtitle, modifier = Modifier.padding(16.dp))
                        list.isEmpty() -> Text("No exports yet.", style = DtnType.RowSubtitle, modifier = Modifier.padding(16.dp))
                        else -> {
                            val shown = if (showAll) list else list.take(FIRST_EXPORTS)
                            shown.forEachIndexed { i, f ->
                                if (i > 0) HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(horizontal = 12.dp))
                                HistoryRow(f, zone, busy = busy, onShare = { share(f) })
                            }
                            if (list.size > FIRST_EXPORTS) {
                                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (showAll) "Show fewer" else "Show all ${list.size}")
                                }
                            }
                        }
                    }
                }
                Text("On the phone: Android/data/com.dtn.mesh/files/exports/ (adb pull works too).", style = DtnType.Meta,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val which = picking
    if (which != null) {
        val today = LocalDate.now(zone)
        val initial = (if (which == "from") range.fromDay else range.toDay) ?: today
        val state = rememberDatePickerState(initialSelectedDateMillis = ExportRange.pickerMillis(initial))
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = ExportRange.pickerDay(ms)
                        rangeText = RangeChoice.encode(if (which == "from") range.copy(fromDay = day) else range.copy(toDay = day))
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** The range chips, From / To for Custom, and the range it resolves to now. */
@Composable
private fun RangeCard(range: RangeChoice, zone: ZoneId, enabled: Boolean, onPreset: (RangePreset) -> Unit, onPick: (String) -> Unit) {
    DtnCard {
        ChipRow(RangePreset.entries.map { it.label }, range.preset.ordinal, { onPreset(RangePreset.entries[it]) })
        if (range.preset == RangePreset.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            val today = LocalDate.now(zone)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("From ${(range.fromDay ?: range.toDay ?: today).format(dayFormat)}", onClick = { onPick("from") },
                    modifier = Modifier.weight(1f))
                OutlineButton("To ${(range.toDay ?: today).format(dayFormat)}", onClick = { onPick("to") }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        val r = range.resolve(System.currentTimeMillis(), zone)
        Text(
            if (range.preset == RangePreset.ALL) "Everything this phone has."
            else "${stamp(r.fromMs, zone)} → ${stamp(r.toMs, zone)}",
            style = DtnType.RowSubtitle.copy(color = DtnColors.TextTitle),
        )
        Text(
            if (enabled) "Limits encounters, forwarding decisions, messages and message events. Contacts, Q-tables and logs are " +
                "always exported whole."
            else "None of the data switched on has dates: it's exported whole.",
            style = DtnType.Meta,
        )
    }
}

/** One export folder: when, what, its size, a Logs chip for Export all logs, and Share. */
@Composable
private fun HistoryRow(f: ExportFolder, zone: ZoneId, busy: String?, onShare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stamp(f.createdAtMs, zone), style = DtnType.RowTitle.copy(fontSize = 15.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (f.logsOnly) {
                    Spacer(Modifier.width(6.dp))
                    StatusChip("Logs", Tone.PURPLE)
                }
            }
            Text("${f.files.size} file${if (f.files.size == 1) "" else "s"} · ${formatBytes(f.bytes)} · ${f.name}", style = DtnType.Meta,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ShareButton(enabled = busy == null, sharing = busy == f.name, label = "Share the export of ${stamp(f.createdAtMs, zone)}",
            onClick = onShare)
    }
}

@Composable
private fun ShareButton(enabled: Boolean, sharing: Boolean, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
        Icon(DtnIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (sharing) "Zipping…" else "Share")
    }
}

private fun stamp(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(stampFormat)
