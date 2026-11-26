package dev.troupe.android.automations.ui

import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.Graph
import dev.troupe.android.automations.protocol.Issue
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.NodeSpec
import dev.troupe.android.automations.protocol.RunPolicy
import dev.troupe.android.automations.protocol.Schedule
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.Trigger
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.automations.state.Control
import dev.troupe.android.automations.state.ControlAction
import dev.troupe.android.automations.state.QueryError

/** What a trigger reads as; the screens map each variant to a string resource. */
sealed interface TriggerSummary {
    data object Manual : TriggerSummary

    data class Every(val everyMs: Long) : TriggerSummary

    data class Cron(val expr: String, val timezone: String) : TriggerSummary

    data class At(val atMs: Long) : TriggerSummary
}

fun triggerSummary(trigger: Trigger): TriggerSummary = when (trigger) {
    Trigger.Manual -> TriggerSummary.Manual
    is Trigger.ScheduleTrigger -> when (val schedule = trigger.schedule) {
        is Schedule.Cron -> TriggerSummary.Cron(schedule.expr, schedule.timezone)
        is Schedule.Every -> TriggerSummary.Every(schedule.everyMs)
        is Schedule.At -> TriggerSummary.At(schedule.at)
    }
}

/** An automation whose current revision nobody approved yet; a null `approvedRevision` has never been approved. */
fun needsApproval(a: Automation): Boolean = a.approvedRevision != a.revision

/** Run now needs write access and an approved revision. */
fun canRunNow(a: Automation, viewOnly: Boolean): Boolean = !viewOnly && !needsApproval(a)

/** Time left on a parked step; null unless it is `Waiting` with a `waitUntil`, never negative. */
fun stepTimeLeftMs(step: AutomationStep, nowMs: Long): Long? {
    val until = step.waitUntil ?: return null
    return if (step.status == StepStatus.Waiting) maxOf(0L, until - nowMs) else null
}

/**
 * The order the list shows. Ported from the web's `AutomationList.tsx`, which does no sorting
 * and no grouping: it renders `automations` as the server sent them (`use-automations.ts`
 * adds none either), so name, enabled state, unseen failures, last run, next run and
 * update time never reorder rows. The list keeps the server's order.
 */
fun orderForList(list: List<Automation>): List<Automation> = list

/**
 * What the Switch, the Run button and their feedback show for one automation. The list card and the detail header
 * both render it, so their gating, in-flight states and failure lines cannot drift apart.
 */
data class AutomationControls(
    val viewOnly: Boolean,
    /** The Switch's value: the pending override while a toggle settles, else the loaded `enabled`. */
    val enabled: Boolean,
    val enableBusy: Boolean,
    val runBusy: Boolean,
    val approvalNeeded: Boolean,
    val canRun: Boolean,
    val showHint: Boolean,
    val failures: CardFailures,
)

fun automationControls(automation: Automation, ui: AutomationsUi, viewOnly: Boolean): AutomationControls {
    val enable = Control(automation.id.value, ControlAction.Enable)
    val run = Control(automation.id.value, ControlAction.Run)
    val approvalNeeded = needsApproval(automation)
    val showHint = approvalNeeded && !viewOnly
    return AutomationControls(
        viewOnly = viewOnly,
        enabled = ui.enabledOverride[automation.id] ?: automation.enabled,
        enableBusy = enable in ui.pending,
        runBusy = run in ui.pending,
        approvalNeeded = approvalNeeded,
        canRun = canRunNow(automation, viewOnly),
        showHint = showHint,
        failures = cardFailures(ui.failures[enable], ui.failures[run], viewOnly, approvalHintShown = showHint),
    )
}

/** The ten node kinds a graph can hold; the screens map each to a string resource, never to its class name. */
enum class NodeKind { Http, Agent, Transform, Condition, Fail, ForEach, Merge, Wait, Approval, McpTool }

fun nodeKind(spec: NodeSpec): NodeKind = when (spec) {
    is NodeSpec.Http -> NodeKind.Http
    is NodeSpec.Agent -> NodeKind.Agent
    is NodeSpec.Transform -> NodeKind.Transform
    is NodeSpec.Condition -> NodeKind.Condition
    is NodeSpec.Fail -> NodeKind.Fail
    is NodeSpec.ForEach -> NodeKind.ForEach
    is NodeSpec.Merge -> NodeKind.Merge
    is NodeSpec.Wait -> NodeKind.Wait
    is NodeSpec.Approval -> NodeKind.Approval
    is NodeSpec.McpTool -> NodeKind.McpTool
}

/** One line of the read-only graph outline. Edges and ports are not shown. */
data class OutlineRow(val kind: NodeKind, val label: String)

/** The nodes in graph order; a blank label falls back to the node id so no row is empty. */
fun graphOutline(graph: Graph): List<OutlineRow> =
    graph.nodes.map { OutlineRow(nodeKind(it.spec), it.label.ifBlank { it.id.value }) }

/** Node labels by id; a policy entry or issue whose node is not in the graph falls back to the id. */
fun nodeLabels(graph: Graph): Map<NodeId, String> = graph.nodes.associate { it.id to it.label }

fun labelOrId(labels: Map<NodeId, String>, id: NodeId): String = labels[id] ?: id.value

/** The node an issue names, as the graph labels it; null when it names none or one that is not in the graph. */
fun issueNodeLabel(issue: Issue, labels: Map<NodeId, String>): String? = issue.nodeId?.let { labels[it] }

private const val MAX_NEXT_RUNS = 3

/** The next runs to show: the policy's first three, else the automation's own `nextRunAt`, else none. */
fun nextRunTimes(automation: Automation, policy: RunPolicy): List<Long> =
    policy.nextRuns.take(MAX_NEXT_RUNS).ifEmpty { listOfNotNull(automation.nextRunAt) }

/** How long a finished run took; null while it runs. Never negative. */
fun runDurationMs(run: AutomationRun): Long? = run.finishedAt?.let { maxOf(0L, it - run.startedAt) }

/** The failure lines a list card shows: [enable] under the Switch's row, [run] under the Run button. */
data class CardFailures(val enable: QueryError?, val run: QueryError?)

/**
 * Nothing shows to a view-only session, which has no controls. A `NotApproved` Run failure is dropped while the
 * approval hint is on the card: the hint already says it.
 */
fun cardFailures(enable: QueryError?, run: QueryError?, viewOnly: Boolean, approvalHintShown: Boolean): CardFailures =
    if (viewOnly) {
        CardFailures(null, null)
    } else {
        CardFailures(enable, run?.takeUnless { it == QueryError.NotApproved && approvalHintShown })
    }
