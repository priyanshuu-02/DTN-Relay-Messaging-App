package com.dtn.mesh.ui.identity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * First launch: shown only when no display name was saved when the app started. Continue saves
 * the name; the shell then opens on Network. Existing users with a name never see it.
 */
@Composable
fun FirstLaunchPage(viewModel: DtnViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    val canContinue = name.isNotBlank()
    val submit = { if (canContinue) viewModel.finishFirstLaunch(name) }
    Column(
        modifier = Modifier.fillMaxSize().background(DtnColors.Card).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            buildAnnotatedString {
                append("DTN Mesh ")
                withStyle(SpanStyle(color = DtnColors.Primary)) { append("Relay") }
            },
            style = DtnType.AppTitle,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        MeshIllustration(Modifier.size(180.dp).semantics { contentDescription = "An antenna linking phones" })
        Spacer(Modifier.height(24.dp))
        Text(
            buildAnnotatedString {
                append("Welcome to ")
                withStyle(SpanStyle(color = DtnColors.Primary)) { append("DTN Mesh Relay") }
            },
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle, textAlign = TextAlign.Center,
            lineHeight = 32.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Messages travel phone to phone over Bluetooth and Wi-Fi Direct, across town through LoRa hubs, and over " +
                "the internet when there is some. No account and no signal needed. Pick the name your contacts will see.",
            style = DtnType.RowSubtitle.copy(fontSize = 15.sp, lineHeight = 21.sp), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.replace("\n", "").take(DtnViewModel.MAX_DISPLAY_NAME) },
            singleLine = true,
            label = { Text("Display name") },
            leadingIcon = { Icon(DtnIcons.Person, contentDescription = null, tint = DtnColors.TextSubtitle) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = DtnShapes.Field,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue →", onClick = submit, enabled = canContinue, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Icon(DtnIcons.Info, contentDescription = null, tint = DtnColors.TextHint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("You can change this later in Settings", style = DtnType.Meta)
        }
    }
}

/** An antenna with three phones around it, linked by dashed-looking arcs (no bitmap asset). */
@Composable
private fun MeshIllustration(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            drawCircle(DtnColors.PrimarySoft, radius = r)
            drawCircle(DtnColors.Primary.copy(alpha = 0.25f), radius = r * 0.72f, style = Stroke(width = 3f))
            drawCircle(DtnColors.Primary.copy(alpha = 0.18f), radius = r * 0.48f, style = Stroke(width = 3f))
            val nodes = listOf(Offset(c.x - r * 0.62f, c.y + r * 0.32f), Offset(c.x + r * 0.6f, c.y + r * 0.36f), Offset(c.x, c.y - r * 0.66f))
            nodes.forEach { n -> drawLine(DtnColors.Primary.copy(alpha = 0.45f), c, n, strokeWidth = 4f) }
            nodes.forEachIndexed { i, n ->
                drawCircle(if (i == 2) DtnColors.Purple else DtnColors.Green, radius = r * 0.11f, center = n)
                drawCircle(DtnColors.Card, radius = r * 0.05f, center = n)
            }
            drawCircle(DtnColors.Primary, radius = r * 0.2f, center = c)
        }
        Icon(DtnIcons.LoraHub, contentDescription = null, tint = DtnColors.Card, modifier = Modifier.size(32.dp))
    }
}
