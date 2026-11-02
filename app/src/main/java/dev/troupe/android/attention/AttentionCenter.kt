package dev.troupe.android.attention

import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every Paired Server's Attention, kept while the app process lives: one [AttentionMonitor] per
 * server over its shared `/ws`, started with [start] and following the paired list (a removed
 * server's Attention goes with it).
 */
@Singleton
class AttentionCenter internal constructor(
    private val connections: ChatConnections,
    private val repository: PairedServerRepository,
    private val api: ServerApi,
    dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    @Inject constructor(connections: ChatConnections, repository: PairedServerRepository, api: ServerApi) :
        this(connections, repository, api, Dispatchers.Default, System::currentTimeMillis)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val _servers = MutableStateFlow<Map<String, ServerAttention>>(emptyMap())
    /** Each Paired Server's Attention, by server id. */
    val servers: StateFlow<Map<String, ServerAttention>> = _servers.asStateFlow()

    private val _finished = MutableSharedFlow<FinishedTurn>(extraBufferCapacity = 64)
    /** Turns that ended in live Sessions of any Paired Server. */
    val finished: SharedFlow<FinishedTurn> = _finished.asSharedFlow()

    /** One server's Attention list, oldest first. */
    fun attention(serverId: String): Flow<List<AttentionItem>> =
        servers.map { it[serverId]?.items.orEmpty() }.distinctUntilChanged()

    /** Starts watching every Paired Server; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val jobs = HashMap<String, Job>()
            repository.servers.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged().collect { ids ->
                (jobs.keys - ids).forEach { id ->
                    jobs.remove(id)?.cancel()
                    _servers.update { it - id }
                }
                (ids - jobs.keys).forEach { id ->
                    jobs[id] = launch {
                        try {
                            watch(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // One server's failure (an unreadable token, say) leaves the others watching.
                            _servers.update { it - id }
                        }
                    }
                }
            }
        }
    }

    private suspend fun watch(serverId: String) {
        val connection = try {
            connections.connection(serverId)
        } catch (e: ChatConnectFailed) {
            return
        }
        val monitor = AttentionMonitor(connection) { projectNames(serverId) }
        val seen = HashMap<String, Long>()
        coroutineScope {
            launch { monitor.run() }
            launch {
                monitor.finished.collect { sessionId ->
                    val state = monitor.state.value
                    val session = state.index.firstOrNull { it.sessionId == sessionId }
                    _finished.emit(
                        FinishedTurn(
                            serverId,
                            sessionId,
                            projectName = session?.projectId?.let(state.projects::get),
                            sessionTitle = session?.title?.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
            combine(monitor.state, connection.pending.bySession, connection.pending.resolved) { state, openChats, resolved ->
                val items = deriveAttention(serverId, state.index, state.tracked, openChats, resolved, state.projects) { id ->
                    seen.getOrPut(id) { clock() }
                }
                ServerAttention(state.ready, items)
            }.distinctUntilChanged().collect { attention -> _servers.update { it + (serverId to attention) } }
        }
    }

    private suspend fun projectNames(serverId: String): Map<String, String>? {
        val server = repository.server(serverId) ?: return null
        val token = repository.token(serverId) ?: return null
        val result = api.projects(server.address, token)
        return if (result is ApiResult.Ok) result.value.projects.associate { it.id to it.name } else null
    }

    /** Stops every monitor; for tests that end. */
    internal fun stop() = scope.cancel()
}
