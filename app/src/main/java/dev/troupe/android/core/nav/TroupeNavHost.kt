package dev.troupe.android.core.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.troupe.android.pairing.PairScreen
import dev.troupe.android.projects.ProjectScreen
import dev.troupe.android.projects.ProjectsScreen
import dev.troupe.android.servers.ServersScreen
import dev.troupe.android.terminal.TerminalScreen
import kotlinx.serialization.Serializable

@Serializable data object ServersRoute

@Serializable data object PairRoute

/** `serverId` is read by [dev.troupe.android.projects.ProjectsViewModel] from its SavedStateHandle. */
@Serializable data class ProjectsRoute(val serverId: String)

/** `serverId` and `projectId` are read by [dev.troupe.android.terminal.TerminalsViewModel]. */
@Serializable data class ProjectRoute(val serverId: String, val projectId: String, val projectName: String)

/** Read by [dev.troupe.android.terminal.TerminalViewModel] from its SavedStateHandle. */
@Serializable data class TerminalRoute(val serverId: String, val terminalId: String)

@Composable
fun TroupeNavHost() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = ServersRoute) {
        composable<ServersRoute> {
            ServersScreen(
                onPair = { nav.navigate(PairRoute) },
                onOpenServer = { nav.navigate(ProjectsRoute(it)) },
            )
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
            )
        }
        composable<ProjectRoute> { entry ->
            val route = entry.toRoute<ProjectRoute>()
            ProjectScreen(
                projectName = route.projectName,
                onBack = { nav.popBackStack() },
                onOpenTerminal = { nav.navigate(TerminalRoute(route.serverId, it)) },
            )
        }
        composable<TerminalRoute> {
            TerminalScreen(onBack = { nav.popBackStack() })
        }
    }
}
