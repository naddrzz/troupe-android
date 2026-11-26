package dev.troupe.android.automations.ui

import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.Schedule
import dev.troupe.android.automations.protocol.StepStatus
import dev.troupe.android.automations.protocol.Trigger
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
