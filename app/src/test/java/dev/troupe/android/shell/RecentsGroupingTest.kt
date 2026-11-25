package dev.troupe.android.shell

import dev.troupe.android.chat.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The drawer's chat groups and row times, on a fixed clock. */
class RecentsGroupingTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(25, 21)

    private fun chat(id: String, lastActivityAt: Long, kind: String? = null, projectId: String? = "p1") =
        SessionSummary(sessionId = id, projectId = projectId, lastActivityAt = lastActivityAt, createdAt = lastActivityAt, kind = kind)

    @Test fun groupsByDayAndKeepsQuickChatsApart() {
        val groups = groupRecents(
            listOf(
                chat("today", at(25, 20, 44)),
                chat("quick", at(25, 20, 41), kind = "quick"),
                chat("unassigned", at(24, 9), projectId = "__unassigned__"),
                chat("yesterday", at(24, 23, 59)),
                chat("old", at(3, 8)),
            ),
            now,
            zone,
        )
        assertEquals(
            listOf(
                RecentsGroup.Today to listOf("today"),
                RecentsGroup.Yesterday to listOf("yesterday"),
                RecentsGroup.Earlier to listOf("old"),
                RecentsGroup.Quick to listOf("quick", "unassigned"),
            ),
            groups.map { (group, list) -> group to list.map { it.sessionId } },
        )
    }

    @Test fun emptyGroupsAreLeftOut() {
        assertEquals(emptyList<Any>(), groupRecents(emptyList(), now, zone))
        assertEquals(listOf(RecentsGroup.Earlier), groupRecents(listOf(chat("old", at(3, 8))), now, zone).map { it.first })
    }

    @Test fun rowTimeIsAClockTodayThenYesterdayThenADate() {
        assertEquals(RecentsTime.Clock("8:44 PM"), recentsTime(at(25, 20, 44), now, zone, Locale.US))
        assertEquals(RecentsTime.Yesterday("9:05 AM"), recentsTime(at(24, 9, 5), now, zone, Locale.US))
        assertEquals(RecentsTime.Date("Nov 3"), recentsTime(at(3, 8), now, zone, Locale.US))
        assertNull(recentsTime(0, now, zone, Locale.US))
    }
}
