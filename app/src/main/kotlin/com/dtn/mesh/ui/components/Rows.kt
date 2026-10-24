package com.dtn.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnSpacing
import com.dtn.mesh.ui.theme.DtnTheme
import com.dtn.mesh.ui.theme.DtnType

/** One entry of a ⋮ menu. */
data class MenuAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/** ⋮ button (48 dp target) with its dropdown. */
@Composable
fun OverflowMenu(actions: List<MenuAction>, contentDescription: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(DtnIcons.MoreVert, contentDescription = contentDescription, tint = DtnColors.TextSubtitle)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.destructive) DtnColors.RedText else DtnColors.TextTitle) },
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

/**
 * Settings row: icon square, title + subtitle, then a switch ([checked] non-null) or a › (when
 * [onClick] is set). The whole row is the touch target; a switch row toggles as one control.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    iconTint: Color = DtnColors.Primary,
    iconBackground: Color = DtnColors.PrimarySoft,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    titleColor: Color = DtnColors.TextTitle,
) {
    val interaction = when {
        checked != null && onCheckedChange != null ->
            Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (highlighted) Modifier.clip(DtnShapes.Button).background(DtnColors.PrimarySoft) else Modifier)
            .then(interaction)
            .heightIn(min = DtnSpacing.RowMinHeight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconSquare(icon, tint = iconTint, background = if (highlighted) Color.White else iconBackground)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) titleColor else DtnColors.TextHint)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = DtnType.RowSubtitle)
        }
        when {
            checked != null -> Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            onClick != null -> Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint)
        }
    }
}

/**
 * A read-only fact (decision 7: settings that would break the mesh are facts, not switches): a
 * green ✓ (or a grey ⓘ when [on] is false), a bold title, an optional value on the right and a
 * grey line underneath.
 */
@Composable
fun FactRow(title: String, detail: String, modifier: Modifier = Modifier, value: String? = null, on: Boolean = true) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top) {
        Icon(if (on) DtnIcons.Check else DtnIcons.Info, contentDescription = null,
            tint = if (on) DtnColors.Green else DtnColors.Grey, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, modifier = Modifier.weight(1f))
                if (value != null) Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextSubtitle)
            }
            Text(detail, style = DtnType.RowSubtitle)
        }
    }
}

/** Status chip on device / contact rows: "● Nearby" green, "● Offline" grey, "LoRa hub", … */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(modifier = modifier, shape = DtnShapes.Pill, color = tone.soft()) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(14.dp))
            else StatusDot(tone.color())
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tone.text(), maxLines = 1)
        }
    }
}

/** Device / contact row: 44 dp avatar, name + subtitle, a status chip and a ⋮ menu. */
@Composable
fun DeviceRow(
    name: String,
    subtitle: String,
    initial: String,
    avatarColor: Color,
    modifier: Modifier = Modifier,
    chip: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    menu: List<MenuAction> = emptyList(),
    /** An optional second, smaller line (e.g. the Bluetooth page's per-phone send results). */
    detail: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .heightIn(min = 68.dp)
            .padding(start = 12.dp, end = if (menu.isEmpty()) 12.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(initial, avatarColor)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (chip != null) {
            Spacer(Modifier.width(8.dp))
            chip()
        }
        if (menu.isNotEmpty()) OverflowMenu(menu, contentDescription = "More options for $name")
    }
}

/** One step of a vertical timeline: coloured dot + rail, title, time, detail and an optional pill. */
@Composable
fun TimelineItem(
    title: String,
    time: String,
    color: Color,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    pill: String? = null,
    pillTone: Tone = Tone.GREY,
) {
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp).fillMaxHeight()) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            }
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(DtnColors.CardBorder))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle,
                    modifier = Modifier.weight(1f))
                if (pill != null) StatusChip(pill, pillTone)
            }
            Text(time, style = DtnType.Meta)
            if (!detail.isNullOrBlank()) Text(detail, style = DtnType.RowSubtitle)
        }
    }
}

/** Radio card (Routing & Tuning): radio, bold name, grey description; selected = blue border + soft fill. */
@Composable
fun RadioCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = DtnColors.Primary,
) {
    Surface(
        modifier = modifier.clip(DtnShapes.Button)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = DtnShapes.Button,
        color = if (selected) DtnColors.PrimarySoft else DtnColors.Card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) DtnColors.Primary else DtnColors.CardBorder),
    ) {
        Row(modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = DtnColors.Primary))
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(accent)
                    Spacer(Modifier.width(6.dp))
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(subtitle, fontSize = 11.sp, color = DtnColors.TextSubtitle, maxLines = 2)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F7FC)
@Composable
private fun RowsPreview() {
    DtnTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DtnCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                SettingRow("Internet relay", icon = DtnIcons.Internet, subtitle = "Reach contacts anywhere", checked = true,
                    onCheckedChange = {})
                SettingRow("Developer Tools", icon = DtnIcons.DevTools, subtitle = "Event log, routing, export", onClick = {},
                    highlighted = true)
            }
            DtnCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                DeviceRow("Vrishank", "Seen just now · Bluetooth", "V", DtnColors.avatarFor("!97ef4bf4"),
                    chip = { StatusChip("Nearby", Tone.GREEN) }, menu = listOf(MenuAction("Open chat") {}))
            }
            TimelineItem("Created", "21:43:02", DtnColors.Primary, isLast = false, detail = "DM · 1 fragment")
            TimelineItem("Receipt", "21:43:09", DtnColors.Green, isLast = true, pill = "Delivered", pillTone = Tone.GREEN)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioCard("PRoPHET", "Probabilistic", selected = true, onClick = {}, modifier = Modifier.weight(1f))
                RadioCard("Epidemic", "Flood-all", selected = false, onClick = {}, modifier = Modifier.weight(1f),
                    accent = DtnColors.AlgoEpidemic)
            }
        }
    }
}
