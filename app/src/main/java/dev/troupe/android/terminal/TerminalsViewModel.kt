package dev.troupe.android.terminal

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.terminal.net.TerminalConnectFailed
import dev.troupe.android.terminal.net.TerminalConnection
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.protocol.PreservedTerminals
import dev.troupe.android.terminal.protocol.SpawnedTerminal
import dev.troupe.android.terminal.protocol.TerminalErrorCodes
import dev.troupe.android.terminal.protocol.TerminalFrame
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

/** A user-facing message: a string resource and its optional `%1$s` argument. */
data class UiMessage(@StringRes val text: Int, val arg: String? = null)

/** What to tell the user when a Paired Server's connection fails; [message] is the server's reason. */
internal fun ConnectionFailure.userMessage(message: String?): UiMessage = when (this) {
    ConnectionFailure.Unreachable -> UiMessage(R.string.pair_error_unreachable)
    ConnectionFailure.Unauthorized -> UiMessage(R.string.pair_error_unauthorized)
    ConnectionFailure.ProtocolMismatch -> UiMessage(R.string.pair_error_protocol)
    ConnectionFailure.Refused -> UiMessage(R.string.pair_error_server, message)
}

/** What to tell the user when a Paired Server's `/terminal/ws` can't be opened. */
internal fun TerminalConnectFailed.userMessage(): UiMessage = failure.userMessage(message)

/** A running Terminal of the Project. [openElsewhere]: another device is attached to it. */
data class TerminalRow(val id: String, val shell: String, val cwd: String, val openElsewhere: Boolean)

/** [openTerminal] is the Terminal just spawned, until the screen has navigated to it. */
data class TerminalsUi(
    val loading: Boolean = true,
    val terminals: List<TerminalRow> = emptyList(),
    val spawning: Boolean = false,
    val killing: String? = null,
    val error: UiMessage? = null,
    val openTerminal: String? = null,
)

/** Thrown inside this ViewModel to end an operation with a message for the user. */
private class Refusal(val reason: UiMessage) : Exception()

