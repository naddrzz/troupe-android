package dev.troupe.android.attention

import dev.troupe.android.chat.PendingFold
import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.parsePendingPermissions
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.chat.protocol.SubscribeReply
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * What one Paired Server's Attention is made of: the persisted-session [index], the [projects] by
 * id, and the requests pending in each live Session ([tracked]).
 */
internal data class MonitorState(
    val ready: Boolean = false,
    val index: List<SessionSummary> = emptyList(),
    val projects: Map<String, String> = emptyMap(),
    val tracked: Map<String, List<PendingRequest>> = emptyMap(),
)

/**
 * Watches one Paired Server's `/ws` for Attention, Agent Chats open or not. termul has no request
 * that lists pending requests across Sessions, so this is the cheapest correct source:
 *
 * - The `chat_history` watch gives the index (Project, title, `status`, `turnActive`). Its deltas
 *   fire when a Session is created or closed, so its live set stays current.
 * - Each live Session (`status: active`, or a turn open) is subscribed, once per connection. A
 *   Session with no open turn subscribes live-only (no replay); one with an open turn replays its
 *   log once, so a question asked before the app connected is seen. Later subscribes resume from
 *   the cursor `(logEpoch, lastSeq)`. Every reply's `pendingPermissions` is authoritative.
 * - Events fold through [PendingFold]. A `gap` subscribes live-only again for a fresh
 *   `pendingPermissions`, since a dropped `tool_call_update` may have been the only sign of an answer.
 *
 * The subscription is shared with an open Agent Chat on the same socket (the host keeps one per
 * Session and connection): events seen twice are skipped by seq.
 */
internal class AttentionMonitor(
    private val connection: ChatConnection,
    private val projectNames: suspend () -> Map<String, String>?,
) {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private class Tracked {
        val fold = PendingFold()
        var logEpoch = ""
        var lastSeq = 0L
        var subscribed = false
    }

    private sealed interface Input {
        data class Frame(val frame: ChatFrame) : Input
        data object Connected : Input
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val sessions = HashMap<String, Tracked>()
    /** `(hostEpoch, n)` of the last `chat_history` frame applied. */
    private var revision: Pair<String, Long>? = null

    suspend fun run(): Unit = coroutineScope {
        // Collect before watching: the host queues the state frame ahead of the watch reply.
        launch(start = CoroutineStart.UNDISPATCHED) {
            connection.socket.frames.collect { frame ->
                if (frame is ChatFrame.Event || frame is ChatFrame.Gap || (frame is ChatFrame.Watch && frame.watch == CHAT_HISTORY)) {
                    inbox.send(Input.Frame(frame))
                }
            }
        }
        launch { connection.generation.collect { if (it > 0) inbox.send(Input.Connected) } }
        for (input in inbox) {
            when (input) {
                Input.Connected -> {
                    sessions.values.forEach { it.subscribed = false }
                    projectNames()?.let { names -> _state.update { it.copy(projects = names) } }
                    watch()
                }
                is Input.Frame -> when (val frame = input.frame) {
                    is ChatFrame.Watch -> onWatchFrame(frame)
                    is ChatFrame.Event -> onEvent(frame)
                    is ChatFrame.Gap -> sessions[frame.sid]?.let { refresh(frame.sid, it) }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun watch() {
        revision = null
        request("watch", buildJsonObject { put("watch", CHAT_HISTORY) })
    }

    private suspend fun onWatchFrame(frame: ChatFrame.Watch) {
        val last = revision
        when {
            frame.reset -> {
                revision = frame.hostEpoch to frame.n
                install(frame.state)
            }
            last == null || last.first != frame.hostEpoch || frame.n != last.second + 1 ||
                (frame.delta as? JsonObject)?.containsKey("resync") == true -> watch()
            else -> {
                revision = frame.hostEpoch to frame.n
                val reply = request("list_persisted_sessions", JsonObject(emptyMap())) ?: return
                when {
                    reply.ok -> install(reply.payload)
                    reply.code == ChatErrorCodes.UNSUPPORTED -> install(null)
                }
            }
        }
    }

    /** Installs the index, then subscribes each live Session not subscribed on this connection yet. */
    private suspend fun install(element: JsonElement?) {
        val index = element?.let {
            runCatching { TroupeJson.decodeFromJsonElement(ListSerializer(SessionSummary.serializer()), it) }.getOrNull()
        }.orEmpty()
        val live = index.filter { it.status == STATUS_ACTIVE || it.turnActive == true }
        sessions.keys.retainAll(live.map { it.sessionId }.toSet())
        _state.update { it.copy(index = index) }
        publish()
        for (session in live) {
            if (sessions[session.sessionId]?.subscribed != true) subscribe(session)
        }
        _state.update { it.copy(ready = true) }
    }

    private suspend fun subscribe(session: SessionSummary) {
        val sid = session.sessionId
        val tracked = sessions.getOrPut(sid) { Tracked() }
        val reply = request("subscribe", buildJsonObject {
            put("sessionId", sid)
            when {
                tracked.logEpoch.isNotEmpty() -> {
                    put("lastSeq", tracked.lastSeq)
                    put("logEpoch", tracked.logEpoch)
                }
                // An open turn may hold a question asked before this app connected: replay the log once.
                session.turnActive == true -> put("lastSeq", 0)
                // Otherwise live-only: nothing is pending in a Session without an open turn.
            }
        }) ?: return
        if (!reply.ok) {
            if (reply.code == ChatErrorCodes.STALE) {
                // Another log incarnation: start over from its head.
                sessions[sid] = Tracked()
                refresh(sid, sessions.getValue(sid))
            } else {
                sessions.remove(sid)
            }
            return publish()
        }
        accept(sid, tracked, reply.payload, liveOnly = tracked.logEpoch.isEmpty() && session.turnActive != true)
    }

    /** A live-only subscribe: the host's current `pendingPermissions`, and the cursor moves to its head. */
    private suspend fun refresh(sid: String, tracked: Tracked) {
        val reply = request("subscribe", buildJsonObject { put("sessionId", sid) }) ?: return
        if (reply.ok) accept(sid, tracked, reply.payload, liveOnly = true)
    }

    private fun accept(sid: String, tracked: Tracked, payload: JsonElement?, liveOnly: Boolean) {
        val subscribed = payload?.let { runCatching { TroupeJson.decodeFromJsonElement(SubscribeReply.serializer(), it) }.getOrNull() } ?: return
        tracked.logEpoch = subscribed.logEpoch
        if (liveOnly) tracked.lastSeq = maxOf(tracked.lastSeq, subscribed.headSeq)
        parsePendingPermissions(sid, payload)?.let { tracked.fold.installPermissions(it, subscribed.headSeq) }
        tracked.subscribed = true
        publish()
    }

    private fun onEvent(event: ChatFrame.Event) {
        val tracked = sessions[event.sid] ?: return
        if (event.seq <= tracked.lastSeq) return
        tracked.fold.apply(event)
        tracked.lastSeq = event.seq
        publish()
    }

    private fun publish() {
        val tracked = sessions.mapValues { it.value.fold.pending() }.filterValues { it.isNotEmpty() }
        _state.update { it.copy(tracked = tracked) }
    }

    private suspend fun request(type: String, payload: JsonObject): ChatFrame.Reply? = try {
        connection.socket.request(type, payload)
    } catch (e: IOException) {
        // The connection dropped; the next one watches and subscribes again.
        null
    }

    private companion object {
        const val CHAT_HISTORY = "chat_history"
    }
}
