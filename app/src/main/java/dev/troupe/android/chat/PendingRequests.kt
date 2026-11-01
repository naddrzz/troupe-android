package dev.troupe.android.chat

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
 * The requests waiting on the user on one Paired Server, by Session id: kept by the server's chat
 * connection, so every screen (the Attention list too) sees the same list. An open Agent Chat
 * updates its Session's entry as events and answers arrive.
 */
class PendingRequests {
    private val _bySession = MutableStateFlow<Map<String, List<PendingRequest>>>(emptyMap())
    /** Every Session with something pending, in the order its requests arrived. */
    val bySession: StateFlow<Map<String, List<PendingRequest>>> = _bySession.asStateFlow()

    internal fun set(sessionId: String, requests: List<PendingRequest>) =
        _bySession.update { if (requests.isEmpty()) it - sessionId else it + (sessionId to requests) }
}

/** A `permission_request` payload (or a `pendingPermissions` entry); null when it is not one. */
internal fun parsePermissionRequest(sessionId: String, p: JsonObject): PendingRequest.Permission? {
    val requestId = p.string("requestId") ?: return null
    val options = (p["options"] as? JsonArray ?: return null).mapNotNull { option ->
        val o = option as? JsonObject ?: return@mapNotNull null
        PermissionOption(o.string("optionId") ?: return@mapNotNull null, o.string("name").orEmpty(), o.string("kind"))
    }
    val title = (p["toolCall"] as? JsonObject)?.string("title").orEmpty()
    return PendingRequest.Permission(p.string("sessionId") ?: sessionId, p.string("agentId"), requestId, title, options)
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
