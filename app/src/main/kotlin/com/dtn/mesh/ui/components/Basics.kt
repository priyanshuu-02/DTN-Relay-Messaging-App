package com.dtn.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnSpacing
import com.dtn.mesh.ui.theme.DtnTheme
import com.dtn.mesh.ui.theme.DtnType

/** Status colour family: green on, orange starting / queued, red problem, grey off. */
enum class Tone { GREEN, ORANGE, RED, GREY, BLUE, PURPLE }

fun Tone.color(): Color = when (this) {
    Tone.GREEN -> DtnColors.Green
    Tone.ORANGE -> DtnColors.Orange
    Tone.RED -> DtnColors.Red
    Tone.GREY -> DtnColors.Grey
    Tone.BLUE -> DtnColors.Primary
    Tone.PURPLE -> DtnColors.Purple
}

fun Tone.soft(): Color = when (this) {
    Tone.GREEN -> DtnColors.GreenSoft
    Tone.ORANGE -> DtnColors.OrangeSoft
    Tone.RED -> DtnColors.RedSoft
    Tone.GREY -> DtnColors.GreySoft
    Tone.BLUE -> DtnColors.PrimarySoft
    Tone.PURPLE -> DtnColors.PurpleSoft
}

/** Readable text colour on [soft]. */
fun Tone.text(): Color = when (this) {
    Tone.GREEN -> DtnColors.GreenText
    Tone.ORANGE -> DtnColors.OrangeText
    Tone.RED -> DtnColors.RedText
    Tone.GREY -> DtnColors.TextSubtitle
    Tone.BLUE -> DtnColors.Primary
    Tone.PURPLE -> DtnColors.Purple
}

/** White card, 16 dp corners, 1 dp border. Clickable when [onClick] is set. */
@Composable
fun DtnCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(DtnSpacing.CardPadding),
    color: Color = DtnColors.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(DtnShapes.Card)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier),
        shape = DtnShapes.Card,
        color = color,
        border = BorderStroke(1.dp, DtnColors.CardBorder),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** Blue upper-case section header ("PROFILE"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = DtnType.SectionHeader, modifier = modifier.padding(top = 8.dp, bottom = 8.dp, start = 4.dp))
}

/** 8 dp status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(DtnSpacing.StatusDot).clip(CircleShape).background(color))
}

/** Coloured pill: "● Online · Store & forward". */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = DtnShapes.Pill, color = tone.soft()) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone.color())
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tone.text(), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The status card under the main top bar: a coloured pill, a meta line and a chevron. Tapping it
 * opens the Connection links sheet. [extra] adds rows under the meta line.
 */
@Composable
fun StatusCard(
    pill: String,
    tone: Tone,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    DtnCard(modifier = modifier, onClick = onClick, onClickLabel = "Show connection links",
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                StatusPill(pill, tone)
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                extra?.invoke(this)
            }
            Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint)
        }
    }
}

enum class NoteKind { INFO, CAUTION, DANGER }

/** Rounded note with an icon: blue ⓘ info, amber ⚠ caution, red ⚠ danger. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, kind: NoteKind = NoteKind.INFO) {
    val (tone, icon) = when (kind) {
        NoteKind.INFO -> Tone.BLUE to DtnIcons.Info
        NoteKind.CAUTION -> Tone.ORANGE to DtnIcons.Warning
        NoteKind.DANGER -> Tone.RED to DtnIcons.Warning
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = DtnShapes.Button, color = tone.soft()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = DtnColors.TextTitle, lineHeight = 18.sp)
        }
    }
}

/** Icon in a soft circle, a 16 sp title and a grey sentence. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(DtnColors.PrimarySoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = DtnColors.Primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = DtnType.RowSubtitle, textAlign = TextAlign.Center)
    }
}

/** Soft-tinted box: small grey label, big bold value ("TTL LEFT / 239m"). */
@Composable
fun StatTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Surface(modifier = modifier, shape = DtnShapes.Button, color = tint.copy(alpha = 0.10f)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label.uppercase(), fontSize = 11.sp, color = DtnColors.TextSubtitle, letterSpacing = 0.6.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = DtnType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon in a 40 dp soft rounded square (list rows). */
@Composable
fun IconSquare(icon: ImageVector, tint: Color = DtnColors.Primary, background: Color = DtnColors.PrimarySoft, size: Dp = DtnSpacing.IconSquare) {
    Box(Modifier.size(size).clip(DtnShapes.IconSquare).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Colour circle with a white initial. */
@Composable
fun Avatar(initial: String, color: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, icon: ImageVector? = null) {
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        else Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp, maxLines = 1)
    }
}

/** Full-width 52 dp blue button. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = DtnShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = DtnSpacing.MainButtonHeight)) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tighter than Material's 24 dp so three buttons fit side by side on a narrow phone. */
private val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** Lavender button with purple text (Sync). */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = DtnShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = DtnColors.PurpleSoft, contentColor = DtnColors.Purple),
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = DtnSpacing.MainButtonHeight)) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Blue-outlined button. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = DtnShapes.Button,
        border = BorderStroke(1.dp, DtnColors.Primary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DtnColors.Primary),
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = DtnSpacing.MainButtonHeight)) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Red outline with red text and an optional ⚠ icon (Clear / Delete / Block). */
@Composable
fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    OutlinedButton(onClick = onClick, shape = DtnShapes.Button, border = BorderStroke(1.dp, DtnColors.Red),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DtnColors.RedText),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = DtnSpacing.MainButtonHeight)) {
        if (showIcon) {
            Icon(DtnIcons.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Material confirm dialog; the confirming action is red when [destructive]. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) DtnColors.RedText else DtnColors.Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F7FC)
@Composable
private fun BasicsPreview() {
    DtnTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(pill = "Online · Store & forward", tone = Tone.GREEN,
                meta = "via BLE · Node 97ef4bf4 · DIMAXPROP · Buffer 0", onClick = {})
            InfoNote("Forwarding is not final delivery.")
            InfoNote("Clearing the buffer removes queued messages.", kind = NoteKind.DANGER)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("TTL left", "239m", DtnColors.Primary, Modifier.weight(1f))
                StatTile("Hops", "1", DtnColors.TextSubtitle, Modifier.weight(1f))
            }
            EmptyState(DtnIcons.Queue, "No more pending messages", "New messages wait here until a link appears.")
        }
    }
}
