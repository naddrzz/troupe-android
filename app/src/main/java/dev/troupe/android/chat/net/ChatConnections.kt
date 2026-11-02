package dev.troupe.android.chat.net

import dev.troupe.android.chat.PendingRequests
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** The Paired Server is not (or no longer) paired: there is no token to open `/ws` with. */
class ChatConnectFailed(val failure: ConnectionFailure) : IOException(failure.name)

/**
 * A Paired Server's `/ws`: the [socket] to talk over, kept connected by its lifecycle, and the
 * server's [pending] permission requests and questions by Session.
 */
class ChatConnection internal constructor(
    val socket: ChatSocket,
    private val lifecycle: ConnectionLifecycle,
    val pending: PendingRequests,
    /** Reads View-only from `/health` on every connect. */
    private val healthWatch: Job,
) {
    val state: StateFlow<ConnectionState> get() = lifecycle.state
    /** Changes on every new connection; the server has then forgotten every subscription and watch. */
    val generation: StateFlow<Int> get() = lifecycle.generation

    fun retry() = lifecycle.retry()

    internal fun stop() {
        healthWatch.cancel()
        lifecycle.stop()
    }
}

/**
 * One shared `/ws` connection per Paired Server, opened on first use and kept connected until the
 * server is removed. The host writes a heartbeat every 10 s, so 25 s of silence reconnects.
 */
@Singleton
class ChatConnections internal constructor(
    private val sockets: ChatSocketFactory,
    private val repository: PairedServerRepository,
    private val api: ServerApi,
    network: NetworkMonitor,
    private val viewOnly: ViewOnlyRegistry,
    dispatcher: CoroutineDispatcher,
    private val newBackoff: () -> Backoff,
) {
    @Inject constructor(
        sockets: ChatSocketFactory,
        repository: PairedServerRepository,
        api: ServerApi,
        network: NetworkMonitor,
        viewOnly: ViewOnlyRegistry,
    ) : this(sockets, repository, api, network, viewOnly, Dispatchers.IO, { Backoff() })

    private val mutex = Mutex()
    private val open = ConcurrentHashMap<String, ChatConnection>()
    private val pending = ConcurrentHashMap<String, PendingRequests>()
    private val lifecycles = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        lifecycles.launch { network.available.collect { open.values.forEach { it.retry() } } }
        // A server that is no longer paired keeps no connection: it would go on with a removed token.
        lifecycles.launch {
            repository.servers.collect {
                mutex.withLock {
                    open.keys.filter { repository.server(it) == null }.forEach { id ->
                        open.remove(id)?.stop()
                        pending.remove(id)
                    }
                }
            }
        }
    }

    /** The permission requests and questions waiting on the user on [serverId], by Session. */
    fun pending(serverId: String): PendingRequests = pending.computeIfAbsent(serverId) { PendingRequests() }

    /**
     * The connection of [serverId], started if it isn't yet. One that failed for good (a rejected
     * token) is replaced, so pairing again takes effect. Throws [ChatConnectFailed] for a server
     * that is no longer paired.
     */
    suspend fun connection(serverId: String): ChatConnection = mutex.withLock {
        open[serverId]?.takeIf { it.state.value !is ConnectionState.Failed }?.let { return it }
        val server = repository.server(serverId)
        val token = repository.token(serverId)
        if (server == null || token == null) throw ChatConnectFailed(ConnectionFailure.Unauthorized)
        val socket = sockets.create(server.address, token)
        val lifecycle = ConnectionLifecycle(
            socket,
            healthy = { api.health(server.address) is ApiResult.Ok },
            scope = lifecycles,
            heartbeat = 10.seconds,
            backoff = newBackoff(),
        )
        val healthWatch = lifecycles.launch {
            lifecycle.generation.filter { it > 0 }.collectLatest {
                (api.health(server.address) as? ApiResult.Ok)?.let { viewOnly.set(serverId, !it.value.allowRemoteWrites) }
            }
        }
        ChatConnection(socket, lifecycle, pending(serverId), healthWatch).also {
            open[serverId] = it
            lifecycle.start()
        }
    }

    /** Drops every connection; for tests that end. */
    internal fun closeAll() {
        open.values.forEach { it.stop() }
        lifecycles.cancel()
    }
}
