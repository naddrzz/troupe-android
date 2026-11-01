package dev.troupe.android.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SubscribeReply
import dev.troupe.android.chat.protocol.parseSnapshot
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.terminal.userMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject

data class ChatUi(
    val loading: Boolean = true,
    val items: List<ChatItem> = emptyList(),
    val plan: List<PlanEntry> = emptyList(),
    val error: UiMessage? = null,
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
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: ChatConnections,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val sessionId: String = checkNotNull(savedState["sessionId"])
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    /** The Paired Server's `/ws` connection, for the header. */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var link: ChatConnection? = null

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
        transcript = Transcript().apply { snapshot.events.filter { it.sid == null || it.sid == sessionId }.forEach(::apply) }
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
        _ui.value = ChatUi(loading = false, items = transcript.items(), plan = transcript.plan)
    }

    private fun unavailable() = _ui.update { it.copy(loading = false, error = UiMessage(R.string.chat_unavailable)) }
}
