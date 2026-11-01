package dev.troupe.android.chat

import dev.troupe.android.chat.protocol.ChatFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Something an agent waits on the user for (Attention): a permission request or a question. */
sealed interface PendingRequest {
    val sessionId: String
    val agentId: String?
    /** The `requestId` or `questionId` the answer goes back under. */
    val id: String

    /** termul's `PermissionRequestEvent`: the tool call that needs approval and the agent's options. */
    data class Permission(
        override val sessionId: String,
        override val agentId: String?,
        override val id: String,
        val title: String,
        val options: List<PermissionOption>,
        /** The tool call it asks about: once that call runs or fails, the request was answered. */
        val toolCallId: String? = null,
    ) : PendingRequest

    /** termul's `AskUserQuestionEvent`: answered with option values, or one free text when [allowFreeText]. */
    data class Question(
        override val sessionId: String,
        override val agentId: String?,
        override val id: String,
        val question: String,
        val options: List<QuestionOption>,
        val allowFreeText: Boolean,
    ) : PendingRequest
}

/** ACP's `PermissionOption`; [kind] is `allow_once`, `allow_always`, `reject_once` or `reject_always`. */
data class PermissionOption(val optionId: String, val name: String, val kind: String?)

data class QuestionOption(val value: String, val label: String, val description: String?)

/**
 * The requests waiting on the user on one Paired Server, as the open Agent Chats see them: kept by
 * the server's chat connection, so every screen (the Attention list too) sees the same list. An
 * open Agent Chat updates its Session's entry as events and answers arrive, and [release]s it when
 * it closes. [resolved] holds the requests answered from this device.
 */
class PendingRequests {
    private val _bySession = MutableStateFlow<Map<String, List<PendingRequest>>>(emptyMap())
    /** Every Session open in an Agent Chat with something pending, in the order its requests arrived. */
    val bySession: StateFlow<Map<String, List<PendingRequest>>> = _bySession.asStateFlow()
    private val _resolved = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the requests answered from this device (or found already answered): never pending again. */
    val resolved: StateFlow<Set<String>> = _resolved.asStateFlow()

    internal fun set(sessionId: String, requests: List<PendingRequest>) =
        _bySession.update { if (requests.isEmpty()) it - sessionId else it + (sessionId to requests) }

    /** The Agent Chat of [sessionId] closed: its list no longer speaks for the Session. */
    internal fun release(sessionId: String) = _bySession.update { it - sessionId }

    internal fun resolve(id: String) = _resolved.update { it + id }
}

/**
 * What a Session's agent waits on the user for, folded from its log events in seq order.
 *
 * A permission request is live only (never in the durable log), so a `subscribe` or snapshot
 * reply's `pendingPermissions` is the authoritative list: [installPermissions] replaces the
 * permissions held and settles every `permission_request` at or below that reply's head.
 *
 * The host sends no "answered" event, so a request leaves when the signals it does send say so:
 * - a question on `question_cancelled`;
 * - a permission request once its tool call reports a status past `pending` (the agent runs it
 *   after an allow and fails it after a deny);
 * - everything on `prompt_complete` (the turn's requests are released with it) or `session_closed`.
 */
class PendingFold {
    private val pending = LinkedHashMap<String, PendingRequest>()
    /** `permission_request`s at or below this seq are settled by the last `pendingPermissions` list. */
    private var permissionsSettledAt = -1L

    /** Oldest first. */
    fun pending(): List<PendingRequest> = pending.values.toList()

    /** Installs a reply's `pendingPermissions`, taken at [headSeq]. */
    fun installPermissions(permissions: List<PendingRequest.Permission>, headSeq: Long) {
        pending.values.removeAll { it is PendingRequest.Permission }
        permissions.forEach { pending[it.id] = it }
        permissionsSettledAt = headSeq
    }

    fun apply(event: ChatFrame.Event) {
        val p = event.payload
        when (event.type) {
            "permission_request" -> if (event.seq > permissionsSettledAt) {
                parsePermissionRequest(event.sid.orEmpty(), p)?.let { pending[it.id] = it }
            }
            "question_request" -> parseQuestionRequest(event.sid.orEmpty(), p)?.let { pending[it.id] = it }
            "question_cancelled" -> p.string("questionId")?.let { pending.remove(it) }
            "tool_call_update" -> (p["update"] as? JsonObject)?.let { update ->
                val toolCallId = update.string("toolCallId") ?: return@let
                val status = update.string("status") ?: return@let
                if (status != "pending") pending.values.removeAll { it is PendingRequest.Permission && it.toolCallId == toolCallId }
            }
            "prompt_complete", "session_closed" -> pending.clear()
        }
    }
}

/** A `permission_request` payload (or a `pendingPermissions` entry); null when it is not one. */
internal fun parsePermissionRequest(sessionId: String, p: JsonObject): PendingRequest.Permission? {
    val requestId = p.string("requestId") ?: return null
    val options = (p["options"] as? JsonArray ?: return null).mapNotNull { option ->
        val o = option as? JsonObject ?: return@mapNotNull null
        PermissionOption(o.string("optionId") ?: return@mapNotNull null, o.string("name").orEmpty(), o.string("kind"))
    }
    val toolCall = p["toolCall"] as? JsonObject
    return PendingRequest.Permission(
        sessionId = p.string("sessionId") ?: sessionId,
        agentId = p.string("agentId"),
        id = requestId,
        title = toolCall?.string("title").orEmpty(),
        options = options,
        toolCallId = toolCall?.string("toolCallId"),
    )
}

/** A `question_request` payload; null when it is not one. */
internal fun parseQuestionRequest(sessionId: String, p: JsonObject): PendingRequest.Question? {
    val questionId = p.string("questionId") ?: return null
    val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { option ->
        val o = option as? JsonObject ?: return@mapNotNull null
        QuestionOption(o.string("value") ?: return@mapNotNull null, o.string("label").orEmpty(), o.string("description"))
    }
    return PendingRequest.Question(
        sessionId = p.string("sessionId") ?: sessionId,
        agentId = p.string("agentId"),
        id = questionId,
        question = p.string("question").orEmpty(),
        options = options,
        allowFreeText = (p["allowFreeText"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A `subscribe` or `recover_session_snapshot` reply's `pendingPermissions`, or null when the host
 * sent no list (it keeps no approval tickets). Malformed entries are skipped.
 */
internal fun parsePendingPermissions(sessionId: String, reply: JsonElement?): List<PendingRequest.Permission>? {
    val list = ((reply as? JsonObject)?.get("pendingPermissions") as? JsonArray) ?: return null
    return list.mapNotNull { entry -> (entry as? JsonObject)?.let { parsePermissionRequest(sessionId, it) } }
}
