package dev.troupe.android.terminal.net

import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val open = mutableMapOf<String, TerminalSocket>()

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
}

/**
 * Claims of the Terminals this process spawned, per (Paired Server, Terminal), held in memory only.
 * The encrypted, persisted store that survives process death replaces this with re-attach.
 */
@Singleton
class TerminalClaims @Inject constructor() {
    private val claims = ConcurrentHashMap<Pair<String, String>, String>()

    fun put(serverId: String, terminalId: String, claim: String) {
        claims[serverId to terminalId] = claim
    }

    fun claim(serverId: String, terminalId: String): String? = claims[serverId to terminalId]
}
