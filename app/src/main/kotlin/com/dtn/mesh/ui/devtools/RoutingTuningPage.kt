package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dtn.mesh.settings.SettingsRepository
import com.dtn.mesh.ui.AlgorithmFormulas
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DestructiveButton
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.RadioCard
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.ValueSlider
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Routing & Tuning (`dev/tuning`): the ONLY algorithm switch — six radio cards, applied at once and
 * not saved (the app starts on PRoPHET) — the λ / η sliders (saved with Save changes), Reset to
 * defaults (PRoPHET, λ 0.50, η 0.20), Benchmarks › and the selected algorithm's formulas with
 * their live constants.
 */
@Composable
internal fun RoutingTuningPage(viewModel: DtnViewModel, onBack: () -> Unit, onOpenBenchmarks: () -> Unit) {
    val active by viewModel.activeStrategy.collectAsState()
    val savedLambda by viewModel.diMaxPropLambda.collectAsState()
    val savedEta by viewModel.qShapingEta.collectAsState()
    var lambda by remember(savedLambda) { mutableStateOf(savedLambda.toFloat()) }
    var eta by remember(savedEta) { mutableStateOf(savedEta.toFloat()) }
    val changed = abs(lambda - savedLambda.toFloat()) > 1e-4f || abs(eta - savedEta.toFloat()) > 1e-4f
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var formulasOpen by rememberSaveable { mutableStateOf(true) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    // The constants are live (ε decays, λ follows Settings): re-read every few seconds.
    val formulas by produceState<AlgorithmFormulas?>(null, active, savedLambda, savedEta) {
        while (true) {
            value = viewModel.algorithmFormulas()
            delay(5_000)
        }
    }

    DevPageFrame(
        "Routing & Tuning", onBack, snackbars,
        bottomBar = {
            DevBottomBar {
                OutlineButton("Cancel", onClick = { lambda = savedLambda.toFloat(); eta = savedEta.toFloat() },
                    enabled = changed, modifier = Modifier.weight(1f))
                PrimaryButton("Save changes", onClick = { viewModel.saveRoutingTuning(lambda.toDouble(), eta.toDouble()) },
                    enabled = changed, modifier = Modifier.weight(1f))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "protocol") {
                SectionHeader("Routing protocol")
                DtnCard(contentPadding = PaddingValues(12.dp)) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Algorithms.all.chunked(2).forEach { pair ->
                            // Both cards of a row as tall as the taller one.
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { a ->
                                    RadioCard(
                                        title = a.label, subtitle = a.description, selected = active == a.type,
                                        onClick = { viewModel.setStrategy(a.type) },
                                        accent = DtnColors.algorithm(a.type), modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Applies right away, to the next forwarding decision. It isn't saved: the app starts on PRoPHET again " +
                        "after a restart. Every algorithm keeps learning in the background, so switching loses nothing.",
                        style = DtnType.Meta)
                }
            }
            item(key = "params") {
                SectionHeader("Experimental parameters")
                DtnCard {
                    ValueSlider("DiMaxProp distance weight λ", lambda, { lambda = it }, color = DtnColors.Primary,
                        description = "0 = plain MaxProp. Higher = prefer carriers that were physically closer (BLE distance).")
                    Spacer(Modifier.height(12.dp))
                    ValueSlider("Q-learning progress reward η", eta, { eta = it }, color = DtnColors.Purple,
                        description = "Reward per forward for moving a message closer to its destination (DiMaxProp cost). 0 = off.")
                    if (changed) {
                        Spacer(Modifier.height(8.dp))
                        Text("Not saved yet: tap Save changes.", style = DtnType.Meta.copy(color = DtnColors.OrangeText))
                    }
                }
            }
            item(key = "formulas") {
                SectionHeader("Formulas & constants")
                FormulasCard(active, formulas, open = formulasOpen, onToggle = { formulasOpen = !formulasOpen })
            }
            item(key = "benchmarks") {
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Benchmarks", icon = DtnIcons.BarChart, subtitle = "Compare the algorithms on this phone",
                        iconTint = DtnColors.Green, iconBackground = DtnColors.GreenSoft, onClick = onOpenBenchmarks)
                }
            }
            item(key = "reset") {
                DtnCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reset to defaults", style = DtnType.RowTitle)
                            Text("PRoPHET, λ %.2f, η %.2f".format(SettingsRepository.DEFAULT_LAMBDA, SettingsRepository.DEFAULT_ETA),
                                style = DtnType.RowSubtitle)
                        }
                        Spacer(Modifier.width(8.dp))
                        DestructiveButton("Reset", onClick = { confirmReset = true }, showIcon = false)
                    }
                }
            }
            item(key = "note") {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(DtnIcons.Info, contentDescription = null, tint = DtnColors.Grey, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("λ and η are saved on this phone and apply right away to the running algorithms. They only change how " +
                        "this phone picks carriers; other phones use their own settings.", style = DtnType.Meta)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset routing to the defaults?",
            text = "The algorithm goes back to PRoPHET, λ to %.2f and η to %.2f. Learned routing tables and the benchmark counters stay."
                .format(SettingsRepository.DEFAULT_LAMBDA, SettingsRepository.DEFAULT_ETA),
            confirmLabel = "Reset",
            onConfirm = { confirmReset = false; viewModel.resetRoutingDefaults() },
            onDismiss = { confirmReset = false },
        )
    }
}

/** The selected algorithm's formulas in monospace and its live constants; the header folds it. */
@Composable
private fun FormulasCard(active: String, f: AlgorithmFormulas?, open: Boolean, onToggle: () -> Unit) {
    DtnCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide the formulas" else "Show the formulas", role = Role.Button, onClick = onToggle)
                .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Algorithms.label(active), style = DtnType.RowTitle.copy(color = DtnColors.algorithm(active)))
                Text("How it scores carriers, with the constants it runs with now", style = DtnType.Meta)
            }
            Text(if (open) "▾" else "▸", style = DtnType.RowTitle.copy(color = DtnColors.TextSubtitle))
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f == null) {
                    Text("No formulas for this algorithm.", style = DtnType.RowSubtitle)
                } else {
                    f.formulas.forEach { item ->
                        Column {
                            Text(item.label, style = DtnType.Meta)
                            Text(item.formula, style = DtnType.Mono)
                        }
                    }
                    Surface(shape = DtnShapes.Button, color = DtnColors.GreySoft, modifier = Modifier.fillMaxWidth()) {
                        Text(f.constantsLine, style = DtnType.Mono.copy(color = DtnColors.TextSubtitle), modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}
