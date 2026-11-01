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

    /** A permission request or question waiting on the user; only a placeholder until they can be answered here. */
    data class Request(override val key: String, val kind: RequestKind, val text: String) : ChatItem
}

enum class RequestKind { Permission, Question }

data class PlanEntry(val content: String, val status: String?)

/**
 * Folds session log events into a transcript, in seq order. Unknown events are ignored.
 * Subagent text (`parentToolCallId`) and the user's echoed chunks stay out: `user_prompt` is the
 * authoritative record of what the user sent.
 */
class Transcript {
    private val items = mutableListOf<ChatItem>()
    private val toolIndex = HashMap<String, Int>()
    var plan: List<PlanEntry> = emptyList()
        private set

    fun items(): List<ChatItem> = items.toList()

    fun apply(event: ChatFrame.Event) {
        val p = event.payload
        when (event.type) {
            "user_prompt" -> items += ChatItem.UserPrompt(
                "u-${event.seq}",
                (p["content"] as? JsonArray).orEmpty().mapNotNull { it.contentText() }.joinToString(""),
            )
            "message_chunk" -> chunk(event.seq, p)
            "tool_call" -> (p["toolCall"] as? JsonObject)?.let(::tool)
            "tool_call_update" -> (p["update"] as? JsonObject)?.let(::tool)
            "plan_update" -> plan = ((p["plan"] as? JsonObject)?.get("entries") as? JsonArray).orEmpty().mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                PlanEntry(e.string("content") ?: return@mapNotNull null, e.string("status"))
            }
            "prompt_complete" -> items += ChatItem.TurnComplete("c-${event.seq}", p.string("stopReason") ?: "end_turn")
            "permission_request" -> items += ChatItem.Request(
                "r-${event.seq}",
                RequestKind.Permission,
                (p["toolCall"] as? JsonObject)?.string("title").orEmpty(),
            )
            "question_request" -> items += ChatItem.Request("q-${event.seq}", RequestKind.Question, p.string("question").orEmpty())
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
