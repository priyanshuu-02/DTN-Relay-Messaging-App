package com.dtn.mesh.ui.identity

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.QrImage
import com.dtn.mesh.ui.QrShare
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * My Identity (`identity`, replaces the identity dialog): this phone's QR, node id + copy, the
 * safety number as a 4×4 grid + copy, Share my QR (as an image), Scan QR, Upload QR, and a note
 * on why to compare in person. A scanned contact opens their chat; a group invite joins (today's
 * conflict rules).
 */
@Composable
fun IdentityPage(viewModel: DtnViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val name by viewModel.displayNameSetting.collectAsState()
    // The QR carries the display name: rebuilt when it changes.
    val qr = remember(name) { viewModel.identityQrString() }
    val nodeId = viewModel.myNodeId.removePrefix("!")
    val fingerprint = remember { viewModel.myFingerprintHex }
    val lines = remember(fingerprint) { UiText.fingerprintLines(fingerprint) }
    val qrActions = rememberQrActions(
        prompt = "Scan a contact or group QR",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> viewModel.openChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )
    fun copied(what: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        // Android 13+ confirms clipboard writes itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbars.showSnackbar("$what copied") }
    }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("My identity", onBack = onBack, subtitle = name.ifBlank { null }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DtnCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(qr, size = 240.dp) }
                Spacer(Modifier.height(12.dp))
                Text("Node ID", style = DtnType.Meta)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nodeId, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = DtnColors.TextTitle, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copied("Node id", nodeId) }) {
                        Icon(DtnIcons.Copy, contentDescription = "Copy node id", tint = DtnColors.Primary)
                    }
                }
            }

            DtnCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Safety number", style = DtnType.RowTitle)
                        Text("256-bit fingerprint of this phone's keys", style = DtnType.Meta)
                    }
                    IconButton(onClick = { copied("Safety number", lines.joinToString("\n")) }) {
                        Icon(DtnIcons.Copy, contentDescription = "Copy safety number", tint = DtnColors.Primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(shape = DtnShapes.Button, color = DtnColors.GreySoft, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        lines.forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 1.sp,
                                color = DtnColors.TextTitle, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }

            PrimaryButton("Share my QR", onClick = {
                scope.launch {
                    if (!QrShare.shareAsync(context, qr, "Share my DTN contact QR")) viewModel.showMessage("Couldn't share the QR image")
                }
            }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Scan QR", onClick = qrActions.scan, modifier = Modifier.weight(1f))
                OutlineButton(if (qrActions.decoding) "Reading…" else "Upload QR", onClick = qrActions.upload,
                    enabled = !qrActions.decoding, modifier = Modifier.weight(1f))
            }
            InfoNote(
                "Scan each other's QR in person to exchange keys and mark them verified, or share yours as an image " +
                    "and the other phone uploads it. Compare safety numbers to be sure nobody swapped the keys. " +
                    "Group invites scan the same way.",
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
