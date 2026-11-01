package dev.troupe.android.chat

import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.contentText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** One row of an Agent Chat's transcript. [key] is stable across a snapshot reinstall. */
sealed interface ChatItem {
    val key: String

    data class UserPrompt(override val key: String, val text: String) : ChatItem

    /** Agent text, streamed chunk by chunk; [thought] is its reasoning rather than its reply. */
    data class AgentMessage(override val key: String, val messageId: String?, val text: String, val thought: Boolean) : ChatItem

    /** A tool call, shown compactly. [status] is ACP's `pending`, `in_progress`, `completed` or `failed`. */
    data class ToolCall(override val key: String, val title: String, val kind: String?, val status: String?) : ChatItem

    /** The turn ended, with an ACP stop reason (`end_turn`, `cancelled`, …) or a host one (`error`, `timeout`, `crashed`). */
    data class TurnComplete(override val key: String, val stopReason: String) : ChatItem
}

data class PlanEntry(val content: String, val status: String?)

/**
 * Folds session log events into a transcript, in seq order. Unknown events are ignored.
 * Subagent text (`parentToolCallId`) and the user's echoed chunks stay out: `user_prompt` is the
 * authoritative record of what the user sent.
 *
 * Permission requests and questions are kept apart, in [pending], until they are answered, a
 * question is cancelled or the turn ends. A permission request is live only (never in the durable
 * log), so a `subscribe` or snapshot reply's `pendingPermissions` is the authoritative list: it
 * replaces the permissions held and settles every `permission_request` at or below its head.
 */
class Transcript {
    private val items = mutableListOf<ChatItem>()
    private val toolIndex = HashMap<String, Int>()
    var plan: List<PlanEntry> = emptyList()
        private set
    /** The agent serving this Session, as its latest event names it; prompts and cancels go to it. */
    var agentId: String? = null
        private set
    /** A prompt was accepted and its turn has not ended yet. */
    var turnRunning = false
        private set

    private val pending = LinkedHashMap<String, PendingRequest>()
    /** `permission_request`s at or below this seq are settled by the last `pendingPermissions` list. */
    private var permissionsSettledAt = -1L

    fun items(): List<ChatItem> = items.toList()

    /** What the agent waits on the user for, oldest first. */
    fun pending(): List<PendingRequest> = pending.values.toList()

    /** Installs a reply's `pendingPermissions`, taken at [headSeq]. */
    fun installPermissions(permissions: List<PendingRequest.Permission>, headSeq: Long) {
        pending.values.removeAll { it is PendingRequest.Permission }
        permissions.forEach { pending[it.id] = it }
        permissionsSettledAt = headSeq
    }

    fun apply(event: ChatFrame.Event) {
        val p = event.payload
        p.string("agentId")?.let { agentId = it }
        when (event.type) {
            "user_prompt" -> {
                turnRunning = true
                items += ChatItem.UserPrompt(
                    "u-${event.seq}",
                    (p["content"] as? JsonArray).orEmpty().mapNotNull { it.contentText() }.joinToString(""),
                )
            }
            "message_chunk" -> chunk(event.seq, p)
            "tool_call" -> (p["toolCall"] as? JsonObject)?.let(::tool)
            "tool_call_update" -> (p["update"] as? JsonObject)?.let(::tool)
            "plan_update" -> plan = ((p["plan"] as? JsonObject)?.get("entries") as? JsonArray).orEmpty().mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                PlanEntry(e.string("content") ?: return@mapNotNull null, e.string("status"))
            }
            "prompt_complete" -> {
                turnRunning = false
                pending.clear()
                items += ChatItem.TurnComplete("c-${event.seq}", p.string("stopReason") ?: "end_turn")
            }
            "permission_request" -> if (event.seq > permissionsSettledAt) {
                parsePermissionRequest(event.sid.orEmpty(), p)?.let { pending[it.id] = it }
            }
            "question_request" -> parseQuestionRequest(event.sid.orEmpty(), p)?.let { pending[it.id] = it }
            "question_cancelled" -> p.string("questionId")?.let { pending.remove(it) }
        }
    }

    private fun chunk(seq: Long, p: JsonObject) {
        if (p["parentToolCallId"] != null) return
        val thought = when (p.string("role")) {
            "agent" -> false
            "thought" -> true
            else -> return
        }
        val text = p["content"]?.contentText() ?: return
        val messageId = p.string("messageId")
        val last = items.lastOrNull()
        if (last is ChatItem.AgentMessage && last.thought == thought && (messageId == null || last.messageId == messageId)) {
            items[items.lastIndex] = last.copy(text = last.text + text)
        } else {
            items += ChatItem.AgentMessage("m-$seq", messageId, text, thought)
        }
    }

    private fun tool(call: JsonObject) {
        val id = call.string("toolCallId") ?: return
        val index = toolIndex[id]
        if (index == null) {
            toolIndex[id] = items.size
            items += ChatItem.ToolCall("t-$id", call.string("title").orEmpty(), call.string("kind"), call.string("status"))
        } else {
            val old = items[index] as ChatItem.ToolCall
            items[index] = old.copy(
                title = call.string("title") ?: old.title,
                kind = call.string("kind") ?: old.kind,
                status = call.string("status") ?: old.status,
            )
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
