package dev.troupe.android.automations.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
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
    when (ui.route) {
        Route.Overview -> AutomationListScreen(controller, ui, viewOnly, modifier)
        is Route.Detail -> AutomationDetailRoute(controller, ui, viewOnly, modifier)
        // Replaced by the run screen.
        is Route.Run -> Box(modifier.fillMaxSize().background(TroupeTheme.palette.background)) {
            Text(
                text = stringResource(R.string.automations_run_placeholder),
                style = TroupeTheme.typography.bodyMedium,
                color = TroupeTheme.palette.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
