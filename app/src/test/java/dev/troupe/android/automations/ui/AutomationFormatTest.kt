package dev.troupe.android.automations.ui

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.LastRun
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.protocol.Schedule
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.Trigger
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
}
