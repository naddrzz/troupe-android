package dev.troupe.android.terminal.net

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.Backoff
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionLifecycle
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.NetworkMonitor
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

/** A Paired Server's `/terminal/ws`: the [socket] to talk over, kept connected by its lifecycle. */
class TerminalConnection internal constructor(val socket: TerminalSocket, private val lifecycle: ConnectionLifecycle) {
    val state: StateFlow<ConnectionState> get() = lifecycle.state
    /** Changes on every new connection; the server has then forgotten every attachment. */
    val generation: StateFlow<Int> get() = lifecycle.generation

    fun retry() = lifecycle.retry()

    internal fun stop() = lifecycle.stop()
}

/**
 * One shared `/terminal/ws` connection per Paired Server, opened on first use and kept connected
 * from then on. A Terminal spawned from the Terminals tab and the Terminal screen that shows it
 * talk over the same connection.
 */
@Singleton
class TerminalConnections internal constructor(
    private val sockets: TerminalSocketFactory,
    private val repository: PairedServerRepository,
    private val api: ServerApi,
    network: NetworkMonitor,
    /** Each Paired Server's View-only state, read from `/health` on every connect. */
    val viewOnly: ViewOnlyRegistry,
    dispatcher: CoroutineDispatcher,
    private val newBackoff: () -> Backoff,
) {
    @Inject constructor(
        sockets: TerminalSocketFactory,
        repository: PairedServerRepository,
        api: ServerApi,
        network: NetworkMonitor,
        viewOnly: ViewOnlyRegistry,
    ) : this(sockets, repository, api, network, viewOnly, Dispatchers.IO, { Backoff() })

    private val mutex = Mutex()
    private val open = ConcurrentHashMap<String, TerminalConnection>()
    private val lifecycles = CoroutineScope(SupervisorJob() + dispatcher)

    /** For work that must outlive the screen that started it, such as a last cursor write. */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        lifecycles.launch { network.available.collect { open.values.forEach { it.retry() } } }
    }

    /**
     * The connection of [serverId], started if it isn't yet. One that failed for good (a rejected
     * token) is replaced, so pairing again takes effect. Throws [TerminalConnectFailed] for a server
     * that is no longer paired.
     */
    suspend fun connection(serverId: String): TerminalConnection = mutex.withLock {
        open[serverId]?.takeIf { it.state.value !is ConnectionState.Failed }?.let { return it }
        val server = repository.server(serverId)
        val token = repository.token(serverId)
        if (server == null || token == null) throw TerminalConnectFailed(ConnectionFailure.Unauthorized, null)
        val socket = sockets.create(server.address, token)
        val lifecycle = ConnectionLifecycle(
            socket,
            healthy = { api.health(server.address) is ApiResult.Ok },
            scope = lifecycles,
            backoff = newBackoff(),
        )
        lifecycles.launch {
            lifecycle.generation.filter { it > 0 }.collectLatest {
                (api.health(server.address) as? ApiResult.Ok)?.let { viewOnly.set(serverId, !it.value.allowRemoteWrites) }
            }
        }
        TerminalConnection(socket, lifecycle).also {
            open[serverId] = it
            lifecycle.start()
        }
    }

    /**
     * The socket of [serverId] once it is connected. Throws [TerminalConnectFailed] when the first
     * attempt fails or the connection is down; it keeps reconnecting in the background.
     */
    suspend fun socket(serverId: String): TerminalSocket {
        val connection = connection(serverId)
        return when (val state = connection.state.first { it != ConnectionState.Idle && it != ConnectionState.Connecting }) {
            ConnectionState.Connected -> connection.socket
            is ConnectionState.Failed -> throw TerminalConnectFailed(state.reason, state.message)
            else -> throw TerminalConnectFailed(ConnectionFailure.Unreachable, null)
        }
    }

    /**
     * Ends this connection's attachment to [terminalId] if the connection is up, so the Terminal is
     * not reported as open elsewhere once its screen is gone.
     */
    fun detach(serverId: String, terminalId: String) {
        val connection = open[serverId]?.takeIf { it.state.value == ConnectionState.Connected } ?: return
        background.launch {
            try {
                connection.socket.request("detach", buildJsonObject { put("terminalId", terminalId) })
            } catch (e: IOException) {
                // The connection is gone, and the attachment with it.
            }
        }
    }

    /** Drops every connection; for tests that end. */
    internal fun closeAll() {
        open.values.forEach { it.stop() }
        lifecycles.cancel()
    }
}
