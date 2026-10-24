package com.dtn.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnTheme
import com.dtn.mesh.ui.theme.DtnType
import kotlin.math.roundToInt

/** Filter chip: blue + white text when selected, soft grey-blue + navy otherwise. */
@Composable
fun DtnFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        // 36 dp to look at, 48 dp to touch.
        modifier = modifier.minimumInteractiveComponentSize().clip(DtnShapes.Pill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = DtnShapes.Pill,
        color = if (selected) DtnColors.Primary else DtnColors.Track,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else DtnColors.TextTitle,
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
    }
}

/** A horizontally scrolling row of [DtnFilterChip]s. */
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label -> DtnFilterChip(label, selected = i == selected, onClick = { onSelect(i) }) }
    }
}

/** 2–3 equal parts in a grey track; the selected part is blue. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clip(DtnShapes.Pill).background(DtnColors.Track).padding(3.dp).selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(DtnShapes.Pill)
                    .background(if (isSel) DtnColors.Primary else Color.Transparent)
                    .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isSel) Color.White else DtnColors.TextTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Soft grey-blue search pill with a 🔍 icon. */
@Composable
fun DtnSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = DtnColors.TextHint, maxLines = 1) },
        leadingIcon = { Icon(DtnIcons.Search, contentDescription = null, tint = DtnColors.TextHint) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(DtnIcons.Close, contentDescription = "Clear search", tint = DtnColors.TextSubtitle)
                }
            }
        } else null,
        shape = DtnShapes.Pill,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = DtnColors.Track,
            unfocusedContainerColor = DtnColors.Track,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Labelled 0–1 slider with its value in a small outlined box and the range ends underneath.
 * Values snap to [steps] + 1 intervals (0.05 by default).
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DtnColors.Primary,
    description: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 19,
) {
    val span = valueRange.endInclusive - valueRange.start
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(8.dp), color = DtnColors.Card, border = BorderStroke(1.dp, DtnColors.CardBorder)) {
                Text("%.2f".format(value), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Slider(
            value = value,
            onValueChange = { v ->
                val intervals = steps + 1
                val snapped = valueRange.start + ((v - valueRange.start) / span * intervals).roundToInt() * span / intervals
                onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
            },
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, activeTickColor = Color.White),
            modifier = Modifier.semantics { contentDescription = label },
        )
        Row {
            Text("%.2f".format(valueRange.start), style = DtnType.Meta, modifier = Modifier.weight(1f))
            Text("%.2f".format(valueRange.endInclusive), style = DtnType.Meta)
        }
        if (description != null) Text(description, style = DtnType.Meta, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One bar of [BarChart]. [value] is 0..max; [valueLabel] is printed on top. */
data class Bar(val label: String, val value: Float, val color: Color, val valueLabel: String)

/** Rounded bars in their colours with values on top and a 0–[max] axis. */
@Composable
fun BarChart(bars: List<Bar>, modifier: Modifier = Modifier, max: Float = 1f, chartHeight: Dp = 140.dp, axisLabel: String = "100%") {
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = bars.joinToString { "${it.label} ${it.valueLabel}" }
    }) {
        Row(modifier = Modifier.fillMaxWidth().height(chartHeight), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(axisLabel, style = DtnType.Meta)
                Text("0", style = DtnType.Meta)
            }
            bars.forEach { b ->
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom) {
                    Text(b.valueLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    val frac = if (max <= 0f) 0f else (b.value / max).coerceIn(0f, 1f)
                    Box(Modifier.width(22.dp).fillMaxHeight(frac.coerceAtLeast(0.02f) * 0.85f)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(b.color))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp)) {
            bars.forEach { b ->
                Text(b.label, fontSize = 10.sp, color = DtnColors.TextSubtitle, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Inner-page top bar: ← arrow, title, optional grey subtitle / breadcrumb, optional actions. */
@Composable
fun InnerTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().background(DtnColors.Background).statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(DtnIcons.ArrowBack, contentDescription = "Back", tint = DtnColors.TextTitle) }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = DtnType.PageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = DtnType.PageSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F7FC)
@Composable
private fun ControlsPreview() {
    DtnTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InnerTopBar("Settings", onBack = {}, subtitle = "Developer Tools")
            var chip by remember { mutableIntStateOf(0) }
            ChipRow(listOf("All", "Unread", "Groups"), chip, { chip = it })
            var seg by remember { mutableIntStateOf(0) }
            SegmentedControl(listOf("Contacts", "Nearby devices"), seg, { seg = it })
            var q by remember { mutableStateOf("") }
            DtnSearchField(q, { q = it }, "Search chats")
            var v by remember { mutableStateOf(0.5f) }
            ValueSlider("λ distance weight", v, { v = it })
            BarChart(listOf(
                Bar("PRoPHET", 0.8f, DtnColors.AlgoProphet, "80%"),
                Bar("Epidemic", 0.95f, DtnColors.AlgoEpidemic, "95%"),
                Bar("Q", 0.4f, DtnColors.AlgoQLearning, "40%"),
            ))
        }
    }
}
