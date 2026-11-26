package dev.troupe.android.automations.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.troupe.android.R
import dev.troupe.android.automations.protocol.ApprovalDecision
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.parseApprovalOutput
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.automations.state.Control
import dev.troupe.android.automations.state.ControlAction
import dev.troupe.android.automations.state.Notice
import dev.troupe.android.automations.state.Route
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.theme.tabularNums
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

private const val TICK_MS = 1_000L
private const val PULL_MIN_MS = 600L
private const val PULL_MAX_MS = 10_000L

/**
 * The run route: a way back to the automation, the notice about an approval someone else handled, then the run. The
 * run is looked up in the runs the detail route already loaded; until it is there the screen shows a loading view.
 */
@Composable
fun RunDetailRoute(
    controller: AutomationsController,
    ui: AutomationsUi,
    route: Route.Run,
    viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val runs = ui.runs
    val run = runs?.data?.firstOrNull { it.id == route.runId }
    val runsError = runs?.error
    Column(modifier.fillMaxSize().background(TroupeTheme.palette.background)) {
        TroupeButton(
            text = stringResource(R.string.automations_back_to_automation),
            icon = TroupeIcons.ArrowLeft,
            variant = TroupeButtonVariant.Ghost,
            minHeight = 48.dp,
            onClick = { controller.back() },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (Notice.ApprovalAlreadyHandled in ui.notices) {
            ApprovalHandledNotice(onDismiss = { controller.dismissNotice(Notice.ApprovalAlreadyHandled) })
        }
        when {
            run != null -> RunDetailScreen(ui, run, controller, viewOnly, Modifier.weight(1f))
            runsError != null -> AutomationErrorView(runsError, retrying = runs.refreshing, onRetry = controller::refresh, modifier = Modifier.weight(1f))
            runs?.loaded == true -> RunMessage(R.string.automations_run_missing, Modifier.weight(1f))
            else -> AutomationLoadingView(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RunMessage(@StringRes text: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(text),
            style = TroupeTheme.typography.bodyMedium,
            color = TroupeTheme.palette.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun ApprovalHandledNotice(onDismiss: () -> Unit) {
    val palette = TroupeTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.warning.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.automations_notice_approval_handled),
            style = TroupeTheme.typography.bodySmall,
            color = palette.warning,
            maxLines = 2,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TroupeButton(
            text = stringResource(R.string.automations_dismiss),
            variant = TroupeButtonVariant.Ghost,
            minHeight = 48.dp,
            onClick = onDismiss,
        )
    }
}

/**
 * One run: its header with Cancel, then its steps. The steps follow the run live (the controller polls while it runs
 * and reloads on changes); a failed reload is a banner above the last good steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    ui: AutomationsUi,
    run: AutomationRun,
    controller: AutomationsController,
    viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val query = ui.steps
    val steps = query?.data
    val error = query?.error
    val labels = nodeLabels(run.graph)
    val prompt = steps?.let { approvalPrompt(run, it) }
    val now = rememberNowWhileWaiting(steps)

    // The steps reload on their own every few seconds while the run goes; only a pull shows the indicator.
    var pulling by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(query?.refreshing == true || ui.runs?.refreshing == true)
    LaunchedEffect(pulling) {
        if (pulling) {
            delay(PULL_MIN_MS)
            withTimeoutOrNull(PULL_MAX_MS) { snapshotFlow { busy }.first { !it } }
            pulling = false
        }
    }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = {
            pulling = true
            controller.refresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (error != null) {
                item(key = "steps-error") {
                    AutomationErrorBanner(error, retrying = query.refreshing, onRetry = controller::refresh)
                }
            }
            item(key = "header") { RunHeader(run, ui, controller, viewOnly) }
            item(key = "steps-heading") {
                AutomationSectionHeading(stringResource(R.string.automations_steps_title), Modifier.padding(top = 8.dp))
            }
            when {
                steps == null -> if (error == null) item(key = "steps-loading") { StepsNote(R.string.automations_steps_loading) }
                steps.isEmpty() -> item(key = "steps-empty") { StepsNote(R.string.automations_steps_empty) }
                else -> itemsIndexed(steps, key = { index, step -> "$index:${step.nodeId.value}:${step.iteration}" }) { _, step ->
                    StepRow(
                        step = step,
                        label = labelOrId(labels, step.nodeId),
                        now = now,
                        approvalCard = if (!viewOnly && prompt != null && prompt.step === step) {
                            { ApprovalSection(run, prompt, ui, controller) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsNote(@StringRes text: Int) {
    Text(stringResource(text), style = TroupeTheme.typography.bodyMedium, color = TroupeTheme.palette.mutedForeground)
}

@Composable
private fun RunHeader(run: AutomationRun, ui: AutomationsUi, controller: AutomationsController, viewOnly: Boolean) {
    val cancel = Control(run.id.value, ControlAction.Cancel)
    val cancelFailure = ui.failures[cancel]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = runTitle(run),
            style = TroupeTheme.typography.titleLarge,
            color = runStatusColor(run),
            modifier = Modifier.semantics { heading() },
        )
        RunSummaryLines(run, summaryMaxLines = Int.MAX_VALUE)
        if (run.status == RunStatus.Running && !viewOnly) {
            TroupeButton(
                text = stringResource(R.string.automations_cancel_run),
                variant = TroupeButtonVariant.Secondary,
                loading = cancel in ui.pending,
                minHeight = 48.dp,
                onClick = { controller.cancelRun(run.id) },
                modifier = Modifier.padding(top = 6.dp),
            )
            cancelFailure?.let { FailureLine(R.string.automations_failure_cancel, it) }
        }
    }
}

/** The approval card with the failure of each decision under it. */
@Composable
private fun ApprovalSection(run: AutomationRun, prompt: ApprovalPrompt, ui: AutomationsUi, controller: AutomationsController) {
    val approve = Control(run.id.value, ControlAction.Approve)
    val reject = Control(run.id.value, ControlAction.Reject)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ApprovalCard(
            message = prompt.message,
            pending = approve in ui.pending || reject in ui.pending,
            onApprove = { controller.decide(run.id, prompt.step.nodeId, approved = true) },
            onReject = { controller.decide(run.id, prompt.step.nodeId, approved = false) },
        )
        ui.failures[approve]?.let { FailureLine(R.string.automations_failure_approve, it) }
        ui.failures[reject]?.let { FailureLine(R.string.automations_failure_reject, it) }
    }
}

/**
 * The clock the time-left lines read. It ticks once a second, and only while a step waits with time left and the
 * screen is started. The State is read inside [TimeLeftText] alone, so a tick recomposes those lines and not the list.
 */
@Composable
private fun rememberNowWhileWaiting(steps: List<AutomationStep>?): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lastDeadline = steps?.mapNotNull { if (it.status == StepStatus.Waiting) it.waitUntil else null }?.maxOrNull()
    if (lastDeadline != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, lastDeadline) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                do {
                    now.longValue = System.currentTimeMillis()
                    delay(TICK_MS)
                } while (now.longValue < lastDeadline)
            }
        }
    }
    return now
}

