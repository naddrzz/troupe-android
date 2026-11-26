package dev.troupe.android.automations.ui

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.protocol.ApprovalDecision
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationDetail
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.Graph
import dev.troupe.android.automations.protocol.Issue
import dev.troupe.android.automations.protocol.LastRun
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.NodeSpec
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.protocol.Schedule
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.Trigger
import dev.troupe.android.automations.state.AutomationsUi
import dev.troupe.android.automations.state.Control
import dev.troupe.android.automations.state.ControlAction
import dev.troupe.android.automations.state.QueryError
import dev.troupe.android.automations.state.QueryState
import dev.troupe.android.automations.state.Route
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFormatTest {
    private val base = TroupeJson.decodeFromString<Automation>(AutomationFixtures.load("automation.json"))
    private val kitchenSink = TroupeJson.decodeFromString<Automation>(AutomationFixtures.load("automation-kitchen-sink.json"))

    private val steps = TroupeJson.decodeFromJsonElement<List<AutomationStep>>(
        TroupeJson.parseToJsonElement(AutomationFixtures.load("steps.json")).jsonObject.getValue("data"),
    )

    private fun waiting(waitUntil: Long?, status: StepStatus = StepStatus.Waiting) =
        steps.first().copy(status = status, waitUntil = waitUntil)

    @Test
    fun manualAndScheduleTriggersSummarize() {
        assertEquals(TriggerSummary.Manual, triggerSummary(Trigger.Manual))
        assertEquals(
            TriggerSummary.Cron("0 9 * * 1-5", "Asia/Jakarta"),
            triggerSummary(Trigger.ScheduleTrigger(Schedule.Cron("0 9 * * 1-5", "Asia/Jakarta"))),
        )
        assertEquals(
            TriggerSummary.Every(3_600_000L),
            triggerSummary(Trigger.ScheduleTrigger(Schedule.Every(everyMs = 3_600_000L, anchorMs = 5L))),
        )
        assertEquals(TriggerSummary.At(1_773_046_800_000L), triggerSummary(Trigger.ScheduleTrigger(Schedule.At(1_773_046_800_000L))))
    }

    @Test
    fun needsApprovalWhenApprovedRevisionIsNull() {
        assertTrue(needsApproval(base.copy(revision = 3, approvedRevision = null)))
    }

    @Test
    fun needsApprovalWhenApprovedRevisionIsBehind() {
        assertTrue(needsApproval(base.copy(revision = 3, approvedRevision = 2)))
        assertFalse(needsApproval(base.copy(revision = 3, approvedRevision = 3)))
    }

    @Test
    fun canRunNowFalseWhenViewOnly() {
        val approved = base.copy(revision = 3, approvedRevision = 3)
        assertTrue(canRunNow(approved, viewOnly = false))
        assertFalse(canRunNow(approved, viewOnly = true))
    }

    @Test
    fun canRunNowFalseWhenApprovalIsNeeded() {
        assertFalse(canRunNow(base.copy(revision = 3, approvedRevision = 2), viewOnly = false))
        assertFalse(canRunNow(base.copy(revision = 3, approvedRevision = null), viewOnly = false))
    }

    @Test
    fun stepTimeLeftCountsDownForWaitingSteps() {
        assertEquals(4_000L, stepTimeLeftMs(waiting(waitUntil = 10_000L), nowMs = 6_000L))
    }

    @Test
    fun stepTimeLeftClampsAtZero() {
        assertEquals(0L, stepTimeLeftMs(waiting(waitUntil = 10_000L), nowMs = 10_000L))
        assertEquals(0L, stepTimeLeftMs(waiting(waitUntil = 10_000L), nowMs = 25_000L))
    }

    @Test
    fun stepTimeLeftIsNullWithoutAWaitingStatusOrWaitUntil() {
        assertNull(stepTimeLeftMs(waiting(waitUntil = null), nowMs = 6_000L))
        assertNull(stepTimeLeftMs(waiting(waitUntil = 10_000L, status = StepStatus.Running), nowMs = 6_000L))
        assertNull(stepTimeLeftMs(waiting(waitUntil = 10_000L, status = StepStatus.Succeeded), nowMs = 6_000L))
    }

    /** `AutomationList.tsx` renders the server's order; each sort key below is one the web could have used and does not. */
    private fun ids(list: List<Automation>) = list.map { it.id.value }

    private fun named(id: String, block: Automation.() -> Automation = { this }) =
        kitchenSink.copy(id = AutomationId(id), name = id).block()

    private fun lastRun(status: RunStatus, finishedAt: Long) = LastRun(RunId("r"), status, finishedAt)

    @Test
    fun orderForListKeepsTheServerOrderForAnEmptyAndASingleList() {
        assertEquals(emptyList<Automation>(), orderForList(emptyList()))
        assertEquals(listOf(base), orderForList(listOf(base)))
    }

    @Test
    fun orderForListDoesNotSortByName() {
        val list = listOf(named("zulu"), named("alpha"), named("mike"))
        assertEquals(listOf("zulu", "alpha", "mike"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotFloatNeedsApprovalFirst() {
        val list = listOf(
            named("armed") { copy(revision = 2, approvedRevision = 2) },
            named("pending") { copy(revision = 2, approvedRevision = 1) },
        )
        assertEquals(listOf("armed", "pending"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotGroupByEnabledState() {
        val list = listOf(
            named("off1") { copy(enabled = false) },
            named("on") { copy(enabled = true) },
            named("off2") { copy(enabled = false) },
        )
        assertEquals(listOf("off1", "on", "off2"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotFloatUnseenFailuresFirst() {
        val list = listOf(named("quiet") { copy(unseenFailures = 0) }, named("loud") { copy(unseenFailures = 5) })
        assertEquals(listOf("quiet", "loud"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotSortByLastRun() {
        val list = listOf(
            named("old") { copy(lastRun = lastRun(RunStatus.Failed, 1_000L)) },
            named("never") { copy(lastRun = null) },
            named("new") { copy(lastRun = lastRun(RunStatus.Succeeded, 9_000L)) },
        )
        assertEquals(listOf("old", "never", "new"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotSortByNextRun() {
        val list = listOf(
            named("later") { copy(nextRunAt = 9_000L) },
            named("none") { copy(nextRunAt = null) },
            named("soon") { copy(nextRunAt = 1_000L) },
        )
        assertEquals(listOf("later", "none", "soon"), ids(orderForList(list)))
    }

    @Test
    fun orderForListDoesNotSortByUpdatedOrCreatedTime() {
        val list = listOf(
            named("stale") { copy(createdAt = 5_000L, updatedAt = 1_000L) },
            named("fresh") { copy(createdAt = 1_000L, updatedAt = 9_000L) },
        )
        assertEquals(listOf("stale", "fresh"), ids(orderForList(list)))
    }

    private val unreachable = QueryError.Unreachable

    @Test
    fun cardFailuresKeepEnableAndRunApart() {
        val failures = cardFailures(QueryError.Unauthorized, unreachable, viewOnly = false, approvalHintShown = false)
        assertEquals(CardFailures(QueryError.Unauthorized, unreachable), failures)
    }

    @Test
    fun cardFailuresAreEmptyWhenViewOnly() {
        assertEquals(CardFailures(null, null), cardFailures(unreachable, unreachable, viewOnly = true, approvalHintShown = false))
    }

    @Test
    fun cardFailuresDropNotApprovedRunFailureWhileTheHintShows() {
        assertNull(cardFailures(null, QueryError.NotApproved, viewOnly = false, approvalHintShown = true).run)
        assertEquals(QueryError.NotApproved, cardFailures(null, QueryError.NotApproved, viewOnly = false, approvalHintShown = false).run)
    }

    @Test
    fun cardFailuresKeepOtherRunFailuresWhileTheHintShows() {
        assertEquals(unreachable, cardFailures(null, unreachable, viewOnly = false, approvalHintShown = true).run)
    }

    @Test
    fun cardFailuresKeepTheEnableFailureWhenOnlyRunIsNotApproved() {
        val failures = cardFailures(unreachable, QueryError.NotApproved, viewOnly = false, approvalHintShown = true)
        assertEquals(CardFailures(unreachable, null), failures)
    }

    private val detail = TroupeJson.decodeFromJsonElement<AutomationDetail>(
        TroupeJson.parseToJsonElement(AutomationFixtures.load("automation-detail.json")).jsonObject.getValue("data"),
    )
    private val runs = TroupeJson.decodeFromJsonElement<List<AutomationRun>>(
        TroupeJson.parseToJsonElement(AutomationFixtures.load("runs.json")).jsonObject.getValue("data"),
    )

    private fun uiOf(
        pending: Set<Control> = emptySet(),
        failures: Map<Control, QueryError> = emptyMap(),
        enabledOverride: Map<AutomationId, Boolean> = emptyMap(),
    ) = AutomationsUi(Route.Overview, QueryState(), null, null, null, pending, failures, enabledOverride, emptyList())

    private val approvedAuto = base.copy(revision = 3, approvedRevision = 3, enabled = true)
    private val unapprovedAuto = base.copy(revision = 3, approvedRevision = 2, enabled = true)

    @Test
    fun controlsOfAnApprovedAutomationAreOpen() {
        val controls = automationControls(approvedAuto, uiOf(), viewOnly = false)
        assertTrue(controls.enabled)
        assertTrue(controls.canRun)
        assertFalse(controls.approvalNeeded)
        assertFalse(controls.showHint)
        assertFalse(controls.enableBusy)
        assertFalse(controls.runBusy)
    }

    @Test
    fun controlsOfAnUnapprovedAutomationGateRunAndShowTheHint() {
        val controls = automationControls(unapprovedAuto, uiOf(), viewOnly = false)
        assertTrue(controls.approvalNeeded)
        assertFalse(controls.canRun)
        assertTrue(controls.showHint)
    }

    @Test
    fun controlsOfAViewOnlySessionShowNoHintAndNoFailures() {
        val ui = uiOf(failures = mapOf(Control(unapprovedAuto.id.value, ControlAction.Run) to unreachable))
        val controls = automationControls(unapprovedAuto, ui, viewOnly = true)
        assertTrue(controls.viewOnly)
        assertFalse(controls.canRun)
        assertFalse(controls.showHint)
        assertEquals(CardFailures(null, null), controls.failures)
    }

    @Test
    fun controlsTakeTheEnabledOverrideOverTheLoadedValue() {
        val ui = uiOf(enabledOverride = mapOf(approvedAuto.id to false))
        assertFalse(automationControls(approvedAuto, ui, viewOnly = false).enabled)
    }

    @Test
    fun controlsReadInFlightAndFailureStateOfTheirOwnAutomationOnly() {
        val mine = Control(approvedAuto.id.value, ControlAction.Enable)
        val other = Control("another", ControlAction.Run)
        val ui = uiOf(pending = setOf(mine, other), failures = mapOf(other to unreachable, mine to QueryError.Unauthorized))
        val controls = automationControls(approvedAuto, ui, viewOnly = false)
        assertTrue(controls.enableBusy)
        assertFalse(controls.runBusy)
        assertEquals(CardFailures(QueryError.Unauthorized, null), controls.failures)
    }

    @Test
    fun everyNodeSpecMapsToItsOwnKind() {
        val kinds = kitchenSink.graph.nodes.map { nodeKind(it.spec) }
        assertEquals(NodeKind.entries.toSet(), kinds.toSet())
        assertEquals(NodeKind.entries.size, kinds.toSet().size)
    }

    @Test
    fun outlineKeepsGraphOrderWithKindAndLabel() {
        val outline = graphOutline(detail.automation.graph)
        assertEquals(detail.automation.graph.nodes.map { it.label }, outline.map { it.label })
        assertEquals(detail.automation.graph.nodes.map { nodeKind(it.spec) }, outline.map { it.kind })
    }

    @Test
    fun outlineFallsBackToTheNodeIdForABlankLabel() {
        val node = detail.automation.graph.nodes.first()
        val outline = graphOutline(Graph(listOf(node.copy(label = " ")), emptyList()))
        assertEquals(listOf(OutlineRow(nodeKind(node.spec), node.id.value)), outline)
    }

    @Test
    fun outlineOfAnEmptyGraphIsEmpty() {
        assertEquals(emptyList<OutlineRow>(), graphOutline(Graph(emptyList(), emptyList())))
    }

    @Test
    fun issueNodeLabelNamesOnlyNodesThatAreInTheGraph() {
        val node = detail.automation.graph.nodes.first()
        val labels = nodeLabels(detail.automation.graph)
        assertEquals(node.label, issueNodeLabel(Issue(node.id, "some_code", "m"), labels))
        assertNull(issueNodeLabel(Issue(NodeId("not_in_graph"), "some_code", "m"), labels))
        assertNull(issueNodeLabel(Issue(null, "some_code", "m"), labels))
        assertEquals("not_in_graph", labelOrId(labels, NodeId("not_in_graph")))
    }

    @Test
    fun nextRunTimesShowTheFirstThreeOfThePolicy() {
        val policy = detail.policy.copy(nextRuns = listOf(1L, 2L, 3L, 4L, 5L))
        assertEquals(listOf(1L, 2L, 3L), nextRunTimes(base.copy(nextRunAt = 9L), policy))
    }

    @Test
    fun nextRunTimesFallBackToTheAutomationsOwnNextRun() {
        val policy = detail.policy.copy(nextRuns = emptyList())
        assertEquals(listOf(9L), nextRunTimes(base.copy(nextRunAt = 9L), policy))
        assertEquals(emptyList<Long>(), nextRunTimes(base.copy(nextRunAt = null), policy))
    }

    @Test
    fun runDurationIsNullWhileRunningAndNeverNegative() {
        val finished = runs.first { it.finishedAt != null }
        assertEquals(finished.finishedAt!! - finished.startedAt, runDurationMs(finished))
        assertNull(runDurationMs(runs.first { it.finishedAt == null }))
        assertEquals(0L, runDurationMs(finished.copy(finishedAt = finished.startedAt - 5)))
    }

    private val run1 = runs.first { it.id.value == "run-1" }
    private val parkedOnWait = runs.first { it.id.value == "run-2" }
    private val parkedOnApproval = runs.first { it.id.value == "run-3" }

    @Test
    fun runParkedOnAnApprovalNodeIsWaitingForApproval() {
        assertTrue(isWaitingForApproval(parkedOnApproval))
        assertEquals("gate", parkedApproval(parkedOnApproval)?.id?.value)
    }

    @Test
    fun runParkedOnAWaitNodeKeepsReadingAsRunning() {
        assertFalse(isWaitingForApproval(parkedOnWait))
        assertNull(parkedApproval(parkedOnWait))
    }

    @Test
    fun runThatIsNotParkedIsNotWaitingForApproval() {
        assertFalse(isWaitingForApproval(parkedOnApproval.copy(parked = null)))
        assertFalse(isWaitingForApproval(run1))
    }

    @Test
    fun finishedRunIsNeverWaitingForApproval() {
        assertFalse(isWaitingForApproval(parkedOnApproval.copy(status = RunStatus.Cancelled)))
        assertFalse(isWaitingForApproval(parkedOnApproval.copy(status = RunStatus.Failed)))
    }

    @Test
    fun parkOnANodeThatIsNotInTheGraphIsNoApproval() {
        val parked = parkedOnApproval.parked!!
        assertNull(parkedApproval(parkedOnApproval.copy(parked = parked.copy(nodeId = NodeId("gone")))))
    }

    @Test
    fun approvalCardSitsOnTheParkedStepInTheParkedIteration() {
        val prompt = approvalPrompt(parkedOnApproval, steps)!!
        assertEquals("gate", prompt.step.nodeId.value)
        assertEquals(1, prompt.step.iteration)
        assertEquals("Ship b?", prompt.message)
    }

    @Test
    fun approvalCardIgnoresOtherIterationsAndOtherRuns() {
        val otherIteration = parkedOnApproval.copy(parked = parkedOnApproval.parked!!.copy(iteration = 0))
        assertNull(approvalPrompt(otherIteration, steps))
        assertNull(approvalPrompt(parkedOnApproval, steps.filter { it.runId.value != "run-3" }))
    }

    @Test
    fun approvalCardNeedsAParkedApprovalRunAndLoadedSteps() {
        assertNull(approvalPrompt(parkedOnWait, steps))
        assertNull(approvalPrompt(run1, steps))
        assertNull(approvalPrompt(parkedOnApproval, emptyList()))
    }

    @Test
    fun approvalCardIsGoneOnceTheStepHasADecision() {
        val decided = buildJsonObject {
            put("message", "Ship b?")
            put("decision", "approved")
            put("decided_at", 1_773_046_900_000L)
        }
        val withDecision = steps.map { if (it.runId.value == "run-3") it.copy(output = decided) else it }
        assertNull(approvalPrompt(parkedOnApproval, withDecision))
    }

    @Test
    fun approvalCardNeedsAnApprovalShapedOutput() {
        assertNull(approvalPrompt(parkedOnApproval, steps.map { if (it.runId.value == "run-3") it.copy(output = null) else it }))
        assertNull(approvalPrompt(parkedOnApproval, steps.map { if (it.runId.value == "run-3") it.copy(output = buildJsonObject { put("items", 1) }) else it }))
    }

    @Test
    fun aStepStoppedByACancelIsCancelledNotFailed() {
        val failed = steps.first { it.status == StepStatus.Failed }
        assertFalse(isCancelledStep(failed))
        assertTrue(isCancelledStep(failed.copy(error = "cancelled")))
        assertFalse(isCancelledStep(failed.copy(status = StepStatus.Skipped, error = "cancelled")))
    }

    @Test
    fun labelOrIdFallsBackForABlankLabel() {
        assertEquals("gate", labelOrId(mapOf(NodeId("gate") to "  "), NodeId("gate")))
        assertEquals("Ship it?", labelOrId(mapOf(NodeId("gate") to "Ship it?"), NodeId("gate")))
    }

    @Test
    fun noOutputOrJsonNullHasNothingToShow() {
        val step = steps.first()
        assertNull(displayedOutput(step.copy(output = null)))
        assertNull(displayedOutput(step.copy(output = JsonNull)))
        assertEquals(step.output, displayedOutput(step))
    }

    @Test
    fun outputShowsAStringAsIsAndOtherJsonPrettyPrinted() {
        assertEquals(OutputPreview("plain", truncated = false), outputPreview(JsonPrimitive("plain")))
        val preview = outputPreview(buildJsonObject { put("a", 1) })
        assertEquals("{\n    \"a\": 1\n}", preview.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun hugeOutputIsCutAndSaysSo() {
        val preview = outputPreview(JsonPrimitive("x".repeat(50)), maxChars = 20)
        assertEquals(OutputPreview("x".repeat(20), truncated = true), preview)
        assertEquals(OutputPreview("x".repeat(20), truncated = false), outputPreview(JsonPrimitive("x".repeat(20)), maxChars = 20))
        assertEquals(OUTPUT_PREVIEW_CHARS, outputPreview(JsonPrimitive("y".repeat(OUTPUT_PREVIEW_CHARS * 3))).text.length)
    }

    @Test
    fun prettyPrintedOutputMatchesTheJsonPrinter() {
        val nested = buildJsonObject {
            put("s", "a \"quoted\"\nline")
            put("n", 1.5)
            put("b", true)
            put("z", JsonNull)
            put("list", JsonArray(listOf(JsonPrimitive(1), buildJsonObject { put("k", "v") }, JsonArray(emptyList()))))
            put("empty", buildJsonObject {})
        }
        val printed = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), nested)
        assertEquals(OutputPreview(printed, truncated = false), outputPreview(nested))
        assertEquals(OutputPreview(printed.take(25), truncated = true), outputPreview(nested, maxChars = 25))
    }

    @Test(timeout = 10_000)
    fun hugeStructuredOutputIsCutWithoutPrintingItWhole() {
        // Two billion elements: printing them all before cutting would never finish (or run out of memory).
        val endless = JsonArray(object : AbstractList<JsonElement>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): JsonElement = JsonPrimitive(index)
        })
        val preview = outputPreview(endless, maxChars = 100)
        assertTrue(preview.truncated)
        assertEquals(100, preview.text.length)
        assertTrue(preview.text, preview.text.startsWith("[\n    0,\n    1,\n"))
    }

    @Test(timeout = 10_000)
    fun aHugeStringInsideStructuredOutputIsCutBeforeItIsEncoded() {
        // Escapes and a long key and value: the cut text must equal the start of the full encoding.
        val big = "a\"b\n\\".repeat(500_000)
        val nested = buildJsonObject {
            put(big.take(5_000), 1)
            put("stdout", big)
        }
        val printed = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), nested)
        assertEquals(OutputPreview(printed.take(100), truncated = true), outputPreview(nested, maxChars = 100))
        assertEquals(OutputPreview(printed.take(6_000), truncated = true), outputPreview(nested, maxChars = 6_000))
    }

    @Test
    fun aDecisionShowsOnlyOnAStepOfAnApprovalNode() {
        val decided = buildJsonObject {
            put("message", "Ship b?")
            put("decision", "rejected")
            put("decided_at", 5L)
        }
        val graph = parkedOnApproval.graph
        val gateStep = steps.first { it.runId.value == "run-3" && it.nodeId.value == "gate" }.copy(output = decided)
        assertEquals(ApprovalDecision.Rejected, stepDecision(gateStep, graph)?.decision)

        val other = graph.nodes.first { it.spec !is NodeSpec.Approval }
        assertNull(stepDecision(gateStep.copy(nodeId = other.id, status = StepStatus.Failed, error = "boom"), graph))
        assertNull(stepDecision(gateStep.copy(nodeId = NodeId("not_in_graph")), graph))
    }

    @Test
    fun issueOnANodeWithABlankLabelNamesTheNodeId() {
        val labels = mapOf(NodeId("gate") to "  ")
        assertEquals("gate", issueNodeLabel(Issue(NodeId("gate"), "some_code", "m"), labels))
    }
}
