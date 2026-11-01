package dev.troupe.android.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.terminal.userMessage
import kotlinx.coroutines.CoroutineStart
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
import javax.inject.Inject

/** A Session of the Project; [lastActivityAt] is epoch milliseconds. */
data class SessionRow(val id: String, val title: String?, val lastActivityAt: Long)

data class ChatsUi(val loading: Boolean = true, val sessions: List<SessionRow> = emptyList(), val error: UiMessage? = null)

/**
 * A Project's Chats tab: its Sessions, newest first, from the host's `chat_history` watch. The
 * watch's state is the whole index; a delta only says something changed, so the index is fetched
 * again. A missed delta, a new host epoch or a `resync` marker watches again for fresh state.
 */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: ChatConnections,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val projectId: String = checkNotNull(savedState["projectId"])
    private val _ui = MutableStateFlow(ChatsUi())
    val ui: StateFlow<ChatsUi> = _ui.asStateFlow()

    /** `(hostEpoch, n)` of the last watch frame applied. */
    private var revision: Pair<String, Long>? = null

    init {
        viewModelScope.launch {
            val connection = try {
                connections.connection(serverId)
            } catch (e: ChatConnectFailed) {
                return@launch _ui.update { it.copy(loading = false, error = e.failure.userMessage(null)) }
            }
            // Collect before watching: the host queues the state frame ahead of the watch reply.
            launch(start = CoroutineStart.UNDISPATCHED) {
                connection.socket.frames.collect { frame ->
                    if (frame is ChatFrame.Watch && frame.watch == CHAT_HISTORY) onWatchFrame(connection, frame)
                }
            }
            launch {
                connection.state.collect { state ->
                    if (state is ConnectionState.Failed) _ui.update { it.copy(loading = false, error = state.reason.userMessage(state.message)) }
                }
            }
            connection.generation.collect { if (it > 0) watch(connection) }
        }
    }

    private suspend fun watch(connection: ChatConnection) {
        revision = null
        try {
            connection.socket.request("watch", buildJsonObject { put("watch", CHAT_HISTORY) })
        } catch (e: IOException) {
            // The connection dropped; the next one watches again.
        }
    }

    private suspend fun onWatchFrame(connection: ChatConnection, frame: ChatFrame.Watch) {
        val last = revision
        when {
            frame.reset -> {
                revision = frame.hostEpoch to frame.n
                install(frame.state)
            }
            last == null || last.first != frame.hostEpoch || frame.n != last.second + 1 ||
                (frame.delta as? JsonObject)?.containsKey("resync") == true -> watch(connection)
            else -> {
                revision = frame.hostEpoch to frame.n
                refetch(connection)
            }
        }
    }

    private suspend fun refetch(connection: ChatConnection) {
        val reply = try {
            connection.socket.request("list_persisted_sessions", JsonObject(emptyMap()))
        } catch (e: IOException) {
            return
        }
        when {
            reply.ok -> install(reply.payload)
            reply.code == ChatErrorCodes.UNSUPPORTED -> install(null)
        }
    }

    /** Installs the index, keeping this Project's Sessions. A host without durable history has none. */
    private fun install(index: JsonElement?) {
        val all = index?.let { runCatching { TroupeJson.decodeFromJsonElement(ListSerializer(SessionSummary.serializer()), it) }.getOrNull() }
        val rows = all.orEmpty()
            .filter { it.projectId == projectId }
            .sortedByDescending { it.lastActivityAt }
            .map { SessionRow(it.sessionId, it.title?.takeIf(String::isNotBlank), it.lastActivityAt) }
        _ui.value = ChatsUi(loading = false, sessions = rows)
    }

    private companion object {
        const val CHAT_HISTORY = "chat_history"
    }
}
