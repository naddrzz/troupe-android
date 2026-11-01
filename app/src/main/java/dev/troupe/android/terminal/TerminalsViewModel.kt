package dev.troupe.android.terminal

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.terminal.net.ConnectionFailure
import dev.troupe.android.terminal.net.TerminalClaims
import dev.troupe.android.terminal.net.TerminalConnectFailed
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.protocol.SpawnedTerminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject

/** A user-facing message: a string resource and its optional `%1$s` argument. */
data class UiMessage(@StringRes val text: Int, val arg: String? = null)

/** What to tell the user when a Paired Server's `/terminal/ws` can't be opened. */
internal fun TerminalConnectFailed.userMessage(): UiMessage = when (failure) {
    ConnectionFailure.Unreachable -> UiMessage(R.string.pair_error_unreachable)
    ConnectionFailure.Unauthorized -> UiMessage(R.string.pair_error_unauthorized)
    ConnectionFailure.ProtocolMismatch -> UiMessage(R.string.pair_error_protocol)
    ConnectionFailure.Refused -> UiMessage(R.string.pair_error_server, message)
}

/** [openTerminal] is the Terminal just spawned, until the screen has navigated to it. */
data class TerminalsUi(
    val spawning: Boolean = false,
    val error: UiMessage? = null,
    val openTerminal: String? = null,
)

/** A Project's Terminals tab. Listing the running Terminals arrives with a later slice. */
@HiltViewModel
class TerminalsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: TerminalConnections,
    private val claims: TerminalClaims,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val projectId: String = checkNotNull(savedState["projectId"])
    private val _ui = MutableStateFlow(TerminalsUi())
    val ui: StateFlow<TerminalsUi> = _ui.asStateFlow()

    /** New terminal: a ledgered `spawn` in this Project. Its claim is kept so the Terminal screen can attach. */
    fun spawn() {
        if (_ui.value.spawning) return
        _ui.value = TerminalsUi(spawning = true)
        viewModelScope.launch { _ui.value = spawnTerminal() }
    }

    fun onTerminalOpened() = _ui.update { it.copy(openTerminal = null) }

    private suspend fun spawnTerminal(): TerminalsUi {
        val payload = buildJsonObject {
            put("projectId", projectId)
            put("cols", 80)
            put("rows", 24)
        }
        val reply = try {
            connections.socket(serverId).request("spawn", payload, ledgered = true)
        } catch (e: TerminalConnectFailed) {
            return TerminalsUi(error = e.userMessage())
        } catch (e: IOException) {
            return TerminalsUi(error = UiMessage(R.string.pair_error_unreachable))
        }
        if (!reply.success) return TerminalsUi(error = UiMessage(R.string.terminals_error_spawn, reply.error.orEmpty()))
        val spawned = reply.data?.let { runCatching { TroupeJson.decodeFromJsonElement(SpawnedTerminal.serializer(), it) }.getOrNull() }
            ?: return TerminalsUi(error = UiMessage(R.string.terminals_error_spawn_reply))
        claims.put(serverId, spawned.id, spawned.claim)
        return TerminalsUi(openTerminal = spawned.id)
    }
}