@Composable
private fun TimeLeftText(step: AutomationStep, now: State<Long>) {
    val left = stepTimeLeftMs(step, now.value) ?: return
    Text(
        text = stringResource(R.string.automations_step_time_left, formatDuration(LocalResources.current, left)),
        style = TroupeTheme.typography.bodySmall.tabularNums(),
        color = TroupeTheme.palette.warning,
    )
}

@Composable
private fun stepStatusStyle(step: AutomationStep): Pair<String, Color> {
    val palette = TroupeTheme.palette
    return when {
        isCancelledStep(step) -> stringResource(R.string.automations_step_status_cancelled) to palette.mutedForeground
        else -> when (step.status) {
            StepStatus.Running -> stringResource(R.string.automations_step_status_running) to palette.brandText
            StepStatus.Succeeded -> stringResource(R.string.automations_step_status_succeeded) to palette.success
            StepStatus.Failed -> stringResource(R.string.automations_step_status_failed) to palette.destructive
            StepStatus.Skipped -> stringResource(R.string.automations_step_status_skipped) to palette.mutedForeground
            StepStatus.Waiting -> stringResource(R.string.automations_step_status_waiting) to palette.warning
        }
    }
}

@Composable
private fun StepRow(
    step: AutomationStep,
    label: String,
    now: State<Long>,
    approvalCard: (@Composable () -> Unit)?,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val resources = LocalResources.current
    val (statusLabel, statusColor) = stepStatusStyle(step)
    val started = step.startedAt
    val finished = step.finishedAt
    val decision = parseApprovalOutput(step.output)?.takeIf { it.decision != null }
    val error = step.error?.takeUnless { it.isBlank() || isCancelledStep(step) || decision != null }
    Column(Modifier.automationCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = typo.titleSmall, color = palette.foreground, modifier = Modifier.weight(1f))
            Text(statusLabel, style = typo.labelMedium, color = statusColor)
        }
        step.iteration?.let {
            Text(stringResource(R.string.automations_step_iteration, it + 1), style = typo.bodySmall, color = palette.mutedForeground)
        }
        if (started != null) {
            val text = if (finished != null) {
                stringResource(R.string.automations_step_started_took, formatDateTime(started), formatDuration(resources, maxOf(0L, finished - started)))
            } else {
                stringResource(R.string.automations_run_started, formatDateTime(started))
            }
            Text(text, style = typo.bodySmall.tabularNums(), color = palette.mutedForeground)
        }
        TimeLeftText(step, now)
        if (decision?.decision != null && decision.decidedAt != null) {
            val (word, color) = decisionStyle(decision.decision)
            Text(
                text = stringResource(R.string.automations_decision_line, word, formatDateTime(decision.decidedAt)),
                style = typo.bodySmall.tabularNums(),
                color = color,
            )
        }
        if (error != null) {
            Text(error, style = typo.bodySmall, color = palette.destructive, modifier = Modifier.automationErrorBox())
        }
        displayedOutput(step)?.let { StepOutput(it) }
        approvalCard?.invoke()
    }
}

