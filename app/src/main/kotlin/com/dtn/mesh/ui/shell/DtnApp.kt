package com.dtn.mesh.ui.shell

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.chats.ChatPage
import com.dtn.mesh.ui.devtools.DevPage
import com.dtn.mesh.ui.groups.CreateGroupPage
import com.dtn.mesh.ui.groups.GroupPage
import com.dtn.mesh.ui.groups.GroupsPage
import com.dtn.mesh.ui.identity.FirstLaunchPage
import com.dtn.mesh.ui.identity.IdentityPage
import com.dtn.mesh.ui.links.LinkPage
import com.dtn.mesh.ui.links.linkKindOf
import com.dtn.mesh.ui.links.routeName
import com.dtn.mesh.ui.peers.ContactPage
import com.dtn.mesh.ui.OnResume
import com.dtn.mesh.ui.PreflightDialog
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.StatusCard
import com.dtn.mesh.ui.components.StatusPill
import com.dtn.mesh.ui.devtools.DevToolsSubPage
import com.dtn.mesh.ui.devtools.DeveloperToolsPage
import com.dtn.mesh.ui.queue.MessagePage
import com.dtn.mesh.ui.queue.QueuedMessagePage
import com.dtn.mesh.ui.rememberBluetoothEnabler
import com.dtn.mesh.ui.settings.RelayConfigPage
import com.dtn.mesh.ui.settings.SettingsPage
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

/** Navigation routes (string routes). */
internal object Routes {
    const val TABS = "tabs"
    const val CHAT = "chat/{key}"
    const val CONTACT = "contact/{node}"
    /** The Message page (full trace), by the message id's first 8 characters. */
    const val MESSAGE = "message/{id}"
    /** The Queued message page (live routing math), same id. */
    const val QUEUED = "queued/{id}"
    const val SETTINGS = "settings"
    const val DEV = "dev"
    const val DEV_PAGE = "dev/{page}"
    /** Groups list, one group's page, Create group. */
    const val GROUPS = "groups"
    const val GROUP = "group/{channel}"
    const val GROUP_NEW = "groups/new"
    /** My Identity (QR, node id, safety number, Scan / Upload / Share QR). */
    const val IDENTITY = "identity"
    /** Relay Configuration (Settings › Relay server, Developer Tools, the Internet relay page). */
    const val RELAY = "relay"
    /** A link's page: ble, wifi_direct, hub, internet. */
    const val TRANSPORT = "transport/{kind}"

    fun chat(key: String) = "chat/" + Uri.encode(key)
    fun contact(node: String) = "contact/" + Uri.encode(node)
    fun message(id8: String) = "message/" + Uri.encode(id8)
    fun queued(id8: String) = "queued/" + Uri.encode(id8)
    fun dev(page: String) = "dev/$page"
    fun group(channel: Int) = "group/$channel"
    fun transport(kind: TransportKind) = "transport/" + kind.routeName()
}

/** The four bottom-nav tabs; Network is the start destination. */
internal enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    NETWORK("network", "Network", DtnIcons.Network),
    CHATS("chats", "Chats", DtnIcons.Chats),
    QUEUE("queue", "Queue", DtnIcons.Queue),
    PEERS("peers", "Peers", DtnIcons.Peers),
}

/**
 * The app: First launch while no display name was saved (Continue then opens the shell on
 * Network), otherwise the shell. Nothing is drawn for the moment the saved name is loading.
 */
@Composable
fun DtnApp(viewModel: DtnViewModel) {
    val firstLaunch by viewModel.firstLaunch.collectAsState()
    when (firstLaunch) {
        null -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(DtnColors.Background))
        true -> FirstLaunchPage(viewModel)
        false -> DtnShell(viewModel)
    }
}

/**
 * The shell: one NavHost, the tabs as a nested graph that starts on Network, the chat, contact,
 * Message (`message/{id}`) and Queued message (`queued/{id}`) destinations, Groups, My Identity,
 * Settings, Relay Configuration, the link pages and Developer Tools. Back from another tab
 * returns to Network; back on Network leaves the app.
 */
