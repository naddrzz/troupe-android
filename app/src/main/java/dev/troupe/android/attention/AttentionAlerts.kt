package dev.troupe.android.attention

import androidx.annotation.StringRes
import dev.troupe.android.R
import dev.troupe.android.core.nav.Screen

/**
 * Which Attention is new, server by server. A server's first ready list is the baseline: what was
 * already waiting when the app connected is listed on the server screen, not notified.
 */
internal class AttentionAlerts {
    private val known = HashMap<String, Set<String>>()

    data class Changes(val added: List<AttentionItem>, val gone: List<Pair<String, String>>)

    /** The items new since the last call, and the `(serverId, key)` of those gone (answered, or their server removed). */
    fun update(servers: Map<String, ServerAttention>): Changes {
        val added = mutableListOf<AttentionItem>()
        val gone = mutableListOf<Pair<String, String>>()
        (known.keys - servers.keys).forEach { id -> known.remove(id)?.forEach { gone += id to it } }
        for ((serverId, attention) in servers) {
            if (!attention.ready) continue
            val keys = attention.items.map { it.key }.toSet()
            val before = known[serverId]
            if (before != null) {
                added += attention.items.filter { it.key !in before }
                gone += (before - keys).map { serverId to it }
            }
            known[serverId] = keys
        }
        return Changes(added, gone)
    }
}

/**
 * Whether new Attention gets a notification: while the app is in the foreground on another screen
 * than that server's screen or that Agent Chat, where it already shows.
 */
internal fun mayAlert(item: AttentionItem, foreground: Boolean, screen: Screen): Boolean =
    foreground && !screen.shows(item.serverId, item.sessionId)

/** A string resource and its arguments, resolved by the Android layer. */
internal data class Text(@StringRes val res: Int, val args: List<String> = emptyList())

/** A notification's title and body; a null [text] shows the title alone. */
internal data class NotificationContent(val title: Text, val text: Text?)

/**
 * The Attention notification: its kind as the title, `Project · Session` as the body. While the app
 * lock is on it says only "Needs attention", like the public version shown on the lock screen.
 */
internal fun attentionContent(item: AttentionItem, locked: Boolean): NotificationContent {
    if (locked) return NotificationContent(Text(R.string.attention_needs_attention), null)
    val title = when (item.kind) {
        AttentionKind.Permission -> R.string.attention_kind_permission
        AttentionKind.Question -> R.string.attention_kind_question
        AttentionKind.Stopped -> R.string.attention_kind_stopped
    }
    return NotificationContent(Text(title), chatName(item.projectName, item.sessionTitle))
}

/** `Project · Session`, with "Untitled chat" for a Session without a title. */
internal fun chatName(projectName: String?, sessionTitle: String?): Text = when {
    projectName != null && sessionTitle != null -> Text(R.string.attention_chat_in_project, listOf(projectName, sessionTitle))
    projectName != null -> Text(R.string.attention_untitled_chat_in_project, listOf(projectName))
    sessionTitle != null -> Text(R.string.attention_chat, listOf(sessionTitle))
    else -> Text(R.string.attention_untitled_chat)
}
