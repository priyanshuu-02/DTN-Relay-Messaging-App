package com.dtn.mesh.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Render a QR code for [content] as a Compose [Image], or nothing if encoding fails. */
@Composable
fun QrImage(content: String, sizePx: Int = 560, modifier: Modifier = Modifier) {
    val bmp = remember(content) { generateQrBitmap(content, sizePx) }
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR code", modifier = modifier.size(220.dp))
    } else {
        Text("QR unavailable", color = Palette.TextMuted)
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size) {
            setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
} catch (e: Exception) {
    null
}

/** Split a hex fingerprint into readable groups for the safety-number display. */
private fun formatFingerprint(hex: String): String =
    hex.take(32).chunked(4).joinToString(" ")

/* ==========================================================================
 *  Identity / key-exchange dialog
 * ========================================================================== */
@Composable
fun IdentityDialog(viewModel: DtnViewModel, onOpenChat: (String) -> Unit, onDismiss: () -> Unit) {
    val qr = remember { viewModel.identityQrString() }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        if (contents.startsWith("dtngrp:")) {
            viewModel.joinGroupFromInvite(contents) { ch -> onOpenChat("grp:$ch") }
        } else {
            viewModel.importContactQr(contents) { node -> if (node != null) onOpenChat(node) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                scanLauncher.launch(ScanOptions().setOrientationLocked(false).setPrompt("Scan a DTN contact or group QR"))
            }) { Text("Scan QR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("My identity") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrImage(qr)
                Spacer(Modifier.height(8.dp))
                Text(viewModel.myNodeId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Safety number", fontSize = 11.sp, color = Palette.TextMuted)
                Text(
                    formatFingerprint(viewModel.myFingerprintHex),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Show this QR to a contact (or scan theirs) to verify keys in person. " +
                        "Scanning also joins a group if the QR is a group invite.",
                    fontSize = 11.sp, color = Palette.TextMuted,
                )
            }
        },
    )
}

/* ==========================================================================
 *  Settings dialog
 * ========================================================================== */
@Composable
fun SettingsDialog(viewModel: DtnViewModel, onDismiss: () -> Unit) {
    val savedName by viewModel.displayNameSetting.collectAsState()
    val internetEnabled by viewModel.internetEnabled.collectAsState()
    val savedBroker by viewModel.brokerUrl.collectAsState()

    var name by remember(savedName) { mutableStateOf(savedName) }
    var broker by remember(savedBroker) { mutableStateOf(savedBroker) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                viewModel.setDisplayName(name.trim())
                viewModel.setBrokerUrl(broker.trim())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Display name", fontSize = 12.sp, color = Palette.TextMuted)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("shown to contacts") })
                Spacer(Modifier.height(12.dp))
                Divider(color = Palette.Divider)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Internet relay", fontWeight = FontWeight.SemiBold)
                        Text("Bridge over MQTT when online (messages stay encrypted)",
                            fontSize = 11.sp, color = Palette.TextMuted)
                    }
                    Switch(checked = internetEnabled, onCheckedChange = { viewModel.setInternetEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text("MQTT broker", fontSize = 12.sp, color = Palette.TextMuted)
                OutlinedTextField(value = broker, onValueChange = { broker = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("tcp://host:1883") })
            }
        },
    )
}

/* ==========================================================================
 *  Groups dialog (create / join / open)
 * ========================================================================== */
@Composable
fun GroupsDialog(viewModel: DtnViewModel, onOpenChat: (String) -> Unit, onDismiss: () -> Unit) {
    val groups by viewModel.groups.collectAsState()
    var newName by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        if (contents.startsWith("dtngrp:")) {
            viewModel.joinGroupFromInvite(contents) { ch -> onOpenChat("grp:$ch") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Groups") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (groups.isEmpty()) {
                    Text("No groups yet. Create one and share the QR, or scan an invite.",
                        fontSize = 12.sp, color = Palette.TextMuted)
                } else {
                    groups.forEach { g ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenChat("grp:${g.channel}") }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("👥 ${g.name}", fontWeight = FontWeight.Medium)
                            TextButton(onClick = { viewModel.groupInvite(g.channel) { invite = it } }) { Text("Invite QR") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Divider(color = Palette.Divider)
                Spacer(Modifier.height(10.dp))
                Text("Create group", fontSize = 12.sp, color = Palette.TextMuted)
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("group name") })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.createGroup(newName) { invite = it; newName = "" } }) { Text("Create") }
                    Button(onClick = {
                        scanLauncher.launch(ScanOptions().setOrientationLocked(false).setPrompt("Scan a group invite QR"))
                    }) { Text("Join (scan)") }
                }
                invite?.let { inv ->
                    Spacer(Modifier.height(12.dp))
                    Text("Share this invite:", fontSize = 12.sp, color = Palette.TextMuted)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(inv)
                    }
                }
            }
        },
    )
}