@Composable
private fun DtnShell(viewModel: DtnViewModel) {
    val nav = rememberNavController()
    ChatRouteSync(viewModel, nav)

    NavHost(
        navController = nav,
        startDestination = Routes.TABS,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        navigation(route = Routes.TABS, startDestination = Tab.NETWORK.route) {
            Tab.entries.forEach { tab -> composable(tab.route) { TabScaffold(viewModel, nav, tab) } }
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            // After process death the back stack comes back before the ViewModel's selection does
            // (and back from a contact page re-selects the chat underneath).
            LaunchedEffect(key) { if (viewModel.selectedChat.value != key) viewModel.openChat(key) }
            // System back is the NavHost's own pop; ChatRouteSync then clears the selection.
            ChatPage(
                viewModel, chatKey = key,
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
                onOpenMessage = { nav.openMessage(it) },
                onOpenGroup = { nav.navigate(Routes.group(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.GROUPS) {
            GroupsPage(
                viewModel, onBack = { nav.popBackStack() },
                onOpenGroup = { nav.navigate(Routes.group(it)) { launchSingleTop = true } },
                onCreate = { nav.navigate(Routes.GROUP_NEW) { launchSingleTop = true } },
            )
        }
        composable(Routes.GROUP_NEW) {
            CreateGroupPage(
                viewModel, onBack = { nav.popBackStack() },
                // The new group's page (its invite QR) replaces Create group.
                onCreated = { ch -> nav.navigate(Routes.group(ch)) { popUpTo(Routes.GROUP_NEW) { inclusive = true } } },
            )
        }
        composable(Routes.GROUP, arguments = listOf(navArgument("channel") { type = NavType.IntType })) { entry ->
            val channel = entry.arguments?.getInt("channel") ?: 0
            GroupPage(
                viewModel, channel = channel, onBack = { nav.popBackStack() },
                onOpenChat = { ch ->
                    // Came from that group's chat: go back to it instead of stacking another copy.
                    val below = nav.previousBackStackEntry
                    if (below?.destination?.route == Routes.CHAT && below.arguments?.getString("key") == "grp:$ch") nav.popBackStack()
                    else viewModel.openChat("grp:$ch")
                },
                onLeft = { nav.popOffGroup(channel) },
            )
        }
        composable(Routes.IDENTITY) { IdentityPage(viewModel, onBack = { nav.popBackStack() }) }
        composable(Routes.RELAY) { RelayConfigPage(viewModel, onBack = { nav.popBackStack() }) }
        composable(Routes.TRANSPORT, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
            val kind = linkKindOf(entry.arguments?.getString("kind").orEmpty())
            LinkPage(
                viewModel, kind = kind, onBack = { nav.popBackStack() },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
                onOpenRelay = { nav.navigate(Routes.RELAY) { launchSingleTop = true } },
                onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.MESSAGE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            MessagePage(viewModel, msgId8 = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.QUEUED, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            QueuedMessagePage(
                viewModel, msgId8 = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenMessage = { nav.openMessage(it) },
            )
        }
        composable(Routes.CONTACT, arguments = listOf(navArgument("node") { type = NavType.StringType })) { entry ->
            val node = entry.arguments?.getString("node").orEmpty()
            ContactPage(
                viewModel = viewModel,
                nodeId = node,
                onBack = { nav.popBackStack() },
                onOpenChat = { id ->
                    // Came from that chat: go back to it instead of stacking another copy.
                    val below = nav.previousBackStackEntry
                    if (below?.destination?.route == Routes.CHAT && below.arguments?.getString("key") == id) nav.popBackStack()
                    else viewModel.openChat(id)
                },
                onClose = { nav.popOffConversation() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsPage(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                onOpenDevTools = { nav.navigate(Routes.DEV) },
                onOpenDevPage = { nav.navigate(Routes.dev(it)) },
                onOpenIdentity = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } },
                onOpenGroups = { nav.navigate(Routes.GROUPS) { launchSingleTop = true } },
                onOpenRelay = { nav.navigate(Routes.RELAY) { launchSingleTop = true } },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.DEV) {
            DeveloperToolsPage(onBack = { nav.popBackStack() }, onOpen = { page ->
                if (page == DevPage.RELAY) nav.navigate(Routes.RELAY) { launchSingleTop = true } else nav.navigate(Routes.dev(page))
            })
        }
        composable(Routes.DEV_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) { entry ->
            DevToolsSubPage(
                viewModel = viewModel,
                page = entry.arguments?.getString("page").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.dev(it)) },
                onOpenMessage = { nav.openMessage(it) },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
            )
        }
    }

    // The "before connecting" checklist can open from any Connect button.
    val showPreflight by viewModel.showPreflight.collectAsState()
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)
    if (showPreflight) PreflightDialog(viewModel, requestBluetoothOn)
}

/**
 * Keeps `viewModel.selectedChat` (every existing `openChat(...)` call) and the `chat/{key}`
 * destination in step, both ways:
 * - a new selection opens `chat/{key}`; clearing it (the chat's ← arrow, leaving the group) pops it;
 * - the chat leaving the top of the back stack any other way (system back) clears the selection,
 *   so opening the same chat again still navigates.
 */
@Composable
private fun ChatRouteSync(viewModel: DtnViewModel, nav: NavHostController) {
    val selected by viewModel.selectedChat.collectAsState()
    // The selection the previous run saw. It starts null, so a chat restored after process death
    // (back stack back, selection not yet) isn't closed before its destination re-selects it.
    val lastSeen = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(selected) {
        val previous = lastSeen[0]
        lastSeen[0] = selected
        val entry = nav.currentBackStackEntry
        val onChat = entry?.destination?.route == Routes.CHAT
        val openKey = if (onChat) entry?.arguments?.getString("key") else null
        val key = selected
        when {
            key != null && key != openKey -> nav.navigate(Routes.chat(key)) {
                // One chat replaces another instead of stacking up.
                if (onChat) popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
            key == null && previous != null && onChat -> nav.popBackStack()
        }
    }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            if (entry.destination.route != Routes.CHAT && viewModel.selectedChat.value != null) viewModel.openChat(null)
        }
    }
}

/** The Message page for one message (8-character id); the same message twice doesn't stack. */
private fun NavHostController.openMessage(id8: String) = navigate(Routes.message(id8)) { launchSingleTop = true }

/** After a Delete / Block on the contact page: leave it and the (now gone) chat under it. */
private fun NavHostController.popOffConversation() {
    while (currentBackStackEntry?.destination?.route.let { it == Routes.CONTACT || it == Routes.CHAT }) {
        if (!popBackStack()) break
    }
}

/** After leaving a group on its page: leave the page, and that group's chat if it's underneath. */
private fun NavHostController.popOffGroup(channel: Int) {
    if (!popBackStack()) return
    val top = currentBackStackEntry
    if (top?.destination?.route == Routes.CHAT && top.arguments?.getString("key") == "grp:$channel") popBackStack()
}

private fun NavHostController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A main tab: top bar, status card (opens Connection links), the tab's content, bottom nav. */
@Composable
private fun TabScaffold(viewModel: DtnViewModel, nav: NavHostController, tab: Tab) {
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val displayName by viewModel.displayNameSetting.collectAsState()

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            MainTopBar(
                initial = UiText.avatarInitial(displayName),
                onGroups = { nav.navigate(Routes.GROUPS) { launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onIdentity = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } },
            )
        },
        bottomBar = { BottomNav(current = tab, onSelect = { if (it != tab) nav.navigateToTab(it) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ShellStatus(viewModel, tab, onOpenLinks = { overlay = OVERLAY_LINKS })
            val openContact: (String) -> Unit = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } }
            when (tab) {
                Tab.NETWORK -> NetworkTabPage(viewModel, onOpenContact = openContact,
                    onOpenRoutingEngine = { nav.navigate(Routes.dev(DevPage.ENGINE)) },
                    onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } })
                Tab.CHATS -> ChatsTabPage(viewModel)
                Tab.QUEUE -> QueueTabPage(
                    viewModel,
                    onOpenQueued = { nav.navigate(Routes.queued(it)) { launchSingleTop = true } },
                    onOpenMessage = { nav.openMessage(it) },
                )
                Tab.PEERS -> PeersTabPage(viewModel, onOpenContact = openContact,
                    onShowMyQr = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } })
            }
        }
    }

    if (overlay == OVERLAY_LINKS) {
        ConnectionLinksSheet(
            viewModel, onDismiss = { overlay = null },
            onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } },
        )
    }
}

