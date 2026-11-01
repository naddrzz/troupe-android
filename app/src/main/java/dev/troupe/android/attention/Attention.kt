package dev.troupe.android.attention

import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.chat.protocol.SessionSummary

enum class AttentionKind { Permission, Question, Stopped }

/**
 * Something on a Paired Server waiting on the user. [key] is unique per server: the request or
 * question id, or `stopped:<sessionId>`. [since] is epoch milliseconds: when the app first saw the
 * request (the host sends no time with it), or the stopped Session's last activity.
 */
data class AttentionItem(
    val serverId: String,
    val key: String,
    val sessionId: String,
    val projectName: String?,
    val sessionTitle: String?,
    val kind: AttentionKind,
    val since: Long,
)

/** A turn that ended in [sessionId]: `prompt_complete`, whatever its stop reason. */
data class FinishedTurn(val serverId: String, val sessionId: String, val projectName: String?, val sessionTitle: String?)

/** A Paired Server's Attention; [ready] once its first index and the requests of its live Sessions are in. */
data class ServerAttention(val ready: Boolean, val items: List<AttentionItem>)

/**
 * The Attention list of one server, oldest first, from the persisted-session [index]:
 * - the pending requests of each Session, as its open Agent Chat sees them ([openChats]) or else as
 *   the background subscription does ([tracked]), less those answered from this device ([resolved]);
 * - a stopped chat for each Session whose agent failed (index status `error`).
 *
 * A Session missing from the index is not an Agent Chat (a warm-up, say) and is left out.
 */
internal fun deriveAttention(
    serverId: String,
    index: Collection<SessionSummary>,
    tracked: Map<String, List<PendingRequest>>,
    openChats: Map<String, List<PendingRequest>>,
    resolved: Set<String>,
    projects: Map<String, String>,
    seenAt: (String) -> Long,
): List<AttentionItem> = index.flatMap { session ->
    fun item(key: String, kind: AttentionKind, since: Long) = AttentionItem(
        serverId = serverId,
        key = key,
        sessionId = session.sessionId,
        projectName = session.projectId?.let(projects::get),
        sessionTitle = session.title?.takeIf(String::isNotBlank),
        kind = kind,
        since = since,
    )
    val requests = (openChats[session.sessionId] ?: tracked[session.sessionId]).orEmpty()
        .filter { it.id !in resolved }
        .map { request ->
            val kind = if (request is PendingRequest.Permission) AttentionKind.Permission else AttentionKind.Question
            item(request.id, kind, seenAt(request.id))
        }
    if (session.status == STATUS_ERROR) requests + item("stopped:${session.sessionId}", AttentionKind.Stopped, session.lastActivityAt)
    else requests
}.sortedBy { it.since }

internal const val STATUS_ACTIVE = "active"
private const val STATUS_ERROR = "error"
