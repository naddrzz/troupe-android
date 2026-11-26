package dev.troupe.android.automations.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.Route
import dev.troupe.android.core.theme.TroupeTheme

/**
 * The Automations tab of a project: switches between the list, an automation's detail and one of its runs, and
 * handles back. Shows the loading view until the server's connection has produced a controller.
 */
@Composable
fun AutomationsScreen(projectId: String, viewOnly: Boolean, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<AutomationsViewModel>()
    val controller by viewModel.controllerFor(projectId).collectAsStateWithLifecycle()
    val current = controller
    if (current == null) {
        Box(modifier.fillMaxSize().background(TroupeTheme.palette.background)) { AutomationLoadingView() }
    } else {
        AutomationsRoutes(current, viewOnly, modifier)
    }
}

@Composable
private fun AutomationsRoutes(controller: AutomationsController, viewOnly: Boolean, modifier: Modifier) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.route != Route.Overview) { controller.back() }
    when (val route = ui.route) {
        Route.Overview -> AutomationListScreen(controller, ui, viewOnly, modifier)
        is Route.Detail -> AutomationDetailRoute(controller, ui, viewOnly, modifier)
        is Route.Run -> RunDetailRoute(controller, ui, route, viewOnly, modifier)
    }
}
