package dev.troupe.android.core.nav

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.troupe.android.MainActivity

private const val ACTION_OPEN_CHAT = "dev.troupe.android.action.OPEN_CHAT"
private const val EXTRA_SERVER_ID = "serverId"
private const val EXTRA_SESSION_ID = "sessionId"
private const val EXTRA_TITLE = "title"

/** Opens [link]'s Agent Chat in the app; [requestCode] keeps one notification's intent apart from another's. */
fun openChatIntent(context: Context, link: ChatLink, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_CHAT)
        .putExtra(EXTRA_SERVER_ID, link.serverId)
        .putExtra(EXTRA_SESSION_ID, link.sessionId)
        .putExtra(EXTRA_TITLE, link.title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** The Agent Chat an [openChatIntent] leads to, or null for any other intent. */
fun Intent.chatLink(): ChatLink? {
    if (action != ACTION_OPEN_CHAT) return null
    val serverId = getStringExtra(EXTRA_SERVER_ID) ?: return null
    val sessionId = getStringExtra(EXTRA_SESSION_ID) ?: return null
    return ChatLink(serverId, sessionId, getStringExtra(EXTRA_TITLE))
}
