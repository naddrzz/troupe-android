package dev.troupe.android.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.net.ChatDisconnected
import dev.troupe.android.chat.net.ChatRequestTimeout
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SubscribeReply
import dev.troupe.android.chat.protocol.parseSnapshot
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.terminal.userMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

data class ChatUi(
    val loading: Boolean = true,
    val items: List<ChatItem> = emptyList(),
    val plan: List<PlanEntry> = emptyList(),
    val error: UiMessage? = null,
    /** A prompt is on its way or its turn is running: Send becomes Cancel. */
    val turnRunning: Boolean = false,
    /** The outcome of the user's last action, when it needs saying (the agent is busy, ...). */
    val notice: UiMessage? = null,
    /** Permission requests and questions waiting on the user, oldest first. */
    val requests: List<PendingRequest> = emptyList(),
    /** Ids of the [requests] whose answer is on its way. */
    val answering: Set<String> = emptySet(),
)

/**
 * An open Agent Chat: the Session's log, read through a subscription resumed by the cursor
 * `(logEpoch, lastSeq)` (termul's "Log epochs and the replay cursor" and "Bounded delivery").
 *
 * - Every new connection subscribes again from the cursor; the first one replays from the start.
 * - Events up to the subscription's `headSeq` are replay and may skip seqs; later ones apply only
 *   when they are the next seq.
 * - A `stale` reply, a `gap` frame or any other seq installs `recover_session_snapshot`, which
 *   replaces the transcript and continues from its watermark.
 *
 * Frames and reconnects go through one [inbox] handled in order, so events that overtake a reply
 * wait for it, and only one sync runs at a time.
 *
 * [send] prompts the agent under a fresh `turnId`, which is also the request's `command_id`: a resend
 * after a reconnect gets a receipt instead of a second turn. The user's prompt is shown only from the
 * host's `user_prompt` record, so it appears once. [cancel] sends `cancel_prompt`; a turn ends on
 * `prompt_complete`.
 *
 * Permission requests and questions become cards ([ChatUi.requests]), also published to the server's
 * [ChatConnections.pending]. [respond] and [answer] send `respond_permission` and `answer_question`;
 * the first answer wins, and a later one is `already_resolved`.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: ChatConnections,
    private val viewOnlyRegistry: ViewOnlyRegistry,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val sessionId: String = checkNotNull(savedState["sessionId"])
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    /** The Paired Server's `/ws` connection, for the header. */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var link: ChatConnection? = null
    /** Whether the Paired Server is View-only: the composer is disabled. */
    val viewOnly: StateFlow<Boolean> = viewOnlyRegistry.state(serverId)
    private val _draft = MutableStateFlow("")
    /** The prompt being written. */
    val draft: StateFlow<String> = _draft.asStateFlow()
    /** The turn id of a prompt whose `send_prompt` has not been answered yet. */
    private var sending: String? = null
    /** Requests answered here or elsewhere: a snapshot that replays them does not bring them back. */
    private val resolved = HashSet<String>()
    private val answering = HashSet<String>()

    private sealed interface Input {
        data class Frame(val frame: ChatFrame) : Input
        data object Connected : Input
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private var transcript = Transcript()
    private var logEpoch = ""
    private var lastSeq = 0L
    /** The current subscription's replay boundary: seqs above it are live and must be contiguous. */
    private var headSeq = 0L

    init {
        viewModelScope.launch {
            val current = try {
                connections.connection(serverId)
            } catch (e: ChatConnectFailed) {
                return@launch _ui.update { it.copy(loading = false, error = e.failure.userMessage(null)) }
            }
            link = current
            launch(start = CoroutineStart.UNDISPATCHED) {
                current.socket.frames.collect { frame ->
                    val sid = when (frame) {
                        is ChatFrame.Event -> frame.sid
                        is ChatFrame.Gap -> frame.sid
                        else -> null
                    }
                    if (sid == sessionId) inbox.send(Input.Frame(frame))
                }
            }
            launch {
                current.state.collect { state ->
                    _connection.value = state
                    if (state is ConnectionState.Failed) _ui.update { it.copy(loading = false, error = state.reason.userMessage(state.message)) }
                }
            }
            launch { current.generation.collect { if (it > 0) inbox.send(Input.Connected) } }
            for (input in inbox) {
                when (input) {
                    Input.Connected -> subscribe(current)
                    is Input.Frame -> when (val frame = input.frame) {
                        is ChatFrame.Gap -> recover(current)
                        is ChatFrame.Event -> onEvent(current, frame)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** The header's Retry: reconnect or check the server now. */
    fun retryConnection() {
        link?.retry()
    }

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    /** Sends the draft as a prompt; nothing while a turn runs or on a View-only server. */
    fun send() {
        viewModelScope.launch {
            val text = _draft.value.trim()
            if (text.isEmpty() || viewOnly.value || _ui.value.turnRunning) return@launch
            val agentId = transcript.agentId ?: return@launch _ui.update { it.copy(notice = UiMessage(R.string.chat_no_agent)) }
            val turnId = UUID.randomUUID().toString()
            sending = turnId
            _draft.value = ""
            _ui.update { it.copy(turnRunning = true, notice = null) }
            val reply = command("send_prompt", buildJsonObject {
                put("agentId", agentId)
                put("sessionId", sessionId)
                putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
                put("turnId", turnId)
            }, commandId = turnId)
            // The reply comes when the turn ends, or as a receipt for a resend. No reply in time
            // means the turn is still running: its prompt_complete ends it.
            sending = null
            if (reply != null && !reply.ok) {
                if (_draft.value.isEmpty()) _draft.value = text
                _ui.update { it.copy(notice = refusal(reply)) }
            }
            _ui.update { it.copy(turnRunning = transcript.turnRunning) }
        }
    }

    /** Cancels the running turn; it ends when its `prompt_complete` arrives. */
    fun cancel() {
        viewModelScope.launch {
            if (viewOnly.value) return@launch
            val agentId = transcript.agentId ?: return@launch
            val reply = command("cancel_prompt", buildJsonObject {
                put("agentId", agentId)
                put("sessionId", sessionId)
            }, commandId = null)
            if (reply != null && !reply.ok) _ui.update { it.copy(notice = refusal(reply)) }
        }
    }

    /** Chooses one of a permission request's options. */
    fun respond(request: PendingRequest.Permission, optionId: String) = answer(request, "respond_permission", buildJsonObject {
        request.agentId?.let { put("agentId", it) }
        put("requestId", request.id)
        put("optionId", optionId)
    })

    /** Answers a question with the chosen option values, or with one free text. */
    fun answer(question: PendingRequest.Question, values: List<String>) = answer(question, "answer_question", buildJsonObject {
        question.agentId?.let { put("agentId", it) }
        put("questionId", question.id)
        putJsonArray("values") { values.forEach { add(it) } }
    })

    private fun answer(request: PendingRequest, type: String, payload: JsonObject) {
        viewModelScope.launch {
            if (viewOnly.value || request.id in answering || request.id in resolved) return@launch
            answering += request.id
            _ui.update { it.copy(notice = null) }
            publish()
            val reply = command(type, payload, commandId = UUID.randomUUID().toString())
            answering -= request.id
            when {
                reply == null -> _ui.update { it.copy(notice = UiMessage(R.string.chat_answer_failed)) }
                reply.ok -> resolved += request.id
                reply.code == ChatErrorCodes.ALREADY_RESOLVED -> {
                    resolved += request.id
                    _ui.update { it.copy(notice = UiMessage(R.string.chat_already_answered)) }
                }
                // The host holds no such request any more: it was released.
                reply.code == ChatErrorCodes.STALE -> resolved += request.id
                else -> _ui.update { it.copy(notice = refusal(reply)) }
            }
            if (request.id in resolved) link?.pending?.resolve(request.id)
            publish()
        }
    }

    /** What to say about a refused write; a `forbidden` one makes the server View-only instead. */
    private fun refusal(reply: ChatFrame.Reply): UiMessage? {
        viewOnlyRegistry.onRefusal(serverId, reply.code)
        return when {
            reply.code == ChatErrorCodes.FORBIDDEN -> null
            reply.code == ChatErrorCodes.RATE_LIMITED || reply.message.orEmpty().contains(TURN_IN_PROGRESS) -> UiMessage(R.string.chat_agent_busy)
            reply.code == ChatErrorCodes.NOT_FOUND -> UiMessage(R.string.chat_no_agent)
            else -> UiMessage(R.string.chat_action_failed, reply.message ?: reply.code)
        }
    }

    /**
     * Sends a write and waits for its reply. A dropped connection resends it once the next one is up,
     * under the same [commandId], so it never runs twice. A write without one (`cancel_prompt`) is not
     * resent: it may have run, and a resend could hit a later turn. Null when no reply came in time,
     * the connection failed for good, or a write without [commandId] lost its connection.
     */
    private suspend fun command(type: String, payload: JsonObject, commandId: String?): ChatFrame.Reply? {
        val connection = link ?: return null
        while (true) {
            val before = connection.generation.value
            try {
                return connection.socket.request(type, payload, commandId)
            } catch (e: ChatDisconnected) {
                if (commandId == null) return null
                combine(connection.generation, connection.state) { generation, state ->
                    generation > before || state is ConnectionState.Failed
                }.first { it }
                if (connection.state.value is ConnectionState.Failed) return null
            } catch (e: ChatRequestTimeout) {
                return null
            }
        }
    }

    private suspend fun onEvent(connection: ChatConnection, event: ChatFrame.Event) {
        if (event.seq <= lastSeq) return
        if (event.seq > headSeq && event.seq != maxOf(lastSeq, headSeq) + 1) return recover(connection)
        transcript.apply(event)
        lastSeq = event.seq
        publish()
    }

    private suspend fun subscribe(connection: ChatConnection) {
        // A nonzero cursor without its epoch is never sent: it takes the snapshot path.
        if (lastSeq > 0 && logEpoch.isEmpty()) return recover(connection)
        val reply = request(connection, "subscribe", buildJsonObject {
            put("sessionId", sessionId)
            put("lastSeq", lastSeq)
            if (logEpoch.isNotEmpty()) put("logEpoch", logEpoch)
        }) ?: return
        if (reply.ok) {
            val subscribed = runCatching { TroupeJson.decodeFromJsonElement(SubscribeReply.serializer(), reply.payload!!) }.getOrNull()
                ?: return unavailable()
            logEpoch = subscribed.logEpoch
            headSeq = subscribed.headSeq
            parsePendingPermissions(sessionId, reply.payload)?.let { transcript.installPermissions(it, headSeq) }
            publish()
        } else when (reply.code) {
            ChatErrorCodes.STALE -> recover(connection)
            else -> unavailable()
        }
    }

    private suspend fun recover(connection: ChatConnection) {
        val reply = request(connection, "recover_session_snapshot", buildJsonObject { put("sessionId", sessionId) }) ?: return
        val snapshot = parseSnapshot(reply.payload)
        if (!reply.ok || snapshot == null) return unavailable()
        // Permission requests are not in the durable log: they ride beside it, or stay as they were.
        val permissions = parsePendingPermissions(sessionId, reply.payload)
            ?: transcript.pending().filterIsInstance<PendingRequest.Permission>()
        transcript = Transcript().apply { snapshot.events.filter { it.sid == null || it.sid == sessionId }.forEach(::apply) }
        transcript.installPermissions(permissions, snapshot.watermark)
        logEpoch = snapshot.logEpoch
        lastSeq = snapshot.watermark
        headSeq = snapshot.watermark
        publish()
    }

    private suspend fun request(connection: ChatConnection, type: String, payload: JsonObject): ChatFrame.Reply? = try {
        connection.socket.request(type, payload)
    } catch (e: IOException) {
        // The connection dropped; the next one subscribes again from the cursor.
        null
    }

    private fun publish() {
        val requests = transcript.pending().filter { it.id !in resolved }
        link?.pending?.set(sessionId, requests)
        _ui.update {
            it.copy(
                loading = false,
                items = transcript.items(),
                plan = transcript.plan,
                error = null,
                turnRunning = transcript.turnRunning || sending != null,
                requests = requests,
                answering = answering.toSet(),
            )
        }
    }

    override fun onCleared() {
        link?.pending?.release(sessionId)
    }

    private fun unavailable() = _ui.update { it.copy(loading = false, error = UiMessage(R.string.chat_unavailable)) }
}

/** termul's stable code for a prompt refused because another turn is running (`rate_limited` on `/ws`). */
private const val TURN_IN_PROGRESS = "ACP_TURN_IN_PROGRESS"