private const val OVERLAY_LINKS = "links"

/** "DTN Mesh Relay" on the left; Groups, Settings and the My Identity avatar on the right. */
@Composable
private fun MainTopBar(initial: String, onGroups: () -> Unit, onSettings: () -> Unit, onIdentity: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(DtnColors.Background).statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append("DTN Mesh ")
                withStyle(SpanStyle(color = DtnColors.Primary)) { append("Relay") }
            },
            style = DtnType.AppTitle,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onGroups) { Icon(DtnIcons.Groups, contentDescription = "Groups", tint = DtnColors.TextTitle) }
        IconButton(onClick = onSettings) { Icon(DtnIcons.Settings, contentDescription = "Settings", tint = DtnColors.TextTitle) }
        IconButton(onClick = onIdentity, modifier = Modifier.semantics { contentDescription = "My identity" }) {
            Avatar(initial, DtnColors.Primary, size = 36.dp)
        }
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        HorizontalDivider(color = DtnColors.CardBorder)
        NavigationBar(containerColor = DtnColors.Card, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label, fontWeight = if (tab == current) FontWeight.SemiBold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DtnColors.Primary,
                        selectedTextColor = DtnColors.Primary,
                        indicatorColor = DtnColors.PrimarySoft,
                        unselectedIconColor = DtnColors.TextSubtitle,
                        unselectedTextColor = DtnColors.TextSubtitle,
                    ),
                )
            }
        }
    }
}

