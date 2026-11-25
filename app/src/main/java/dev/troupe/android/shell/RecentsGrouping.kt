package dev.troupe.android.shell

import dev.troupe.android.chat.protocol.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The headings of the drawer's chat list, in Troupe's order (`acp-history-persistence.ts`). */
enum class RecentsGroup { Today, Yesterday, Earlier, Quick }

/** When a recents row's chat was made, as Troupe words it (`relative-time.ts`). */
sealed interface RecentsTime {
    /** Today: the clock time alone. */
    data class Clock(val time: String) : RecentsTime
    data class Yesterday(val time: String) : RecentsTime

    /** Older: month and day, like "Nov 3". */
    data class Date(val date: String) : RecentsTime
}

/**
 * Splits [sessions] (newest first) into the drawer's groups. Quick chats get their own group
 * whatever their age; the rest go by the day of [recentsAt], the time their row shows. Empty
 * groups are left out.
 */
fun groupRecents(
    sessions: List<SessionSummary>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<RecentsGroup, List<SessionSummary>>> {
    val today = dayOf(now, zone)
    return sessions.groupBy { session ->
        when {
            session.isQuick -> RecentsGroup.Quick
            else -> when (dayOf(session.recentsAt, zone)) {
                today -> RecentsGroup.Today
                today.minusDays(1) -> RecentsGroup.Yesterday
                else -> RecentsGroup.Earlier
            }
        }
    }.toSortedMap().map { (group, list) -> group to list }
}

fun recentsTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): RecentsTime? {
    if (at <= 0) return null
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val clock = DateTimeFormatter.ofPattern("h:mm a", locale).format(moment)
    val today = dayOf(now, zone)
    return when (moment.toLocalDate()) {
        today -> RecentsTime.Clock(clock)
        today.minusDays(1) -> RecentsTime.Yesterday(clock)
        else -> RecentsTime.Date(DateTimeFormatter.ofPattern("MMM d", locale).format(moment))
    }
}

/** When the chat was made (Troupe's rows show creation time), or its last activity on older hosts. */
val SessionSummary.recentsAt: Long get() = createdAt.takeIf { it > 0 } ?: lastActivityAt

private fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
