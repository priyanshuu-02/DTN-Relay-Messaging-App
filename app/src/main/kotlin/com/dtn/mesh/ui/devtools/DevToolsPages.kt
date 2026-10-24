package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes

/** Developer Tools sub-pages (`dev/{page}`). */
object DevPage {
    const val EVENTS = "events"
    const val ENGINE = "engine"
    const val HISTORY = "history"
    const val TUNING = "tuning"
    const val BENCHMARKS = "benchmarks"
    const val EXPORT = "export"
    const val CONSOLE = "console"
    /** Relay Configuration: its own route (`relay`), the same page as Settings › Relay server. */
    const val RELAY = "relay"
}

/** The breadcrumb under every Developer Tools page title. */
internal const val DEV_SUBTITLE = "Settings › Developer Tools"

/** One entry of the Developer Tools page. */
private data class DevEntry(
    val page: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val background: Color,
)

private val ENTRIES = listOf(
    DevEntry(DevPage.EVENTS, "Event Log", "Every message event, with search and filters · Export events",
        DtnIcons.EventLog, DtnColors.Primary, DtnColors.PrimarySoft),
    DevEntry(DevPage.ENGINE, "Routing Engine", "Network summary, per-peer scores, route observations, encounters and calculations",
        DtnIcons.Network, DtnColors.Primary, DtnColors.PrimarySoft),
    DevEntry(DevPage.HISTORY, "Buffer History", "How each buffered direct message left the buffer",
        DtnIcons.Storage, DtnColors.Orange, DtnColors.OrangeSoft),
    DevEntry(DevPage.TUNING, "Routing & Tuning", "PRoPHET · Stable · MaxProp · DiMaxProp · Epidemic · Q-learning · λ / η",
        DtnIcons.Settings, DtnColors.Purple, DtnColors.PurpleSoft),
    DevEntry(DevPage.BENCHMARKS, "Benchmarks", "Messages handled, delivery rate, delay, hops and overhead per algorithm",
        DtnIcons.BarChart, DtnColors.Green, DtnColors.GreenSoft),
    DevEntry(DevPage.EXPORT, "Export Data", "Research CSV / JSON and all logs, with a date range and Share",
        DtnIcons.Download, DtnColors.Primary, DtnColors.PrimarySoft),
    DevEntry(DevPage.CONSOLE, "Console", "The diagnostics log: levels, sources, search, copy",
        DtnIcons.DevTools, DtnColors.TextTitle, DtnColors.GreySoft),
    DevEntry(DevPage.RELAY, "Relay Configuration", "MQTT broker, how the relay connects, Test connection",
        DtnIcons.Internet, DtnColors.Primary, DtnColors.PrimarySoft),
)

/** The Developer Tools entry page: one row per research / diagnostics page, plus the info note. */
@Composable
fun DeveloperToolsPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(containerColor = DtnColors.Background, topBar = { InnerTopBar("Developer Tools", onBack = onBack, subtitle = "Settings") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "note") {
                Spacer(Modifier.height(4.dp))
                InfoNote("For advanced users and researchers: what the routing engine sees and decides, live on this phone. " +
                    "Nothing here is needed to send messages.")
            }
            ENTRIES.forEach { e ->
                item(key = e.page) {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        SettingRow(e.title, icon = e.icon, subtitle = e.subtitle, iconTint = e.tint, iconBackground = e.background,
                            onClick = { onOpen(e.page) })
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * One Developer Tools page by its [DevPage] name. [onOpenMessage] opens the Message page for a
 * message id, [onOpenContact] a phone's contact page.
 */
@Composable
fun DevToolsSubPage(
    viewModel: DtnViewModel,
    page: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenContact: (String) -> Unit,
) {
    when (page) {
        DevPage.EVENTS -> EventLogPage(viewModel, onBack, onOpenMessage)
        DevPage.ENGINE -> RoutingEnginePage(viewModel, onBack, onOpenMessage, onOpenContact)
        DevPage.HISTORY -> BufferHistoryPage(viewModel, onBack, onOpenMessage)
        DevPage.TUNING -> RoutingTuningPage(viewModel, onBack, onOpenBenchmarks = { onOpen(DevPage.BENCHMARKS) })
        DevPage.BENCHMARKS -> BenchmarksPage(viewModel, onBack)
        DevPage.EXPORT -> ExportDataPage(viewModel, onBack)
        DevPage.CONSOLE -> ConsolePage(viewModel, onBack)
        else -> DevPageFrame("Developer Tools", onBack) { padding ->
            Column(Modifier.padding(padding).padding(16.dp)) { InfoNote("This page isn't available.", kind = NoteKind.CAUTION) }
        }
    }
}

/** Shared frame of a Developer Tools page: the inner top bar with the breadcrumb, snackbars, an optional bottom bar. */
@Composable
internal fun DevPageFrame(
    title: String,
    onBack: () -> Unit,
    snackbars: SnackbarHostState? = null,
    subtitle: String = DEV_SUBTITLE,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { if (snackbars != null) SnackbarHost(snackbars) },
        topBar = { InnerTopBar(title, onBack = onBack, subtitle = subtitle) },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Buttons fixed at the bottom of a page, above the navigation bar. */
@Composable
internal fun DevBottomBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = DtnColors.Background) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A white card with a 4 dp coloured stripe on the left (Event Log, route observations); the whole card is the tap target. */
@Composable
internal fun StripeCard(
    stripe: Color,
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(DtnShapes.Card).clickable(onClickLabel = onClickLabel, onClick = onClick),
        shape = DtnShapes.Card,
        color = DtnColors.Card,
        border = BorderStroke(1.dp, DtnColors.CardBorder),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), content = content)
        }
    }
}
