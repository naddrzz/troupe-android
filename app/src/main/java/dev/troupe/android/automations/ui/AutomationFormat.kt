package dev.troupe.android.automations.ui

import dev.troupe.android.automations.protocol.ApprovalStepOutput
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationNode
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.Graph
import dev.troupe.android.automations.protocol.Issue
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.NodeSpec
import dev.troupe.android.automations.protocol.RunPolicy
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.protocol.Schedule
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.Trigger
import dev.troupe.android.automations.protocol.parseApprovalOutput
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.automations.state.Control
import dev.troupe.android.automations.state.ControlAction
import dev.troupe.android.automations.state.QueryError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** Node labels by id; a policy entry, issue or step whose node is not in the graph, or has a blank label, falls back to the id. */
fun nodeLabels(graph: Graph): Map<NodeId, String> = graph.nodes.associate { it.id to it.label }

fun labelOrId(labels: Map<NodeId, String>, id: NodeId): String = labels[id]?.takeUnless { it.isBlank() } ?: id.value

/** The node an issue names, as the graph labels it (its id for a blank label); null when it names none or one that is not in the graph. */
fun issueNodeLabel(issue: Issue, labels: Map<NodeId, String>): String? =
    issue.nodeId?.takeIf { it in labels }?.let { labelOrId(labels, it) }

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

/** The approval node a run is parked on, from the run's own graph; null when it is not parked or is parked on a wait. */
fun parkedApproval(run: AutomationRun): AutomationNode? {
    val parked = run.parked ?: return null
    return run.graph.nodes.firstOrNull { it.id == parked.nodeId }?.takeIf { it.spec is NodeSpec.Approval }
}

/** A run parked on an approval reads as waiting for it while its status stays `Running`, as in the web's `runStatusPresentation`. */
fun isWaitingForApproval(run: AutomationRun): Boolean = run.status == RunStatus.Running && parkedApproval(run) != null

/** The approval card's step and the message it shows. */
data class ApprovalPrompt(val step: AutomationStep, val message: String)

/**
 * The step that carries the approval card: the row of the node the run is parked on, in the parked iteration, while
 * its output has a message and no decision yet. Null when the run is not waiting for an approval or the row is not
 * loaded yet.
 */
fun approvalPrompt(run: AutomationRun, steps: List<AutomationStep>): ApprovalPrompt? {
    val parked = run.parked?.takeIf { isWaitingForApproval(run) } ?: return null
    val step = steps.firstOrNull { it.runId == run.id && it.nodeId == parked.nodeId && it.iteration == parked.iteration } ?: return null
    val output = parseApprovalOutput(step.output) ?: return null
    return if (output.decision == null) ApprovalPrompt(step, output.message) else null
}

/**
 * The decision a step of [graph] records, only for a step of an approval node: another node's output can have the
 * same shape, and its row must keep showing its error.
 */
fun stepDecision(step: AutomationStep, graph: Graph): ApprovalStepOutput? {
    if (graph.nodes.firstOrNull { it.id == step.nodeId }?.spec !is NodeSpec.Approval) return null
    return parseApprovalOutput(step.output)?.takeIf { it.decision != null }
}

/** The error a step records when a cancel stops it; the web shows such a step as cancelled, not failed. */
private const val CANCELLED_ERROR = "cancelled"

fun isCancelledStep(step: AutomationStep): Boolean = step.status == StepStatus.Failed && step.error == CANCELLED_ERROR

/** The output a step shows; null when it has none or it is JSON null. */
fun displayedOutput(step: AutomationStep): JsonElement? = step.output?.takeUnless { it is JsonNull }

/** Output text shows at most this many characters, so a huge output cannot freeze the screen. */
const val OUTPUT_PREVIEW_CHARS = 10_000

data class OutputPreview(val text: String, val truncated: Boolean)

/**
 * A step output as text, as the web shows it: a string as is, anything else pretty-printed; cut at [maxChars].
 * Printing stops one character past the cut, so a huge output is never printed whole.
 */
fun outputPreview(output: JsonElement, maxChars: Int = OUTPUT_PREVIEW_CHARS): OutputPreview {
    val text = if (output is JsonPrimitive && output.isString) output.content else BoundedJsonPrinter(maxChars + 1).print(output)
    return if (text.length > maxChars) OutputPreview(text.take(maxChars), truncated = true) else OutputPreview(text, truncated = false)
}

/** Prints JSON as `Json { prettyPrint = true }` does (four-space indent), but stops once it holds [limit] characters. */
private class BoundedJsonPrinter(private val limit: Int) {
    private val out = StringBuilder()
    private val full get() = out.length >= limit

    fun print(element: JsonElement): String {
        element(element, depth = 0)
        return out.toString()
    }

    private fun append(s: String) {
        if (!full) out.append(s, 0, minOf(s.length, limit - out.length))
    }

    /**
     * [s] quoted and escaped. Only the first `limit - out.length` characters can be kept, and each source character
     * encodes to at least one, so a longer string is cut before it is encoded; the closing quote then falls past the
     * limit, exactly where the full encoding would put it.
     */
    private fun appendQuoted(s: String) {
        val room = limit - out.length
        if (room <= 0) return
        append(JsonPrimitive(if (s.length > room) s.substring(0, room) else s).toString())
    }

    private fun element(e: JsonElement, depth: Int) {
        when (e) {
            is JsonObject -> container("{", "}", e.entries, depth) { (key, value) ->
                appendQuoted(key)
                append(": ")
                element(value, depth + 1)
            }
            is JsonArray -> container("[", "]", e, depth) { element(it, depth + 1) }
            is JsonPrimitive -> if (e.isString) appendQuoted(e.content) else append(e.toString())
        }
    }

    private inline fun <T> container(open: String, close: String, items: Iterable<T>, depth: Int, item: (T) -> Unit) {
        val iterator = items.iterator()
        if (!iterator.hasNext()) {
            append(open + close)
            return
        }
        append(open)
        var first = true
        while (iterator.hasNext() && !full) {
            append(if (first) "\n" else ",\n")
            first = false
            append(INDENT.repeat(depth + 1))
            item(iterator.next())
        }
        append("\n" + INDENT.repeat(depth) + close)
    }

    private companion object {
        const val INDENT = "    "
    }
}
