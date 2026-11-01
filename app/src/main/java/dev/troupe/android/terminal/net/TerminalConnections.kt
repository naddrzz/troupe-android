package dev.troupe.android.terminal.net

import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Opening the Paired Server's `/terminal/ws` failed with [failure]; [message] is the server's reason, if any. */
class TerminalConnectFailed(val failure: ConnectionFailure, message: String?) : IOException(message ?: failure.name)

/**
 * One shared `/terminal/ws` connection per Paired Server, opened on first use. A Terminal spawned
 * from the Terminals tab and the Terminal screen that shows it talk over the same connection.
 * Reconnect and idle close arrive with a later slice; a dropped connection is reopened on next use.
 */
@Singleton
class TerminalConnections @Inject constructor(
    private val sockets: TerminalSocketFactory,
    private val repository: PairedServerRepository,
) {
    private val mutex = Mutex()
    private val open = ConcurrentHashMap<String, TerminalSocket>()

    /** For work that must outlive the screen that started it, such as a last cursor write. */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The connected socket of [serverId]; throws [TerminalConnectFailed] when it can't be opened. */
    suspend fun socket(serverId: String): TerminalSocket = mutex.withLock {
        open[serverId]?.takeIf { it.state.value == ConnectionState.Connected }?.let { return it }
        val server = repository.server(serverId)
        val token = repository.token(serverId)
        if (server == null || token == null) throw TerminalConnectFailed(ConnectionFailure.Unauthorized, null)
        val socket = sockets.create(server.address, token)
        when (val state = socket.connect()) {
            ConnectionState.Connected -> socket.also { open[serverId] = it }
            is ConnectionState.Failed -> throw TerminalConnectFailed(state.reason, state.message)
            else -> throw TerminalConnectFailed(ConnectionFailure.Unreachable, null)
        }
    }

    /**
     * Ends this connection's attachment to [terminalId] if the connection is still open, so the
     * Terminal is not reported as open elsewhere once its screen is gone.
     */
    fun detach(serverId: String, terminalId: String) {
        val socket = open[serverId]?.takeIf { it.state.value == ConnectionState.Connected } ?: return
        background.launch {
            try {
                socket.request("detach", buildJsonObject { put("terminalId", terminalId) })
            } catch (e: IOException) {
                // The connection is gone, and the attachment with it.
            }
        }
    }
}
