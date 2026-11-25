package dev.troupe.android.core.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.troupe.android.chat.ChatScreen
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.lock.SettingsScreen
import dev.troupe.android.pairing.ConnectServerScreen
import dev.troupe.android.pairing.ConnectServerViewModel
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.setup.ServerSetupViewModel
import dev.troupe.android.setup.ServerSetupWizardScreen
import dev.troupe.android.shell.MobileWorkspaceShellScreen
import dev.troupe.android.shell.WorkspaceShellViewModel
import dev.troupe.android.terminal.TerminalScreen
import dev.troupe.android.workspace.WorkspaceTab
import kotlinx.serialization.Serializable

@Serializable data object WorkspaceShellRoute

@Serializable data class ConnectRoute(val canNavigateBack: Boolean = false)

@Serializable data object SettingsRoute

@Serializable data class SetupRoute(
    val baseUrl: String? = null,
    val setupToken: String? = null,
    val reconfigureServerId: String? = null,
)

/** Kept for deep-link / legacy compatibility. */
@Serializable data class TerminalRoute(val serverId: String, val terminalId: String)

@Serializable data class ChatRoute(val serverId: String, val sessionId: String, val title: String?)

/**
 * Troupe V2 Navigation Host:
 * - Boots straight into [ConnectRoute] (1-tap Paste Link) when no servers are paired.
 * - Boots straight into [WorkspaceShellRoute] (`MobileWorkspaceShellScreen`) when a server is paired.
 * - Notification [ChatLink] switches to the server and opens the chat tab directly in the shell.
 */
@Composable
fun TroupeNavHost(
    screens: Screens,
    link: ChatLink?,
    onLinkHandled: () -> Unit,
    shellViewModel: WorkspaceShellViewModel = hiltViewModel(),
) {
    val servers by shellViewModel.repository.servers.collectAsStateWithLifecycle(initialValue = null)
    val shellState by shellViewModel.session.state.collectAsStateWithLifecycle()

    // Wait for initial DataStore read so we never flash ConnectScreen for existing users.
    val loadedServers = servers
    if (loadedServers == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(TroupeTheme.palette.background),
            contentAlignment = Alignment.Center,
        ) {
            TroupeSpinner(size = 24.dp)
        }
        return
    }

    val nav = rememberNavController()
    val startDestination: Any = if (loadedServers.isEmpty()) ConnectRoute(canNavigateBack = false) else WorkspaceShellRoute

    // Report active screen to AttentionNotifier so notifications stay silent for the chat on screen.
    LaunchedEffect(nav, shellState.activeServer?.id, shellState.workspace.activeTab) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val destination = entry.destination
            screens.onScreen(
                when {
                    destination.hasRoute<WorkspaceShellRoute>() -> {
                        val srvId = shellState.activeServer?.id
                        val activeTab = shellState.workspace.activeTab
                        when {
                            srvId != null && activeTab is WorkspaceTab.AgentChat -> Screen.Chat(srvId, activeTab.sessionId)
                            srvId != null -> Screen.Server(srvId)
                            else -> Screen.Other
                        }
                    }
                    destination.hasRoute<ChatRoute>() -> entry.toRoute<ChatRoute>().let { Screen.Chat(it.serverId, it.sessionId) }
                    else -> Screen.Other
                },
            )
        }
    }

    // Also update Screen.Chat immediately whenever active tab changes inside WorkspaceShellRoute.
    LaunchedEffect(shellState.activeServer?.id, shellState.workspace.activeTab) {
        val srvId = shellState.activeServer?.id ?: return@LaunchedEffect
        val tab = shellState.workspace.activeTab
        if (tab is WorkspaceTab.AgentChat) {
            screens.onScreen(Screen.Chat(srvId, tab.sessionId))
        } else {
            screens.onScreen(Screen.Server(srvId))
        }
    }

    // Notification deep-link -> switch server & open chat tab directly in MobileWorkspaceShellScreen.
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        shellViewModel.session.openChatOnServer(link.serverId, link.sessionId, link.title)
        nav.navigate(WorkspaceShellRoute) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
        onLinkHandled()
    }

    NavHost(nav, startDestination = startDestination) {
        composable<WorkspaceShellRoute> {
            MobileWorkspaceShellScreen(
                viewModel = shellViewModel,
                onOpenConnect = { nav.navigate(ConnectRoute(canNavigateBack = loadedServers.isNotEmpty())) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }

        composable<ConnectRoute> { entry ->
            val route = entry.toRoute<ConnectRoute>()
            val connectVm: ConnectServerViewModel = hiltViewModel()
            ConnectServerScreen(
                viewModel = connectVm,
                canNavigateBack = route.canNavigateBack,
                onBack = { nav.popBackStack() },
                onConnected = { serverId, chatSessionId ->
                    shellViewModel.session.selectServer(serverId)
                    if (!chatSessionId.isNullOrBlank()) {
                        shellViewModel.session.openChat(chatSessionId)
                    }
                    nav.navigate(WorkspaceShellRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNeedsSetup = { address, setupToken ->
                    nav.navigate(SetupRoute(baseUrl = address.baseUrl.toString(), setupToken = setupToken))
                },
            )
        }

        composable<SetupRoute> { entry ->
            val route = entry.toRoute<SetupRoute>()
            val setupVm: ServerSetupViewModel = hiltViewModel()
            LaunchedEffect(route) {
                when {
                    route.reconfigureServerId != null -> setupVm.loadForReconfigure(route.reconfigureServerId)
                    route.baseUrl != null -> setupVm.loadForFirstRun(ServerAddress.of(route.baseUrl), route.setupToken)
                }
            }
            ServerSetupWizardScreen(
                viewModel = setupVm,
                onCompleted = { serverId ->
                    shellViewModel.session.selectServer(serverId)
                    nav.navigate(WorkspaceShellRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onAddServer = { nav.navigate(ConnectRoute(canNavigateBack = true)) },
                onReconfigureServer = { serverId -> nav.navigate(SetupRoute(reconfigureServerId = serverId)) },
                shellViewModel = shellViewModel,
            )
        }

        composable<ChatRoute> { entry ->
            ChatScreen(title = entry.toRoute<ChatRoute>().title, onBack = { nav.popBackStack() })
        }

        composable<TerminalRoute> {
            TerminalScreen(onBack = { nav.popBackStack() })
        }
    }
}
