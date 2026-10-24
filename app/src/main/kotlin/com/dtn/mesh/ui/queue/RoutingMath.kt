package com.dtn.mesh.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.RoutingExplanation
import com.dtn.mesh.ui.RoutingNodeBelief
import com.dtn.mesh.ui.components.StatTile
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * The live routing math on the Queued message page: how the algorithm selected now scores the
 * carriers toward the message's destination — my own score, the best next hop, a small path map,
 * every candidate carrier (this route's previous / next hop marked) and the formulas with their
 * constants (folded). The header folds the whole section; [initiallyExpanded] opens it.
 */
@Composable
internal fun RoutingMathSection(
    exp: RoutingExplanation,
    destLabel: String,
    prevHop: String? = null,
    nextHop: String? = null,
    initiallyExpanded: Boolean = false,
) {
    val hint = if (initiallyExpanded) "" else " — tap to expand"
    ExpandableSection(
        title = "Routing math · ${exp.strategy}",
        subtitle = "How the path toward $destLabel is scored$hint",
        initiallyExpanded = initiallyExpanded,
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            // Headline scores in two highlighted stat tiles.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(exp.myScoreLabel, "%.3f".format(exp.myScore), DtnColors.Primary, Modifier.weight(1f))
                StatTile(
                    "Best next hop",
                    exp.bestNextHop?.let { exp.bestNextHopName ?: it.takeLast(8) } ?: "none",
                    if (exp.bestNextHop != null) DtnColors.Green else DtnColors.TextSubtitle,
                    Modifier.weight(1f),
                    sub = exp.bestNextHop?.let { "${exp.bestScoreLabel} ${"%.3f".format(exp.bestScore)}" },
                )
            }

            Spacer(Modifier.height(14.dp))
            SmallHeading("Path map")
            Text("YOU → chosen next hop (green). Grey = online peers not selected.", style = DtnType.Meta,
                modifier = Modifier.padding(bottom = 4.dp))
            RoutingMapView(exp)

            Spacer(Modifier.height(14.dp))
            SmallHeading("Candidate carriers")
            Spacer(Modifier.height(6.dp))
            if (exp.beliefs.isEmpty()) {
                Text("No candidate carriers known yet.", style = DtnType.RowSubtitle, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                exp.beliefs.take(8).forEachIndexed { i, b ->
                    val role = when (b.nodeId.takeLast(8)) {
                        prevHop -> "prev hop"
                        nextHop -> "next hop"
                        else -> null
                    }
                    CarrierBeliefRow(b, role, exp.bestScoreLabel, isBest = i == 0 && b.score > 0.0)
                }
            }

            Spacer(Modifier.height(14.dp))
            ExpandableSection(title = "Formulas & constants") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    exp.formulas.forEach { FormulaLine(it.label, it.formula) }
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = DtnShapes.Button, color = DtnColors.GreySoft, modifier = Modifier.fillMaxWidth()) {
                        Text(exp.constantsLine, style = DtnType.Mono.copy(fontSize = 11.sp, color = DtnColors.TextSubtitle),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

/** Tappable header that reveals / hides [content]. Local, transient expand state. */
@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide $title" else "Show $title", role = Role.Button) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.Primary)
                if (subtitle != null) Text(subtitle, style = DtnType.Meta)
            }
            Text(if (expanded) "▾" else "▸", fontSize = 14.sp, color = DtnColors.Primary)
        }
        if (expanded) content()
    }
}

@Composable
private fun SmallHeading(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextSubtitle, letterSpacing = 0.8.sp)
}

/**
 * A tiny "next-hop map": YOU on the left, each online candidate carrier on the right. The
 * chosen next hop is drawn in green with a thick solid line; the other online peers (offered
 * but not selected) are drawn in grey. Purely a visualisation of the current [exp] scoring.
 */
@Composable
private fun RoutingMapView(exp: RoutingExplanation) {
    val online = exp.beliefs.filter { it.isOnline }.take(6)
    if (online.isEmpty()) {
        Text("No online peers to route through right now.", style = DtnType.RowSubtitle, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val chosenColor = DtnColors.Green
    val greyColor = DtnColors.Grey
    val youColor = DtnColors.Primary
    // Rows grow with the user's font scale so two lines of label always fit.
    val rowHeight = with(LocalDensity.current) { 22.sp.toDp() * 2 }.coerceAtLeast(40.dp)

    // Lines and dots are drawn on a canvas; every label is real Compose text in its own row
    // (ellipsized), so nothing clips or overlaps on narrow screens or with large text.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(rowHeight * online.size + 8.dp)) {
        // Keep the carrier dots at ~40% of the width (bounded) so the labels get the rest.
        val dotX = (maxWidth * 0.40f).coerceIn(72.dp, 150.dp)
        val leftX = 22.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodeR = 7.dp.toPx()
            val rowPx = rowHeight.toPx()
            val youCenter = Offset(leftX.toPx(), (rowPx * online.size) / 2f + 4.dp.toPx())
            online.forEachIndexed { i, b ->
                val target = Offset(dotX.toPx(), 4.dp.toPx() + rowPx * (i + 0.5f))
                val chosen = b.nodeId == exp.bestNextHop
                drawLine(
                    color = if (chosen) chosenColor else greyColor,
                    start = youCenter, end = target,
                    strokeWidth = if (chosen) 3.dp.toPx() else 1.dp.toPx(),
                )
                drawCircle(if (chosen) chosenColor else greyColor, radius = nodeR, center = target)
            }
            drawCircle(youColor, radius = nodeR + 2.dp.toPx(), center = youCenter)
        }
        Text(
            "YOU", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = youColor,
            modifier = Modifier.padding(start = 6.dp, top = (rowHeight * online.size) / 2f - 18.dp),
        )
        Column(modifier = Modifier.fillMaxSize().padding(start = dotX + 14.dp, top = 4.dp)) {
            online.forEach { b ->
                val chosen = b.nodeId == exp.bestNextHop
                Column(modifier = Modifier.height(rowHeight), verticalArrangement = Arrangement.Center) {
                    Text(
                        b.displayName + if (chosen) "  ✓" else "",
                        fontSize = 12.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chosen) DtnColors.GreenText else DtnColors.TextTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${exp.bestScoreLabel} %.2f".format(b.score),
                        fontSize = 11.sp, color = DtnColors.TextSubtitle, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarrierBeliefRow(b: RoutingNodeBelief, role: String?, scoreLabel: String, isBest: Boolean) {
    val dot = if (b.isOnline) DtnColors.Green else DtnColors.Red
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = DtnShapes.Button,
        color = if (isBest) DtnColors.GreenSoft else DtnColors.GreySoft,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (role != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = DtnShapes.Pill, color = DtnColors.PrimarySoft) {
                            Text(role, fontSize = 10.sp, color = DtnColors.Primary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("$scoreLabel ${"%.3f".format(b.score)}", fontSize = 12.sp, color = DtnColors.Primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(b.detail, fontSize = 11.sp, color = DtnColors.TextSubtitle, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FormulaLine(label: String, formula: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, style = DtnType.Meta)
        Spacer(Modifier.height(2.dp))
        Text(formula, style = DtnType.Mono)
    }
}