/**
 * The status card under the top bar (tap → Connection links): the full card on Network (links in
 * use, node, the active algorithm read-only, buffer), link state + queued count on Chats, a small
 * pill on Queue and Peers.
 */
@Composable
private fun ShellStatus(viewModel: DtnViewModel, tab: Tab, onOpenLinks: () -> Unit) {
    // Re-read radios + permissions on return to the app (back from system settings), on every
    // tab, as the old always-visible connection card did.
    OnResume { viewModel.refreshRadios() }
    val snapshot = rememberLinkSnapshot(viewModel)
    val nodeId by viewModel.localNodeId.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    val h = snapshot.headline
    val tone = h.health.tone()
    val queued = if (buffered == 1) "1 message queued" else "$buffered messages queued"
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    when (tab) {
        Tab.NETWORK -> StatusCard(
            pill = h.pill(), tone = tone, onClick = onOpenLinks, modifier = pad,
            meta = listOf(h.detail, "Node ${nodeId?.takeLast(8) ?: "—"}", strategy, "Buffer $buffered").joinToString(" · "),
        )
        Tab.CHATS -> StatusCard(pill = h.pill(), tone = tone, onClick = onOpenLinks, modifier = pad, meta = h.detail) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (h.health == com.dtn.mesh.ui.LinkHealth.ON) queued else "No live link · $queued",
                style = DtnType.Meta, fontWeight = FontWeight.SemiBold,
            )
        }
        Tab.QUEUE, Tab.PEERS -> Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Show connection links", role = Role.Button, onClick = onOpenLinks)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(h.pill(), tone)
            Spacer(Modifier.width(10.dp))
            Text("$queued ›", style = DtnType.Meta, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}
