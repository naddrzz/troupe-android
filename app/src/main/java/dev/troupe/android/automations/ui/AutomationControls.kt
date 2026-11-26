package dev.troupe.android.automations.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.automations.state.QueryError
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant

/** The state word and its colour: needs-approval wins, as on the web; the word carries the meaning, the colour only backs it. */
@Composable
internal fun automationStateStyle(controls: AutomationControls): Pair<String, Color> {
    val palette = TroupeTheme.palette
    return when {
        controls.approvalNeeded -> stringResource(R.string.automations_state_needs_approval) to palette.warning
        controls.enabled -> stringResource(R.string.automations_state_armed) to palette.success
        else -> stringResource(R.string.automations_state_disabled) to palette.mutedForeground
    }
}

/** The enable Switch of the list card and the detail header; nothing for a view-only session. */
@Composable
internal fun AutomationEnableSwitch(
    name: String,
    controls: AutomationControls,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controls.viewOnly) return
    val description = stringResource(R.string.automations_enable_description, name)
    Switch(
        checked = controls.enabled,
        onCheckedChange = onToggle,
        enabled = !controls.enableBusy,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/**
 * Everything under the Switch's row, in order: the Enable failure, the Run button, the Run failure and the approval hint.
 * Emits into the caller's column so both screens space it the same way.
 */
@Composable
internal fun AutomationRunControls(controls: AutomationControls, onRun: () -> Unit) {
    controls.failures.enable?.let { FailureLine(R.string.automations_failure_enable, it) }
    if (!controls.viewOnly) {
        TroupeButton(
            text = stringResource(R.string.automations_run),
            variant = TroupeButtonVariant.Secondary,
            enabled = controls.canRun,
            loading = controls.runBusy,
            minHeight = 48.dp,
            onClick = onRun,
        )
    }
    controls.failures.run?.let { FailureLine(R.string.automations_failure_run, it) }
    if (controls.showHint) {
        Text(stringResource(R.string.automations_approval_hint), style = TroupeTheme.typography.bodySmall, color = TroupeTheme.palette.warning)
    }
}

/** One line under a control: what could not be done, and why. Announced when it appears. */
@Composable
internal fun FailureLine(@StringRes action: Int, error: QueryError) {
    Text(
        text = stringResource(action, automationErrorText(error)),
        style = TroupeTheme.typography.bodySmall,
        color = TroupeTheme.palette.destructive,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
