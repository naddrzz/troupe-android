package dev.troupe.android.attention

import dev.troupe.android.chat.PendingFold
import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.parsePendingPermissions
import dev.troupe.android.chat.parsePendingQuestions
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.chat.protocol.SubscribeReply
import dev.troupe.android.chat.protocol.parseSnapshot
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.WatchHub
import dev.troupe.android.core.store.WatchUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Watches one Paired Server's `/ws` for Attention, Agent Chats open or not:
 *
 * - Holds a ref-counted [WatchHub] subscription for `chat_history` so switching servers in the
 *   Workspace Shell never sends `unwatch` behind this monitor's back, while routing BOTH session
 *   events and `chat_history` watch frames through a single `socket.frames` collector so wire
 *   ordering (e.g. `prompt_complete` arriving right before a `chat_history` delta) is strictly
 *   preserved.
 * - Each live Session (`status: active`, or a turn open) is subscribed once per connection
 *   generation. A Session with no open turn subscribes live-only (no replay); one with an open
 *   turn replays its log once, so a question asked before the app connected is seen. Later
 *   subscribes resume from the cursor `(logEpoch, lastSeq)`. Every reply's `pendingPermissions`
 *   (and `pendingQuestions`, when provided by the host) is authoritative.
 * - Events fold through [PendingFold]. A `gap`, or a `stale` cursor, rebuilds the fold from
 *   `recover_session_snapshot`.
 * - A connection that fails for good (a revoked token) takes the server's Attention with it.
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
        var subscribedGeneration = 0
        /** Seqs up to here were in the log before the app first subscribed: history, not news. */
        var historyUpTo = Long.MAX_VALUE
    }

    private sealed interface Input {
        data class Frame(val frame: ChatFrame) : Input
        data class CachedReset(val update: WatchUpdate) : Input
        data class Connected(val generation: Int) : Input
        data object Failed : Input
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val sessions = HashMap<String, Tracked>()
    private var currentGeneration = 0
    /** `(hostEpoch, n)` of the last `chat_history` watch frame applied. */
    private var revision: Pair<String, Long>? = null
    /** Latest `(hostEpoch, n)` delta seen before consuming our initial baseline snapshot. */
    private var latestPreBaselineRevision: Pair<String, Long>? = null

    suspend fun run(): Unit = coroutineScope {
        // Single collector on socket.frames preserves exact WebSocket wire order between session
        // events (e.g. prompt_complete) and chat_history watch frames.
        launch(start = CoroutineStart.UNDISPATCHED) {
            connection.socket.frames.collect { frame ->
                if (frame is ChatFrame.Event || frame is ChatFrame.Gap ||
                    (frame is ChatFrame.Watch && frame.watch == WatchHub.CHAT_HISTORY)
                ) {
                    inbox.send(Input.Frame(frame))
                }
            }
        }
        // Hold a ref-counted lease on WatchHub so WorkspaceSessionManager never sends `unwatch`
        // for chat_history, and pick up any cached reset if WatchHub already subscribed earlier.
        launch(start = CoroutineStart.UNDISPATCHED) {
            connection.watchHub.watch(WatchHub.CHAT_HISTORY).collect { update ->
                if (update.reset) inbox.send(Input.CachedReset(update))
            }
        }
        launch { connection.generation.collect { if (it > 0) inbox.send(Input.Connected(it)) } }
        launch { connection.state.collect { if (it is ConnectionState.Failed) inbox.send(Input.Failed) } }
        for (input in inbox) {
            when (input) {
                is Input.Connected -> {
                    currentGeneration = maxOf(currentGeneration, input.generation)
                    projectNames()?.let { names -> _state.update { it.copy(projects = names) } }
                }
                Input.Failed -> {
                    sessions.clear()
                    revision = null
                    latestPreBaselineRevision = null
                    _state.value = MonitorState()
                }
                is Input.CachedReset -> {
                    // Only apply cached snapshot when we joined after the wire reset already passed.
                    if (revision == null) {
                        val pre = latestPreBaselineRevision
                        latestPreBaselineRevision = null
                        val newerSameEpoch = pre != null &&
                            pre.first == input.update.hostEpoch &&
                            pre.second > input.update.n
                        revision = if (newerSameEpoch) pre else (input.update.hostEpoch to input.update.n)
                        if (_state.value.projects.isEmpty()) {
                            projectNames()?.let { names -> _state.update { it.copy(projects = names) } }
                        }
                        if (newerSameEpoch) {
                            val reply = request("list_persisted_sessions", JsonObject(emptyMap()))
                            when {
                                reply != null && reply.ok -> install(reply.payload)
                                reply?.code == ChatErrorCodes.UNSUPPORTED -> install(null)
                                else -> install(input.update.state)
                            }
                        } else {
                            install(input.update.state)
                        }
                    }
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

    private suspend fun onWatchFrame(frame: ChatFrame.Watch) {
        val gen = maxOf(currentGeneration, connection.generation.value)
        if (gen > 0) currentGeneration = gen
        val last = revision
        when {
            frame.reset -> {
                revision = frame.hostEpoch to frame.n
                latestPreBaselineRevision = null
                if (_state.value.projects.isEmpty()) {
                    projectNames()?.let { names -> _state.update { it.copy(projects = names) } }
                }
                install(frame.state)
            }
            (frame.delta as? JsonObject)?.containsKey("resync") == true -> {
                revision = null
                latestPreBaselineRevision = null
                connection.watchHub.resync(WatchHub.CHAT_HISTORY)
            }
            last == null -> {
                // Baseline reset (from wire or WatchHub cache) has not been consumed yet; avoid
                // triggering a redundant shared unwatch/watch and let CachedReset reconcile if needed.
                latestPreBaselineRevision = frame.hostEpoch to frame.n
            }
            last.first != frame.hostEpoch || frame.n != last.second + 1 -> {
                revision = null
                latestPreBaselineRevision = null
                connection.watchHub.resync(WatchHub.CHAT_HISTORY)
            }
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
        val gen = maxOf(currentGeneration, connection.generation.value)
        for (session in live) {
            if (sessions[session.sessionId]?.subscribedGeneration != gen) subscribe(session, gen)
        }
        _state.update { it.copy(ready = true) }
    }

    private suspend fun subscribe(session: SessionSummary, generation: Int) {
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
        accept(sid, tracked, reply.payload, liveOnly = tracked.logEpoch.isEmpty() && session.turnActive != true, generation = generation)
    }

    /**
     * Rebuilds [tracked] from `recover_session_snapshot`, which also subscribes: the fold of its
     * events, its `pendingPermissions` and `pendingQuestions` (when sent), and the cursor at its
     * watermark. What the snapshot holds is not news: no finished turn is reported from it.
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
            parsePendingQuestions(sid, reply.payload)?.let { installQuestions(it, snapshot.watermark) }
        }
        if (tracked.logEpoch.isEmpty()) tracked.historyUpTo = snapshot.watermark
        tracked.logEpoch = snapshot.logEpoch
        tracked.lastSeq = snapshot.watermark
        tracked.subscribedGeneration = maxOf(currentGeneration, connection.generation.value)
        publish()
    }

    private fun accept(sid: String, tracked: Tracked, payload: JsonElement?, liveOnly: Boolean, generation: Int) {
        val subscribed = payload?.let { runCatching { TroupeJson.decodeFromJsonElement(SubscribeReply.serializer(), it) }.getOrNull() } ?: return
        if (tracked.logEpoch.isEmpty()) tracked.historyUpTo = subscribed.headSeq
        tracked.logEpoch = subscribed.logEpoch
        if (liveOnly) tracked.lastSeq = maxOf(tracked.lastSeq, subscribed.headSeq)
        parsePendingPermissions(sid, payload)?.let { tracked.fold.installPermissions(it, subscribed.headSeq) }
        parsePendingQuestions(sid, payload)?.let { tracked.fold.installQuestions(it, subscribed.headSeq) }
        tracked.subscribedGeneration = generation
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
}
