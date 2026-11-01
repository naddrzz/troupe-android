package dev.troupe.android.core.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.troupe.android.pairing.PairScreen
import dev.troupe.android.projects.ProjectsScreen
import dev.troupe.android.servers.ServersScreen
import kotlinx.serialization.Serializable

@Serializable data object ServersRoute

@Serializable data object PairRoute

/** `serverId` is read by [dev.troupe.android.projects.ProjectsViewModel] from its SavedStateHandle. */
@Serializable data class ProjectsRoute(val serverId: String)

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
        composable<ProjectsRoute> {
            ProjectsScreen(onBack = { nav.popBackStack() })
        }
    }
}