@Composable
private fun decisionStyle(decision: ApprovalDecision): Pair<String, Color> {
    val palette = TroupeTheme.palette
    return when (decision) {
        ApprovalDecision.Approved -> stringResource(R.string.automations_decision_approved) to palette.success
        ApprovalDecision.Rejected -> stringResource(R.string.automations_decision_rejected) to palette.destructive
        ApprovalDecision.TimedOut -> stringResource(R.string.automations_decision_timed_out) to palette.destructive
    }
}

/** A step's output, folded away until asked for; it is formatted only once it is open, off the main thread. */
@Composable
private fun StepOutput(output: JsonElement) {
    val palette = TroupeTheme.palette
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.automations_output_expanded else R.string.automations_output_collapsed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (expanded) TroupeIcons.ChevronDown else TroupeIcons.ChevronRight,
                contentDescription = null,
                tint = palette.mutedForeground,
                modifier = Modifier.size(16.dp),
            )
            Text(stringResource(R.string.automations_output_title), style = TroupeTheme.typography.labelMedium, color = palette.mutedForeground)
        }
        if (expanded) OutputText(output)
    }
}

@Composable
private fun OutputText(output: JsonElement) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val preview by produceState<OutputPreview?>(null, output) { value = withContext(Dispatchers.Default) { outputPreview(output) } }
    val shown = preview
    if (shown == null) {
        Text(stringResource(R.string.automations_output_loading), style = typo.bodySmall, color = palette.mutedForeground)
        return
    }
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = shown.text,
            style = typo.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.foreground,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(shape)
                .background(palette.background)
                .border(0.5.dp, palette.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
        if (shown.truncated) {
            Text(stringResource(R.string.automations_output_truncated, OUTPUT_PREVIEW_CHARS), style = typo.bodySmall, color = palette.mutedForeground)
        }
    }
}