/** A Project's Terminals tab: the running Terminals, "New terminal" and kill, plus the header's connection state. */
@HiltViewModel
class TerminalsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: TerminalConnections,
    private val cursors: TerminalCursorStore,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val projectId: String = checkNotNull(savedState["projectId"])
    private val _ui = MutableStateFlow(TerminalsUi())
    val ui: StateFlow<TerminalsUi> = _ui.asStateFlow()
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    /** The Paired Server's connection, for the header. */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var link: TerminalConnection? = null

    init {
        viewModelScope.launch {
            val current = try {
                connections.connection(serverId)
            } catch (e: TerminalConnectFailed) {
                return@launch _ui.update { it.copy(loading = false, error = e.userMessage()) }
            }
            link = current
            // No list_preserved on reconnect: it reissues the claim of every Terminal without a
            // live attachment, racing the Terminal screen's re-attach with the old claim.
            current.state.collect { _connection.value = it }
        }
    }

    /** The header's Retry: reconnect or check the server now. */
    fun retryConnection() {
        link?.retry()
    }

    /**
     * Lists the Project's running Terminals with `list_preserved`. The server issues a fresh claim
     * for each, which replaces the stored one.
     */
    fun refresh() {
        viewModelScope.launch {
            val result = attempt {
                val data = request("list_preserved", buildJsonObject { put("projectId", projectId) })
                val list = runCatching { TroupeJson.decodeFromJsonElement(PreservedTerminals.serializer(), data) }.getOrNull()
                    ?: throw Refusal(UiMessage(R.string.terminals_error_list_reply))
                list.terminals.forEach { t -> t.claim?.let { cursors.putClaim(serverId, t.id, it) } }
                list.terminals.map { TerminalRow(it.id, it.shell, it.cwd, it.hasLiveAttachment) }
            }
            _ui.update { it.copy(loading = false) }
            result.onSuccess { rows -> _ui.update { it.copy(terminals = rows, error = null) } }
                .onFailure { e -> _ui.update { it.copy(error = (e as Refusal).reason) } }
        }
    }

    /** New terminal: a ledgered `spawn` in this Project. Its claim is stored so the Terminal screen can attach. */
    fun spawn() {
        if (_ui.value.spawning) return
        _ui.update { it.copy(spawning = true, error = null) }
        viewModelScope.launch {
            val result = attempt {
                val data = request("spawn", buildJsonObject {
                    put("projectId", projectId)
                    put("cols", 80)
                    put("rows", 24)
                }, ledgered = true, failure = R.string.terminals_error_spawn)
                val spawned = runCatching { TroupeJson.decodeFromJsonElement(SpawnedTerminal.serializer(), data) }.getOrNull()
                    ?: throw Refusal(UiMessage(R.string.terminals_error_spawn_reply))
                cursors.putClaim(serverId, spawned.id, spawned.claim)
                spawned.id
            }
            _ui.update { it.copy(spawning = false) }
            result.onSuccess { id -> _ui.update { it.copy(openTerminal = id) } }
                .onFailure { e -> _ui.update { it.copy(error = (e as Refusal).reason) } }
        }
    }

    /**
     * Kills [terminalId] with a ledgered `kill`; the screen confirms first. The server only lets a
     * connection kill a Terminal it is attached to, so this attaches with the stored claim first.
     */
    fun kill(terminalId: String) {
        if (_ui.value.killing != null) return
        _ui.update { it.copy(killing = terminalId, error = null) }
        viewModelScope.launch {
            val result = attempt {
                val stored = cursors.get(serverId, terminalId) ?: throw Refusal(UiMessage(R.string.terminal_unavailable))
                request("attach", buildJsonObject {
                    put("terminalId", terminalId)
                    put("claim", stored.claim)
                    // Only the authorization is wanted: a cursor at the head replays little.
                    put("lastSeq", stored.lastSeq)
                    if (stored.logEpoch.isNotEmpty() || stored.lastSeq > 0) put("logEpoch", stored.logEpoch)
                }, failure = R.string.terminals_error_kill, terminalId = terminalId)
                request("kill", buildJsonObject { put("terminalId", terminalId) }, ledgered = true, failure = R.string.terminals_error_kill)
                cursors.remove(serverId, terminalId)
            }
            _ui.update { it.copy(killing = null) }
            result.onSuccess {
                _ui.update { ui -> ui.copy(terminals = ui.terminals.filterNot { it.id == terminalId }) }
                refresh()
            }.onFailure { e -> _ui.update { it.copy(error = (e as Refusal).reason) } }
        }
    }

    fun onTerminalOpened() = _ui.update { it.copy(openTerminal = null) }

    /**
     * One request over the Paired Server's connection; returns the reply `data`. Every failure is a
     * [Refusal]: a refused reply reads [failure] with the server's reason, and `UNAUTHORIZED` for
     * [terminalId] means its claim is no longer valid, which drops it.
     */
    private suspend fun request(
        type: String,
        payload: JsonObject,
        ledgered: Boolean = false,
        @StringRes failure: Int = R.string.pair_error_server,
        terminalId: String? = null,
    ): JsonObject {
        val reply: TerminalFrame.Reply = try {
            connections.socket(serverId).request(type, payload, ledgered)
        } catch (e: TerminalConnectFailed) {
            throw Refusal(e.userMessage())
        } catch (e: IOException) {
            throw Refusal(UiMessage(R.string.pair_error_unreachable))
        }
        if (!reply.success) {
            if (terminalId != null && reply.code == TerminalErrorCodes.UNAUTHORIZED) {
                cursors.remove(serverId, terminalId)
                throw Refusal(UiMessage(R.string.terminal_unavailable))
            }
            throw Refusal(UiMessage(failure, reply.error.orEmpty()))
        }
        return reply.data as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Runs [block], catching only [Refusal]s; cancellation and bugs propagate. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Refusal) {
        Result.failure(e)
    }
}
