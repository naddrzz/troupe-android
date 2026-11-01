package dev.troupe.android.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fake Troupe Server's `/ws`, the way termul-server speaks it (`web-protocol.types.ts`,
 * `ws/subscribe.rs`, `ws/sessions.rs`, `ws/watch.rs`): `auth_required`, `authenticate`, the
 * `chat_history` watch and `list_persisted_sessions`, session logs with `subscribe` by
 * `(logEpoch, lastSeq)` and `recover_session_snapshot`, plus `gap` and `heartbeat` frames.
 */
class FakeChatServer internal constructor(
    private val token: () -> String,
    private val protocolVersion: () -> Int,
) {
    /** One session log: its epoch and every event it holds, in seq order. */
    class Session(@Volatile var logEpoch: String) {
        val events: MutableList<JsonObject> = CopyOnWriteArrayList()
        val head: Long get() = events.size.toLong()
    }

    /** The persisted-session index: the `chat_history` watch state and the `list_persisted_sessions` reply. */
    @Volatile var history: List<JsonObject> = emptyList()
    /** The host's serve-start epoch, carried by every watch revision. */
    @Volatile var hostEpoch: String = "host-1"
    /** true: a subscribe's replay goes out before its reply, the events-overtake-the-reply case. */
    @Volatile var replayBeforeReply: Boolean = true
    val sessions: MutableMap<String, Session> = ConcurrentHashMap()
    /** Every request frame the app sent on `/ws`, parsed. */
    val requests: MutableList<JsonObject> = CopyOnWriteArrayList()
    val upgrades = AtomicInteger()

    private val lock = Any()
    private val connections: MutableList<Connection> = CopyOnWriteArrayList()

    fun requests(type: String): List<JsonObject> = requests.filter { it["type"]?.jsonPrimitive?.content == type }

    /** Adds a session log for [sessionId] and returns it. */
    fun session(sessionId: String, logEpoch: String = "ep-1"): Session = Session(logEpoch).also { sessions[sessionId] = it }

    /**
     * Appends an event to [sessionId]'s log under the next seq and sends it live to each subscribed
     * connection; [deliver] = false loses it on the way, as a lossy event dropped for a slow peer.
     */
    fun append(sessionId: String, type: String, payload: JsonObject, deliver: Boolean = true): Long = synchronized(lock) {
        val session = sessions.getValue(sessionId)
        val event = event(sessionId, session.head + 1, type, payload)
        session.events += event
        if (deliver) connections.filter { sessionId in it.subscribed }.forEach { it.send(event) }
        session.head
    }

    /** `{type: "gap", sid}`: lossy events of [sessionId] were dropped for every subscribed connection. */
    fun gap(sessionId: String) = synchronized(lock) {
        connections.filter { sessionId in it.subscribed }.forEach { it.send(buildJsonObject { put("type", "gap"); put("sid", sessionId) }) }
    }

    fun heartbeat() = connections.forEach { it.send(buildJsonObject { put("type", "heartbeat") }) }

    /** The index changed for [changed]: a `{changed}` delta to every watcher. [lost]: the delta is dropped but keeps its `n`. */
    fun historyChanged(vararg changed: String, lost: Boolean = false) = synchronized(lock) {
        connections.filter { it.watchN > 0 }.forEach { connection ->
            connection.watchN++
            if (!lost) {
                connection.send(watchFrame(connection.watchN, delta = buildJsonObject {
                    put("changed", JsonArray(changed.map { JsonPrimitive(it) }))
                }))
            }
        }
    }

    /** Closes every authenticated connection with [code]: 1001 is a deliberate stop, 1011 a crash. */
    fun closeAll(code: Int) = connections.toList().forEach {
        connections -= it
        it.socket.close(code, null)
    }

    internal fun listener(): WebSocketListener {
        upgrades.incrementAndGet()
        return Connection()
    }

    private fun watchFrame(n: Int, state: JsonElement? = null, delta: JsonElement? = null) = buildJsonObject {
        put("type", "watch")
        put("watch", "chat_history")
        put("scope", "")
        put("revision", buildJsonObject {
            put("hostEpoch", hostEpoch)
            put("n", n)
        })
        if (state != null) {
            put("reset", true)
            put("state", state)
        }
        if (delta != null) put("delta", delta)
    }

    private inner class Connection : WebSocketListener() {
        lateinit var socket: WebSocket
        private var authed = false
        val subscribed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var watchN = 0

        fun send(frame: JsonObject) {
            socket.send(frame.toString())
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            send(buildJsonObject {
                put("sid", JsonNull)
                put("seq", 0)
                put("type", "auth_required")
                put("payload", JsonObject(emptyMap()))
            })
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            connections -= this
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connections -= this
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            requests += request
            val id = request.getValue("id").jsonPrimitive.content
            val type = request.getValue("type").jsonPrimitive.content
            val payload = request["payload"] as? JsonObject ?: JsonObject(emptyMap())
            when {
                type == "authenticate" -> reply(id, authenticate(payload))
                !authed -> reply(id, Err("unauthorized", "Unauthorized"))
                type == "watch" -> watch(id, payload)
                type == "list_persisted_sessions" -> reply(id, JsonArray(history))
                type == "subscribe" -> subscribe(id, payload)
                type == "recover_session_snapshot" -> snapshot(id, payload)
                else -> reply(id, Err("unsupported", "unknown request `$type`"))
            }
        }

        private fun authenticate(payload: JsonObject): Any {
            if (authed) return JsonObject(emptyMap())
            if (payload["protocolVersion"]?.jsonPrimitive?.intOrNull != protocolVersion()) {
                return Err("protocol_version_mismatch", "protocol version mismatch: this host speaks version ${protocolVersion()}")
            }
            for (field in listOf("deviceId", "clientInstanceId")) {
                if (payload[field]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) return Err("invalid_client_identity", "$field is required")
            }
            if (payload["token"]?.jsonPrimitive?.contentOrNull != token()) return Err("unauthorized", "Unauthorized")
            authed = true
            connections += this
            return buildJsonObject {
                put("historyMode", "server")
                put("protocolVersion", protocolVersion())
            }
        }

        private fun watch(id: String, payload: JsonObject) = synchronized(lock) {
            if (payload["watch"]?.jsonPrimitive?.contentOrNull != "chat_history") return@synchronized reply(id, Err("unsupported", "unknown watch"))
            watchN = 1
            send(watchFrame(1, state = JsonArray(history)))
            reply(id, buildJsonObject {
                put("watch", "chat_history")
                put("scope", "")
                put("revision", buildJsonObject { put("hostEpoch", hostEpoch); put("n", 1) })
            })
        }

        private fun subscribe(id: String, payload: JsonObject) = synchronized(lock) {
            val sid = payload["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val session = sessions[sid] ?: return@synchronized reply(id, Err("not_found", "session not found"))
            val lastSeq = payload["lastSeq"]?.jsonPrimitive?.longOrNull
            val epoch = payload["logEpoch"]?.jsonPrimitive?.contentOrNull
            if (lastSeq != null && ((epoch != null && epoch != session.logEpoch) || lastSeq > session.head)) {
                return@synchronized reply(id, Err("stale", "cursor does not belong to the event log; request an atomic session snapshot"))
            }
            val replay = if (lastSeq == null) emptyList() else session.events.drop(lastSeq.toInt())
            subscribed += sid
            val answer = buildJsonObject {
                put("sessionId", sid)
                put("replayed", replay.size)
                put("logEpoch", session.logEpoch)
                put("headSeq", session.head)
            }
            if (replayBeforeReply) {
                replay.forEach(::send)
                reply(id, answer)
            } else {
                reply(id, answer)
                replay.forEach(::send)
            }
        }

        private fun snapshot(id: String, payload: JsonObject) = synchronized(lock) {
            val sid = payload["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val session = sessions[sid] ?: return@synchronized reply(id, Err("not_found", "session snapshot not found"))
            subscribed += sid
            reply(id, buildJsonObject {
                put("sessionId", sid)
                put("watermark", session.head)
                put("headSeq", session.head)
                put("logEpoch", session.logEpoch)
                put("events", JsonArray(session.events.toList()))
            })
        }

        private fun reply(id: String, result: Any) = send(buildJsonObject {
            put("id", id)
            when (result) {
                is Err -> {
                    put("ok", false)
                    put("err", buildJsonObject {
                        put("code", result.code)
                        put("message", result.message)
                    })
                }
                else -> {
                    put("ok", true)
                    put("payload", result as JsonElement)
                }
            }
        })
    }

    private data class Err(val code: String, val message: String)

    companion object {
        fun event(sessionId: String, seq: Long, type: String, payload: JsonObject) = buildJsonObject {
            put("sid", sessionId)
            put("seq", seq)
            put("type", type)
            put("payload", payload)
        }

        /** One `PersistedSessionSummary` of the index; [lastActivityAt] is epoch ms. */
        fun summary(sessionId: String, projectId: String?, title: String?, lastActivityAt: Long) = buildJsonObject {
            put("storageKey", "claude/$sessionId")
            put("sessionId", sessionId)
            put("stableAgentNamespace", "claude")
            if (projectId != null) put("projectId", projectId)
            put("cwd", "/workspace")
            put("title", title)
            put("createdAt", lastActivityAt - 60_000)
            put("lastActivityAt", lastActivityAt)
            put("status", "closed")
            put("messageCount", 2)
            put("toolCount", 1)
            put("lastSeq", 7)
            put("discovered", false)
            put("resumeEligible", true)
            put("logEpoch", "ep-1")
        }

        private fun text(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        fun userPrompt(sessionId: String, text: String, turnId: String = "turn-1") = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("turnId", turnId)
            put("content", buildJsonArray { add(text(text)) })
        }

        fun messageChunk(sessionId: String, text: String, role: String = "agent", messageId: String? = "msg-1") = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("role", role)
            put("content", text(text))
            if (messageId != null) put("messageId", messageId)
        }

        fun toolCall(sessionId: String, toolCallId: String, title: String, status: String = "pending") = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("toolCall", buildJsonObject {
                put("toolCallId", toolCallId)
                put("title", title)
                put("kind", "read")
                put("status", status)
            })
        }

        fun toolCallUpdate(sessionId: String, toolCallId: String, status: String) = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("update", buildJsonObject {
                put("toolCallId", toolCallId)
                put("status", status)
            })
        }

        fun planUpdate(sessionId: String, vararg entries: Pair<String, String>) = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("plan", buildJsonObject {
                put("entries", buildJsonArray {
                    entries.forEach { (content, status) ->
                        add(buildJsonObject {
                            put("content", content)
                            put("priority", "medium")
                            put("status", status)
                        })
                    }
                })
            })
        }

        fun promptComplete(sessionId: String, stopReason: String = "end_turn", turnId: String = "turn-1") = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("stopReason", stopReason)
            put("turnId", turnId)
        }

        fun permissionRequest(sessionId: String, requestId: String, title: String) = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("requestId", requestId)
            put("toolCall", buildJsonObject {
                put("toolCallId", "tc-$requestId")
                put("title", title)
            })
            put("options", buildJsonArray {
                add(buildJsonObject { put("optionId", "allow"); put("name", "Allow"); put("kind", "allow_once") })
                add(buildJsonObject { put("optionId", "deny"); put("name", "Deny"); put("kind", "reject_once") })
            })
        }

        fun questionRequest(sessionId: String, questionId: String, question: String) = buildJsonObject {
            put("agentId", "agent-1")
            put("sessionId", sessionId)
            put("questionId", questionId)
            put("question", question)
            put("options", JsonArray(emptyList()))
        }
    }
}
