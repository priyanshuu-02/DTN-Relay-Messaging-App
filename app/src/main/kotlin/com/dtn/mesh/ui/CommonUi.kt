package com.dtn.mesh.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes

/*
 * Small pieces shared by the pages: the ViewModel's snackbars, re-reading the radios on resume,
 * the system-settings shortcuts, the Clear buffer confirmation text and the "Before connecting"
 * checklist (opened from any Connect button by the shell).
 */

/** Show the ViewModel's one-shot notices (joined group, invalid QR, …) as snackbars. */
@Composable
internal fun UserMessageSnackbars(viewModel: DtnViewModel, host: SnackbarHostState) {
    LaunchedEffect(viewModel, host) {
        viewModel.userMessages.collect { msg ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(msg)
        }
    }
}

/** Re-read radios + permissions whenever the app comes back to the foreground. */
@Composable
internal fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Returns a function that asks the system to turn Bluetooth on (the standard in-app dialog),
 * falling back to Bluetooth settings when the request isn't possible (no permission / OEM block).
 */
@Composable
fun rememberBluetoothEnabler(viewModel: DtnViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshRadios()
    }
    return remember(launcher) {
        {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val launched = allowed && runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.isSuccess
            if (!launched) runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }
}

internal fun openWifiSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
    else Intent(Settings.ACTION_WIFI_SETTINGS)
    runCatching { context.startActivity(intent) }
}

private fun openLocationSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

internal const val CLEAR_BUFFER_TITLE = "Clear the buffer?"

/** The Clear-buffer confirmation text (shared by every Clear buffer… button). */
internal fun clearBufferConfirmText(buffered: Int): String =
    if (buffered == 0) "There are no queued messages. Clearing also drops any queued control " +
        "traffic (key announces, receipts)."
    else "This permanently drops $buffered queued message${if (buffered == 1) "" else "s"} — " +
        "your unsent messages AND messages you're carrying for other people. They won't be " +
        "delivered. This can't be undone."

/**
 * "Before connecting" checklist: lists exactly what would stop a transport and offers the fix
 * for each. Re-evaluates live (radio toggles, permission results, returning from settings) and
 * continues on its own once everything is resolved.
 */
@Composable
internal fun PreflightDialog(viewModel: DtnViewModel, requestBluetoothOn: () -> Unit) {
    val context = LocalContext.current
    val radios by viewModel.radios.collectAsState()
    var recheck by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck++
        viewModel.refreshRadios()
    }
    OnResume { recheck++; viewModel.refreshRadios() }
    val issues = remember(radios, recheck) { viewModel.preflightIssues() }
    LaunchedEffect(issues) { if (issues.isEmpty()) viewModel.connectAll() }

    AlertDialog(
        onDismissRequest = { viewModel.dismissPreflight() },
        title = { Text("Before connecting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                issues.forEach { issue ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, DtnColors.CardBorder, DtnShapes.Button)
                            .padding(10.dp),
                    ) {
                        Text(issue.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DtnColors.TextTitle)
                        Text(issue.detail, fontSize = 12.sp, color = DtnColors.TextSubtitle)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (issue.kind) {
                                PreflightIssue.Kind.PERMISSIONS -> {
                                    FilledTonalButton(onClick = { permissions.launch(PermissionHelper.requiredPermissions()) }) { Text("Allow") }
                                    TextButton(onClick = { openAppSettings(context) }) { Text("App settings") }
                                }
                                PreflightIssue.Kind.BLUETOOTH_OFF -> {
                                    FilledTonalButton(onClick = requestBluetoothOn) { Text("Turn on") }
                                    TextButton(onClick = { viewModel.useInternetRelayInstead() }) { Text("Use internet instead") }
                                }
                                PreflightIssue.Kind.WIFI_OFF ->
                                    FilledTonalButton(onClick = { openWifiSettings(context) }) { Text("Turn on Wi-Fi") }
                                PreflightIssue.Kind.LOCATION_OFF ->
                                    FilledTonalButton(onClick = { openLocationSettings(context) }) { Text("Location settings") }
                            }
                        }
                    }
                }
                Text(
                    "Or connect anyway — every link that can start will, and the rest show what they need.",
                    fontSize = 12.sp, color = DtnColors.TextSubtitle,
                )
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.connectAll() }) { Text("Connect anyway") } },
        dismissButton = { TextButton(onClick = { viewModel.dismissPreflight() }) { Text("Cancel") } },
    )
}
