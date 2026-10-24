package com.dtn.mesh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.RelayEndpoints
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.RelayTestResult
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.components.text
import com.dtn.mesh.ui.relayStatusText
import com.dtn.mesh.ui.shell.rememberRelayClock
import com.dtn.mesh.ui.shell.tone
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.FactRow
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.StatusDot
import com.dtn.mesh.ui.links.relayConnectionFacts
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * Relay Configuration (Settings › Relay server, Developer Tools, the Internet relay page): the
 * broker address (staged until Save changes), the relay's live status + diagnostics line, how it
 * connects (read-only facts) and Test connection, which runs a throwaway client off the main
 * thread and reports the endpoint that answered or why none did.
 */
@Composable
fun RelayConfigPage(viewModel: DtnViewModel, onBack: () -> Unit) {
    val saved by viewModel.brokerUrl.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val relayPeers by viewModel.internetPeers.collectAsState()
    val relay by viewModel.relayStatus.collectAsState()
    val nowMs = rememberRelayClock(relay)
    var broker by rememberSaveable(saved) { mutableStateOf(saved) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RelayTestResult?>(null) }
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)

    val wanted = status.isWanted(TransportKind.INTERNET)
    // The same state model as the Network tile, the relay page and Settings.
    val relayText = relayStatusText(relay, relayPeers.size, nowMs)
    val statusText = if (relay == RelayStatus.Off) "Off · switch the internet relay on in Settings or Connection links"
    else relayText.title
    val dirty = broker.trim() != saved && broker.isNotBlank()

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("Relay Configuration", onBack = onBack, subtitle = "Settings › Relay server") },
        bottomBar = {
            Surface(color = DtnColors.Background) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlineButton(
                        if (testing) "Testing…" else "Test connection",
                        onClick = {
                            testing = true
                            result = null
                            scope.launch {
                                result = viewModel.testRelayConnection(broker)
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("✓ Save changes", onClick = { viewModel.saveBrokerUrl(broker) }, enabled = dirty,
                        modifier = Modifier.weight(1f))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoNote(
                "The internet relay is optional. It reaches contacts anywhere when both phones have internet; nearby " +
                    "phones and LoRa hubs work without it.",
                kind = NoteKind.CAUTION,
            )

            SectionHeader("MQTT broker address")
            DtnCard {
                OutlinedTextField(
                    value = broker,
                    onValueChange = { broker = it.replace("\n", "").take(200) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = DtnShapes.Field,
                    label = { Text("Broker URL") },
                    placeholder = { Text(RelayEndpoints.DEFAULT_URL) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(6.dp))
                Text("e.g. ssl://broker.example.org:8883, tcp://host:1883 or wss://host:8884/mqtt", style = DtnType.Meta)
                if (broker.trim() != RelayEndpoints.DEFAULT_URL) {
                    TextButton(onClick = { broker = RelayEndpoints.DEFAULT_URL }) { Text("Use the public broker") }
                }
            }

            SectionHeader("Status")
            DtnCard {
                val tone = relayText.health.tone()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tone.color())
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tone.text())
                }
                if (wanted && relay != RelayStatus.Off) {
                    Spacer(Modifier.height(4.dp))
                    Text(relayText.detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DtnColors.TextSubtitle)
                }
                if (relay is RelayStatus.Retrying || relay is RelayStatus.GaveUp) {
                    TextButton(onClick = { viewModel.refreshLink(TransportKind.INTERNET) }) { Text("Retry now") }
                }
                if (testing || result != null) {
                    HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(vertical = 10.dp))
                    TestResultLine(testing, result)
                }
            }

            SectionHeader("Connection")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                relayConnectionFacts().forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(horizontal = 12.dp))
                    FactRow(f.title, f.detail, value = f.value, on = f.on)
                }
            }
            Text(
                "The broker is shared by everyone who uses it. It never sees the text of encrypted messages, but it does " +
                    "see who is online and message headers. Saving a new address reconnects a running relay.",
                style = DtnType.Meta, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TestResultLine(testing: Boolean, result: RelayTestResult?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            testing -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Testing the broker (up to 4 endpoints)…", style = DtnType.RowSubtitle)
            }
            result != null -> {
                StatusDot(if (result.ok) DtnColors.Green else DtnColors.Red)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (result.ok) "Test passed: ${result.endpoint} answered in ${result.tookMs} ms"
                    else "Test failed: ${result.detail} (last tried ${result.endpoint})",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (result.ok) DtnColors.GreenText else DtnColors.RedText,
                )
            }
        }
    }
}
