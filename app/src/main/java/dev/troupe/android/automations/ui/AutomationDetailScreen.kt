package dev.troupe.android.automations.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationDetail
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.Issue
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunPolicy
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.protocol.RunTrigger
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.theme.tabularNums
import dev.troupe.android.core.ui.StatusDot
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons

/**
 * The detail route: a way back to the list, then the detail inside pull-to-refresh. Loading and a failed first load
 * show the list's state views; once the detail has data it stays on screen through reloads and errors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationDetailRoute(
    controller: AutomationsController,
    ui: AutomationsUi,
    viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val query = ui.detail
    val detail = query?.data
    val error = query?.error
    Column(modifier.fillMaxSize().background(TroupeTheme.palette.background)) {
        TroupeButton(
            text = stringResource(R.string.automations_back_to_list),
            icon = TroupeIcons.ArrowLeft,
            variant = TroupeButtonVariant.Ghost,
            minHeight = 48.dp,
            onClick = { controller.back() },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = query?.refreshing == true || ui.runs?.refreshing == true,
            onRefresh = controller::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                detail != null -> AutomationDetailScreen(ui, detail, controller, viewOnly)
                error != null -> AutomationErrorView(error, retrying = query.refreshing, onRetry = controller::refresh)
                else -> AutomationLoadingView()
            }
        }
    }
}

/**
 * One automation: header with the controls, trigger and next runs, run policy, graph issues, a read-only outline of
 * the graph and the run history. Each query shows its own error: a failed reload of the detail is a banner above it,
 * and the runs' error stays inside the Runs section.
 */
@Composable
fun AutomationDetailScreen(
    ui: AutomationsUi,
    detail: AutomationDetail,
    controller: AutomationsController,
    viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val automation = detail.automation
    val detailError = ui.detail?.error
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (detailError != null) {
            item(key = "detail-error") {
                AutomationErrorBanner(detailError, retrying = ui.detail.refreshing, onRetry = controller::refresh)
            }
        }
        item(key = "header") {
            DetailHeader(
                automation = automation,
                controls = automationControls(automation, ui, viewOnly),
                onToggle = { controller.toggleEnabled(automation.id, it) },
                onRun = { controller.runNow(automation.id) },
            )
        }
        item(key = "trigger") { TriggerSection(automation, detail.policy, Modifier.padding(top = 8.dp)) }
        item(key = "policy") { RunPolicyCard(automation, detail.policy, Modifier.padding(top = 8.dp)) }
        item(key = "issues") { IssuesSection(detail.issues, nodeLabels(automation.graph), Modifier.padding(top = 8.dp)) }
        item(key = "outline") { OutlineSection(automation, Modifier.padding(top = 8.dp)) }
        runsSection(ui, controller)
    }
}

@Composable
private fun DetailHeader(automation: Automation, controls: AutomationControls, onToggle: (Boolean) -> Unit, onRun: () -> Unit) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val (stateLabel, stateColor) = automationStateStyle(controls)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(color = stateColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = automation.name,
                    style = typo.titleLarge,
                    color = palette.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stateLabel, style = typo.labelMedium, color = stateColor)
            }
        }
        if (!controls.viewOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The Switch carries the spoken name; this label only shows what it is for.
                Text(
                    text = stringResource(R.string.automations_enabled_label),
                    style = typo.bodyMedium,
                    color = palette.foreground,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                )
                AutomationEnableSwitch(automation.name, controls, onToggle)
            }
        }
        AutomationRunControls(controls, onRun)
    }
}

@Composable
private fun TriggerSection(automation: Automation, policy: RunPolicy, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val nextRuns = nextRunTimes(automation, policy)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AutomationSectionHeading(stringResource(R.string.automations_trigger_title))
        Text(triggerLine(automation), style = typo.bodyMedium, color = palette.foreground)
        Text(stringResource(R.string.automations_next_runs_label), style = typo.labelMedium, color = palette.mutedForeground)
        if (nextRuns.isEmpty()) {
            val empty = if (triggerSummary(automation.trigger) == TriggerSummary.Manual) {
                R.string.automations_next_runs_manual
            } else {
                R.string.automations_next_runs_none
            }
            Text(stringResource(empty), style = typo.bodyMedium, color = palette.mutedForeground)
        } else {
            nextRuns.forEach { Text(formatDateTime(it), style = typo.bodyMedium.tabularNums(), color = palette.foreground) }
        }
    }
}

