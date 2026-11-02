package dev.troupe.android.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.terminal.net.TerminalSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [Unsupported]: the server speaks another protocol version, so the app or the server needs an update. */
enum class ServerStatus { Checking, Connected, Unreachable, TokenRejected, Unsupported }

data class ServerRow(val server: PairedServer, val status: ServerStatus)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val api: ServerApi,
    private val sockets: TerminalSocketFactory,
    private val repository: PairedServerRepository,
) : ViewModel() {
    private val statuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    private val probes = mutableMapOf<String, Job>()

    /** `null` until storage has been read, so the empty state never flashes for existing users. */
    val rows: StateFlow<List<ServerRow>?> =
        combine(repository.servers, statuses) { servers, status ->
            servers.map { ServerRow(it, status[it.id] ?: ServerStatus.Checking) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Checks every Paired Server once: `/health`, then the `authenticate` handshake. Call when the list is shown. */
    fun probe() {
        viewModelScope.launch {
            val servers = repository.servers.first()
            statuses.value = servers.associate { it.id to ServerStatus.Checking }
            for (server in servers) {
                probes.remove(server.id)?.cancel()
                probes[server.id] = launch {
                    val status = probe(server)
                    statuses.update { it + (server.id to status) }
                }
            }
        }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.rename(id, trimmed) }
    }

    /** "Stay connected in background" for one server. */
    fun setStayConnected(id: String, value: Boolean) {
        viewModelScope.launch { repository.setStayConnected(id, value) }
    }

    fun remove(id: String) {
        probes.remove(id)?.cancel()
        viewModelScope.launch { repository.remove(id) }
    }

    private suspend fun probe(server: PairedServer): ServerStatus {
        if (api.health(server.address) !is ApiResult.Ok) return ServerStatus.Unreachable
        // A token that no longer decrypts (a reset Keystore, a restored backup) is fixed the same way: pair again.
        val token = try {
            repository.token(server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ServerStatus.TokenRejected
        val socket = sockets.create(server.address, token)
        val handshake = try {
            socket.connect()
        } finally {
            socket.close()
        }
        return when {
            handshake == ConnectionState.Connected -> ServerStatus.Connected
            handshake is ConnectionState.Failed && handshake.reason == ConnectionFailure.Unauthorized -> ServerStatus.TokenRejected
            handshake is ConnectionState.Failed && handshake.reason == ConnectionFailure.ProtocolMismatch -> ServerStatus.Unsupported
            else -> ServerStatus.Unreachable
        }
    }
}
