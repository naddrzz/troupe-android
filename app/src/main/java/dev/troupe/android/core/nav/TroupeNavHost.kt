package dev.troupe.android.core.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.troupe.android.chat.ChatScreen
import dev.troupe.android.lock.SettingsScreen
import dev.troupe.android.pairing.PairScreen
import dev.troupe.android.projects.ProjectScreen
import dev.troupe.android.projects.ProjectsScreen
import dev.troupe.android.servers.ServersScreen
import dev.troupe.android.terminal.TerminalScreen
import kotlinx.serialization.Serializable

@Serializable data object ServersRoute

@Serializable data object PairRoute

@Serializable data object SettingsRoute

/** `serverId` is read by [dev.troupe.android.projects.ProjectsViewModel] from its SavedStateHandle. */
@Serializable data class ProjectsRoute(val serverId: String)

/** `serverId` and `projectId` are read by [dev.troupe.android.terminal.TerminalsViewModel]. */
@Serializable data class ProjectRoute(val serverId: String, val projectId: String, val projectName: String)

/** Read by [dev.troupe.android.terminal.TerminalViewModel] from its SavedStateHandle. */
@Serializable data class TerminalRoute(val serverId: String, val terminalId: String)

/** `serverId` and `sessionId` are read by [dev.troupe.android.chat.ChatViewModel]. */
@Serializable data class ChatRoute(val serverId: String, val sessionId: String, val title: String?)

/**
 * The app's screens. [screens] learns which one is on top; a [link] (from a notification) opens its
 * Agent Chat above its server's screen, then [onLinkHandled] clears it.
 */
@Composable
fun TroupeNavHost(screens: Screens, link: ChatLink?, onLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val destination = entry.destination
            screens.onScreen(
                when {
                    destination.hasRoute<ProjectsRoute>() -> Screen.Server(entry.toRoute<ProjectsRoute>().serverId)
                    destination.hasRoute<ChatRoute>() -> entry.toRoute<ChatRoute>().let { Screen.Chat(it.serverId, it.sessionId) }
                    else -> Screen.Other
                },
            )
        }
    }
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        nav.navigate(ProjectsRoute(link.serverId)) { popUpTo(ServersRoute) }
        nav.navigate(ChatRoute(link.serverId, link.sessionId, link.title))
        onLinkHandled()
    }
    NavHost(nav, startDestination = ServersRoute) {
        composable<ServersRoute> {
            ServersScreen(
                onPair = { nav.navigate(PairRoute) },
                onOpenServer = { nav.navigate(ProjectsRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable<PairRoute> {
            PairScreen(
                onPaired = { serverId ->
                    nav.navigate(ProjectsRoute(serverId)) { popUpTo(ServersRoute) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<ProjectsRoute> { entry ->
            val serverId = entry.toRoute<ProjectsRoute>().serverId
            ProjectsScreen(
                onBack = { nav.popBackStack() },
                onOpenProject = { nav.navigate(ProjectRoute(serverId, it.id, it.name)) },
                onOpenChat = { sessionId, title -> nav.navigate(ChatRoute(serverId, sessionId, title)) },
            )
        }
        composable<ProjectRoute> { entry ->
            val route = entry.toRoute<ProjectRoute>()
            ProjectScreen(
                projectName = route.projectName,
                onBack = { nav.popBackStack() },
                onOpenTerminal = { nav.navigate(TerminalRoute(route.serverId, it)) },
                onOpenChat = { sessionId, title -> nav.navigate(ChatRoute(route.serverId, sessionId, title)) },
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