@Composable
private fun IssuesSection(issues: List<Issue>, labels: Map<NodeId, String>, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AutomationSectionHeading(
            if (issues.isEmpty()) {
                stringResource(R.string.automations_issues_title)
            } else {
                pluralStringResource(R.plurals.automations_issues_to_fix, issues.size, issues.size)
            },
        )
        if (issues.isEmpty()) {
            Text(stringResource(R.string.automations_issues_none), style = typo.bodyMedium, color = palette.mutedForeground)
        }
        issues.forEach { issue ->
            val nodeLabel = issueNodeLabel(issue, labels)
            Text(
                text = if (nodeLabel != null) stringResource(R.string.automations_issue_on_node, nodeLabel, issue.message) else issue.message,
                style = typo.bodySmall,
                color = palette.destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.destructive.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun OutlineSection(automation: Automation, modifier: Modifier = Modifier) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val rows = graphOutline(automation.graph)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AutomationSectionHeading(stringResource(R.string.automations_outline_title))
        if (rows.isEmpty()) {
            Text(stringResource(R.string.automations_outline_empty), style = typo.bodyMedium, color = palette.mutedForeground)
        } else {
            Column(Modifier.automationCard()) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(row.label, style = typo.bodyMedium, color = palette.foreground)
                        Text(stringResource(nodeKindRes(row.kind)), style = typo.labelMedium, color = palette.mutedForeground)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.runsSection(ui: AutomationsUi, controller: AutomationsController) {
    val query = ui.runs
    val runs = query?.data
    val error = query?.error
    item(key = "runs-heading") {
        AutomationSectionHeading(stringResource(R.string.automations_runs_title), Modifier.padding(top = 8.dp))
    }
    if (error != null) {
        item(key = "runs-error") {
            AutomationErrorBanner(error, retrying = query.refreshing, onRetry = controller::refresh)
        }
    }
    when {
        runs == null -> if (error == null) item(key = "runs-loading") { RunsNote(R.string.automations_runs_loading) }
        runs.isEmpty() -> item(key = "runs-empty") { RunsNote(R.string.automations_no_runs) }
        else -> items(runs, key = { it.id.value }) { run -> RunRow(run, onOpen = { controller.openRun(run.id) }) }
    }
}

@Composable
private fun RunsNote(@StringRes text: Int) {
    Text(stringResource(text), style = TroupeTheme.typography.bodyMedium, color = TroupeTheme.palette.mutedForeground)
}

@Composable
private fun RunRow(run: AutomationRun, onOpen: () -> Unit) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val resources = LocalResources.current
    val statusColor = when (run.status) {
        RunStatus.Running -> palette.brandText
        RunStatus.Succeeded -> palette.success
        RunStatus.Failed -> palette.destructive
        RunStatus.Cancelled, RunStatus.Skipped -> palette.mutedForeground
    }
    val title = stringResource(R.string.automations_run_row_title, stringResource(runTriggerRes(run.trigger)), stringResource(runStatusRes(run.status)))
    val duration = runDurationMs(run)
    val finishedAt = run.finishedAt
    val stopReason = run.stopReason?.takeUnless { it.isBlank() }
    val summary = run.summary?.takeUnless { it.isBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .defaultMinSize(minHeight = 48.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = typo.titleSmall, color = statusColor)
        Text(stringResource(R.string.automations_run_started, formatDateTime(run.startedAt)), style = typo.bodySmall.tabularNums(), color = palette.mutedForeground)
        if (finishedAt != null && duration != null) {
            Text(
                text = stringResource(R.string.automations_run_finished, formatDateTime(finishedAt), formatDuration(resources, duration)),
                style = typo.bodySmall.tabularNums(),
                color = palette.mutedForeground,
            )
        }
        if (stopReason != null) {
            Text(stringResource(R.string.automations_run_stop_reason, stopReason), style = typo.bodySmall, color = palette.mutedForeground)
        }
        if (summary != null) {
            Text(summary, style = typo.bodySmall, color = palette.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A section title, announced as a heading. */
@Composable
internal fun AutomationSectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TroupeTheme.typography.titleSmall,
        color = TroupeTheme.palette.foreground,
        modifier = modifier.semantics { heading() },
    )
}

/** The bordered card surface of the detail's grouped sections. */
@Composable
internal fun Modifier.automationCard(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return fillMaxWidth()
        .clip(shape)
        .background(TroupeTheme.palette.card)
        .border(0.5.dp, TroupeTheme.palette.border, shape)
}

@StringRes
private fun nodeKindRes(kind: NodeKind): Int = when (kind) {
    NodeKind.Http -> R.string.automations_node_http
    NodeKind.Agent -> R.string.automations_node_agent
    NodeKind.Transform -> R.string.automations_node_transform
    NodeKind.Condition -> R.string.automations_node_condition
    NodeKind.Fail -> R.string.automations_node_fail
    NodeKind.ForEach -> R.string.automations_node_for_each
    NodeKind.Merge -> R.string.automations_node_merge
    NodeKind.Wait -> R.string.automations_node_wait
    NodeKind.Approval -> R.string.automations_node_approval
    NodeKind.McpTool -> R.string.automations_node_mcp_tool
}

@StringRes
private fun runTriggerRes(trigger: RunTrigger): Int = when (trigger) {
    RunTrigger.Scheduled -> R.string.automations_run_trigger_scheduled
    RunTrigger.CatchUp -> R.string.automations_run_trigger_catch_up
    RunTrigger.Manual -> R.string.automations_run_trigger_manual
}
