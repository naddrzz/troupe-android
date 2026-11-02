package dev.troupe.android.attention

import dev.troupe.android.chat.PendingFold
import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.parsePendingPermissions
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.chat.protocol.SubscribeReply
import dev.troupe.android.chat.protocol.parseSnapshot
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * - Events fold through [PendingFold]. A `gap`, or a `stale` cursor, rebuilds the fold from
 *   `recover_session_snapshot`: a dropped `question_cancelled` or `tool_call_update` may have been
 *   the only sign of an answer, and a question asked before a stale cursor is still in the log.
 * - A connection that fails for good (a revoked token) takes the server's Attention with it.
 *
 * The subscription is shared with an open Agent Chat on the same socket (the host keeps one per
 * Session and connection): events seen twice are skipped by seq.
 *
 * [finished] names each Session whose turn ended (`prompt_complete`) after the app first
 * subscribed it: live, or missed while disconnected and replayed on resume.
 */
internal class AttentionMonitor(
    private val connection: ChatConnection,
    private val projectNames: suspend () -> Map<String, String>?,
) {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private class Tracked {
        var fold = PendingFold()
        var logEpoch = ""
        var lastSeq = 0L
        var subscribed = false
        /** Seqs up to here were in the log before the app first subscribed: history, not news. */
        var historyUpTo = Long.MAX_VALUE
    }

    private sealed interface Input {
        data class Frame(val frame: ChatFrame) : Input
        data object Connected : Input
        data object Failed : Input
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
        launch { connection.state.collect { if (it is ConnectionState.Failed) inbox.send(Input.Failed) } }
        for (input in inbox) {
            when (input) {
                Input.Connected -> {
                    sessions.values.forEach { it.subscribed = false }
                    projectNames()?.let { names -> _state.update { it.copy(projects = names) } }
                    watch()
                }
                Input.Failed -> {
                    sessions.clear()
                    revision = null
                    _state.value = MonitorState()
                }
                is Input.Frame -> when (val frame = input.frame) {
                    is ChatFrame.Watch -> onWatchFrame(frame)
                    is ChatFrame.Event -> onEvent(frame)
                    is ChatFrame.Gap -> sessions[frame.sid]?.let { recover(frame.sid, it) }
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
                // Another log incarnation: start over from its snapshot.
                sessions[sid] = Tracked()
                recover(sid, sessions.getValue(sid))
            } else {
                sessions.remove(sid)
            }
            return publish()
        }
        accept(sid, tracked, reply.payload, liveOnly = tracked.logEpoch.isEmpty() && session.turnActive != true)
    }

    /**
     * Rebuilds [tracked] from `recover_session_snapshot`, which also subscribes: the fold of its
     * events, its `pendingPermissions` (or the permissions held, when it sends none), and the cursor
     * at its watermark. What the snapshot holds is not news: no finished turn is reported from it.
     */
    private suspend fun recover(sid: String, tracked: Tracked) {
        val reply = request("recover_session_snapshot", buildJsonObject { put("sessionId", sid) }) ?: return
        val snapshot = parseSnapshot(reply.payload)
        if (!reply.ok || snapshot == null) {
            sessions.remove(sid)
            return publish()
        }
        val permissions = parsePendingPermissions(sid, reply.payload)
            ?: tracked.fold.pending().filterIsInstance<PendingRequest.Permission>()
        tracked.fold = PendingFold().apply {
            snapshot.events.filter { it.sid == null || it.sid == sid }.forEach(::apply)
            installPermissions(permissions, snapshot.watermark)
        }
        if (tracked.logEpoch.isEmpty()) tracked.historyUpTo = snapshot.watermark
        tracked.logEpoch = snapshot.logEpoch
        tracked.lastSeq = snapshot.watermark
        tracked.subscribed = true
        publish()
    }

    private fun accept(sid: String, tracked: Tracked, payload: JsonElement?, liveOnly: Boolean) {
        val subscribed = payload?.let { runCatching { TroupeJson.decodeFromJsonElement(SubscribeReply.serializer(), it) }.getOrNull() } ?: return
        if (tracked.logEpoch.isEmpty()) tracked.historyUpTo = subscribed.headSeq
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
        if (event.type == "prompt_complete" && event.seq > tracked.historyUpTo) _finished.tryEmit(event.sid.orEmpty())
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
