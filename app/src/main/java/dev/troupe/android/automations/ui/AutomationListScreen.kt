package dev.troupe.android.automations.ui

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.theme.tabularNums
import dev.troupe.android.core.ui.StatusDot

/**
 * The overview route: the project's Automations as the server sent them, inside pull-to-refresh. Once the list has
 * data it stays on screen through reloads and errors; a failed reload shows a banner above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(
    controller: AutomationsController,
    ui: AutomationsUi,
    viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val list = ui.list
    val automations = list.data
    val error = list.error
    PullToRefreshBox(
        isRefreshing = list.refreshing,
        onRefresh = controller::refresh,
        modifier = modifier.fillMaxSize().background(TroupeTheme.palette.background),
    ) {
        when {
            automations == null && error != null ->
                AutomationErrorView(error, retrying = list.refreshing, onRetry = controller::refresh)
            automations == null -> AutomationLoadingView()
            else -> Column(Modifier.fillMaxSize()) {
                if (error != null) AutomationErrorBanner(error, retrying = list.refreshing, onRetry = controller::refresh)
                if (automations.isEmpty()) {
                    AutomationEmptyView(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(orderForList(automations), key = { it.id.value }) { automation ->
                            AutomationCard(
                                automation = automation,
                                controls = automationControls(automation, ui, viewOnly),
                                onOpen = { controller.open(automation.id) },
                                onToggle = { controller.toggleEnabled(automation.id, it) },
                                onRun = { controller.runNow(automation.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    controls: AutomationControls,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val (stateLabel, stateColor) = automationStateStyle(controls)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.card)
            .border(0.5.dp, palette.border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(color = stateColor)
            Column(
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(automation.name, style = typo.titleSmall, color = palette.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stateLabel, style = typo.labelMedium, color = stateColor)
                Text(triggerLine(automation), style = typo.bodySmall, color = palette.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(nextRunLine(automation), style = typo.bodySmall.tabularNums(), color = palette.mutedForeground)
                Text(lastRunLine(automation), style = typo.bodySmall.tabularNums(), color = palette.mutedForeground)
                if (automation.unseenFailures > 0) UnseenFailuresBadge(automation.unseenFailures)
            }
            AutomationEnableSwitch(automation.name, controls, onToggle)
        }
        AutomationRunControls(controls, onRun)
    }
}

@Composable
private fun UnseenFailuresBadge(count: Int) {
    val palette = TroupeTheme.palette
    Text(
        text = pluralStringResource(R.plurals.automations_unseen_failures, count, count),
        style = TroupeTheme.typography.labelMedium.tabularNums(),
        color = palette.destructive,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.destructive.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun triggerLine(automation: Automation): String {
    val resources = LocalResources.current
    return when (val summary = triggerSummary(automation.trigger)) {
        TriggerSummary.Manual -> stringResource(R.string.automations_trigger_manual)
        is TriggerSummary.Every -> stringResource(R.string.automations_trigger_every, formatDuration(resources, summary.everyMs))
        is TriggerSummary.Cron -> stringResource(R.string.automations_trigger_cron, summary.expr, summary.timezone)
        is TriggerSummary.At -> stringResource(R.string.automations_trigger_at, formatDateTime(summary.atMs))
    }
}

@Composable
private fun nextRunLine(automation: Automation): String =
    automation.nextRunAt?.let { stringResource(R.string.automations_next_run, formatDateTime(it)) }
        ?: stringResource(R.string.automations_no_next_run)

@Composable
private fun lastRunLine(automation: Automation): String {
    val last = automation.lastRun ?: return stringResource(R.string.automations_no_runs)
    return stringResource(R.string.automations_last_run, stringResource(runStatusRes(last.status)), formatDateTime(last.finishedAt))
}

@StringRes
internal fun runStatusRes(status: RunStatus): Int = when (status) {
    RunStatus.Running -> R.string.automations_run_status_running
    RunStatus.Succeeded -> R.string.automations_run_status_succeeded
    RunStatus.Failed -> R.string.automations_run_status_failed
    RunStatus.Cancelled -> R.string.automations_run_status_cancelled
    RunStatus.Skipped -> R.string.automations_run_status_skipped
}

/** A run's status word: one parked on an approval reads as waiting for it, as the web does. */
@StringRes
internal fun runStatusRes(run: AutomationRun): Int =
    if (isWaitingForApproval(run)) R.string.automations_run_status_waiting_for_approval else runStatusRes(run.status)

/** Local wall-clock time such as "Mon, Oct 30, 9:00 AM"; the year shows only when it is not the current one. */
@Composable
internal fun formatDateTime(ms: Long): String {
    val flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
    return DateUtils.formatDateTime(LocalContext.current, ms, flags)
}

/** Ported from the web's `formatDuration`: the two largest units, such as "1 h 30 min". */
internal fun formatDuration(resources: Resources, ms: Long): String {
    fun pair(first: String, second: String) = resources.getString(R.string.automations_duration_pair, first, second)
    if (ms < 1000) return resources.getString(R.string.automations_duration_ms, maxOf(0L, ms))
    val seconds = (ms + 500) / 1000
    if (seconds < 60) return resources.getString(R.string.automations_duration_s, seconds)
    val minutes = seconds / 60
    if (minutes < 60) {
        val min = resources.getString(R.string.automations_duration_min, minutes)
        val rest = seconds % 60
        return if (rest != 0L) pair(min, resources.getString(R.string.automations_duration_s, rest)) else min
    }
    val hours = minutes / 60
    if (hours < 24) {
        val h = resources.getString(R.string.automations_duration_h, hours)
        val rest = minutes % 60
        return if (rest != 0L) pair(h, resources.getString(R.string.automations_duration_min, rest)) else h
    }
    val days = hours / 24
    val d = resources.getString(R.string.automations_duration_d, days)
    val rest = hours % 24
    return if (rest != 0L) pair(d, resources.getString(R.string.automations_duration_h, rest)) else d
}
