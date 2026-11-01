package dev.troupe.android.chat.protocol

import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** Client request `{id, type, payload, command_id?}` on `/ws`. Never log one: `authenticate` carries the token. */
@Serializable
class ChatRequest(
    val id: String,
    val type: String,
    val payload: JsonObject,
    @SerialName("command_id") val commandId: String? = null,
)

/** Server frames of `/ws`, mirroring termul's `web-protocol.types.ts`. */
sealed interface ChatFrame {
    /** `{id, ok: true, payload?}` or `{id, ok: false, err: {code, message}}`. */
    data class Reply(
        val id: String,
        val ok: Boolean,
        val payload: JsonElement?,
        val code: String?,
        val message: String?,
    ) : ChatFrame

    /** A session log event `{sid, seq, type, payload}`; `auth_required` is one too, with `seq = 0`. */
    data class Event(val sid: String?, val seq: Long, val type: String, val payload: JsonObject) : ChatFrame

    /** Lossy events of [sid] were dropped for this connection: the client recovers from a snapshot. */
    data class Gap(val sid: String) : ChatFrame

    /** Sent every 10 s; any frame proves the host is alive. */
    data object Heartbeat : ChatFrame

    /** A state watch frame: the full [state] (n = 1, [reset]) or one [delta]. */
    data class Watch(
        val watch: String,
        val scope: String,
        val hostEpoch: String,
        val n: Long,
        val reset: Boolean,
        val state: JsonElement?,
        val delta: JsonElement?,
    ) : ChatFrame

    /** Anything this app does not understand yet. Never a crash. */
    data class Unknown(val raw: String) : ChatFrame
}

/** Parses one server frame; never throws. */
fun parseChatFrame(text: String): ChatFrame = runCatching { parse(text) }.getOrNull() ?: ChatFrame.Unknown(text)

private fun parse(text: String): ChatFrame? {
    val obj = TroupeJson.parseToJsonElement(text).jsonObject
    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
    val id = obj["id"]?.jsonPrimitive?.contentOrNull
    if (id != null && ok != null) {
        val err = obj["err"] as? JsonObject
        return ChatFrame.Reply(
            id = id,
            ok = ok,
            payload = obj["payload"],
            code = err?.get("code")?.jsonPrimitive?.contentOrNull,
            message = err?.get("message")?.jsonPrimitive?.contentOrNull,
        )
    }
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return when {
        type == "heartbeat" -> ChatFrame.Heartbeat
        type == "gap" -> ChatFrame.Gap(obj.getValue("sid").jsonPrimitive.content)
        type == "watch" -> {
            val revision = obj.getValue("revision").jsonObject
            ChatFrame.Watch(
                watch = obj.getValue("watch").jsonPrimitive.content,
                scope = obj["scope"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hostEpoch = revision.getValue("hostEpoch").jsonPrimitive.content,
                n = revision.getValue("n").jsonPrimitive.long,
                reset = obj["reset"]?.jsonPrimitive?.booleanOrNull ?: false,
                state = obj["state"],
                delta = obj["delta"],
            )
        }
        "seq" in obj -> ChatFrame.Event(
            sid = obj["sid"]?.jsonPrimitive?.contentOrNull,
            seq = obj.getValue("seq").jsonPrimitive.long,
            type = type,
            payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        else -> null
    }
}

/** Parses a recorded session event (as in a snapshot's `events`); null when it is not one. */
fun parseChatEvent(element: JsonElement): ChatFrame.Event? = runCatching {
    val obj = element.jsonObject
    ChatFrame.Event(
        sid = obj["sid"]?.jsonPrimitive?.contentOrNull,
        seq = obj.getValue("seq").jsonPrimitive.long,
        type = obj.getValue("type").jsonPrimitive.content,
        payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
    )
}.getOrNull()

/** `err.code`s of `/ws` (termul's `WS_ERROR_CODES`) that this app acts on. */
object ChatErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val PROTOCOL_VERSION_MISMATCH = "protocol_version_mismatch"
    const val STALE = "stale"
    const val NOT_FOUND = "not_found"
    const val UNSUPPORTED = "unsupported"
    const val FORBIDDEN = "forbidden"
    const val RATE_LIMITED = "rate_limited"
    const val ALREADY_RESOLVED = "already_resolved"
}

/** One entry of the persisted-session index (termul's `PersistedSessionSummary`). */
@Serializable
class SessionSummary(
    val sessionId: String,
    val projectId: String? = null,
    val title: String? = null,
    val lastActivityAt: Long = 0,
)

/** The `subscribe` reply: events with `seq <= headSeq` are replay, anything above is live. */
@Serializable
class SubscribeReply(val sessionId: String, val logEpoch: String = "", val headSeq: Long = 0)

/** The `recover_session_snapshot` reply: the session's durable [events], and the cursor to continue from. */
class SessionSnapshot(val logEpoch: String, val watermark: Long, val events: List<ChatFrame.Event>)

fun parseSnapshot(payload: JsonElement?): SessionSnapshot? = runCatching {
    val obj = payload!!.jsonObject
    SessionSnapshot(
        logEpoch = obj["logEpoch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        watermark = obj["watermark"]?.jsonPrimitive?.longOrNull ?: 0,
        events = obj["events"]?.jsonArray.orEmpty().mapNotNull(::parseChatEvent),
    )
}.getOrNull()

/** The text of an ACP content block, or null for one that is not text. */
internal fun JsonElement.contentText(): String? =
    (this as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
