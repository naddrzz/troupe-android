package dev.troupe.android.attention

import dev.troupe.android.R
import dev.troupe.android.core.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When new Attention notifies, and what its notification says. */
class AttentionAlertsTest {
    private fun item(key: String, server: String = "srv", session: String = "s1", kind: AttentionKind = AttentionKind.Permission) =
        AttentionItem(server, key, session, "troupe", "Fix login", kind, since = 0)

    @Test fun aServersFirstReadyListIsTheBaselineAndLaterItemsAreNew() {
        val alerts = AttentionAlerts()

        assertEquals(emptyList<AttentionItem>(), alerts.update(mapOf("srv" to ServerAttention(false, listOf(item("a"))))).added)
        assertEquals(emptyList<AttentionItem>(), alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("a"))))).added)

        val changes = alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("a"), item("b")))))
        assertEquals(listOf("b"), changes.added.map { it.key })
        assertEquals(emptyList<Pair<String, String>>(), changes.gone)
    }

    @Test fun answeredItemsAndRemovedServersAreGone() {
        val alerts = AttentionAlerts()
        alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("a"), item("b"))), "two" to ServerAttention(true, listOf(item("c", "two")))))

        assertEquals(listOf("srv" to "a"), alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("b"))), "two" to ServerAttention(true, listOf(item("c", "two"))))).gone)
        assertEquals(listOf("two" to "c"), alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("b"))))).gone)
    }

    @Test fun anItemThatComesBackIsNewAgain() {
        val alerts = AttentionAlerts()
        alerts.update(mapOf("srv" to ServerAttention(true, emptyList())))
        alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("stopped:s1")))))
        alerts.update(mapOf("srv" to ServerAttention(true, emptyList())))

        assertEquals(listOf("stopped:s1"), alerts.update(mapOf("srv" to ServerAttention(true, listOf(item("stopped:s1"))))).added.map { it.key })
    }

    @Test fun notifiesInTheForegroundUnlessTheScreenAlreadyShowsIt() {
        val attention = item("a", server = "srv", session = "s1")

        assertTrue(mayAlert(attention, foreground = true, screen = Screen.Other))
        assertTrue(mayAlert(attention, foreground = true, screen = Screen.Server("other")))
        assertTrue(mayAlert(attention, foreground = true, screen = Screen.Chat("srv", "s2")))
        assertFalse(mayAlert(attention, foreground = true, screen = Screen.Server("srv")))
        assertFalse(mayAlert(attention, foreground = true, screen = Screen.Chat("srv", "s1")))
        assertFalse(mayAlert(attention, foreground = false, screen = Screen.Other))
    }

    @Test fun theNotificationNamesTheKindAndTheChat() {
        assertEquals(
            NotificationContent(Text(R.string.attention_kind_permission), Text(R.string.attention_chat_in_project, listOf("troupe", "Fix login"))),
            attentionContent(item("a"), locked = false),
        )
        assertEquals(Text(R.string.attention_kind_question), attentionContent(item("a", kind = AttentionKind.Question), locked = false).title)
        assertEquals(Text(R.string.attention_kind_stopped), attentionContent(item("a", kind = AttentionKind.Stopped), locked = false).title)
    }

    @Test fun whileTheAppLockIsOnTheNotificationSaysOnlyNeedsAttention() {
        assertEquals(NotificationContent(Text(R.string.attention_needs_attention), null), attentionContent(item("a"), locked = true))
    }

    @Test fun anUntitledChatOrUnknownProjectStillReads() {
        assertEquals(Text(R.string.attention_untitled_chat_in_project, listOf("troupe")), chatName("troupe", null))
        assertEquals(Text(R.string.attention_chat, listOf("Fix login")), chatName(null, "Fix login"))
        assertEquals(Text(R.string.attention_untitled_chat), chatName(null, null))
    }
}
