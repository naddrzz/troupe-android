package dev.troupe.android.automations.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.state.AutomationEvents
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsRepository
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.core.store.WatchHub
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.workspace.WorkspaceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Builds an [AutomationsController] per project for the active Paired Server, anew whenever its id, address or
 * connection changes. A project's controller is kept while a screen shows it ([retain] / [release]); of the ones no
 * screen shows, the [MAX_IDLE] most recently released are kept and older ones closed, to be rebuilt on next use.
 */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val session: WorkspaceSessionManager,
    private val api: AutomationsApi,
    private val servers: PairedServerRepository,
) : ViewModel() {

    /** A project's controller and the job its pipeline runs in. */
    private class Pipeline(val job: Job, val controller: StateFlow<AutomationsController?>)

    private val pipelines = RetainedCache(MAX_IDLE, ::pipeline) { pipeline ->
        pipeline.controller.value?.close()
        pipeline.job.cancel()
    }

    /** Null until the server's connection is adopted and while it is replaced; a new pair replaces (and closes) the last controller. */
    fun controllerFor(projectId: String): StateFlow<AutomationsController?> = pipelines.get(projectId).controller

    /** A screen shows [projectId]'s controller: it is not closed until every such screen [release]s it. */
    fun retain(projectId: String) = pipelines.retain(projectId)

    fun release(projectId: String) = pipelines.release(projectId)

    private fun pipeline(projectId: String): Pipeline {
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        val controller = perConnection(
            servers = session.state.map { it.activeServer }.connectionKeys(),
            hubs = session.watchHub,
            sameServer = { key, active -> key.id == active.serverId },
            close = AutomationsController::close,
        ) { server, active ->
            AutomationsController(automationsRepository(api, servers, server, active.hub, scope), projectId, scope)
        }.stateIn(scope, SharingStarted.Eagerly, null)
        return Pipeline(job, controller)
    }

    override fun onCleared() = pipelines.clear()

    private companion object {
        const val MAX_IDLE = 2
    }
}

/** What a controller depends on in a Paired Server; the token is read per request, so it is not part of it. */
internal data class ConnectionKey(val id: String, val address: ServerAddress)

/**
 * The repository for the server [key] names. Its requests carry that server's own token, read anew for each request
 * (so a changed token applies at once), never the active server's: during a switch the two differ.
 */
internal fun automationsRepository(
    api: AutomationsApi,
    servers: PairedServerRepository,
    key: ConnectionKey,
    hub: WatchHub,
    scope: CoroutineScope,
) = AutomationsRepository(api, key.address, { servers.token(key.id) }, AutomationEvents(hub), scope)

/** The servers as their controllers see them: a rename or a `stayConnected` change does not rebuild anything. */
internal fun Flow<PairedServer?>.connectionKeys(): Flow<ConnectionKey?> =
    map { server -> server?.let { ConnectionKey(it.id, it.address) } }.distinctUntilChanged()

/**
 * [build]'s result for the latest server and hub while both are known and [sameServer] pairs them, else null: a
 * server switch publishes the new server before it withdraws the old server's hub, and a result must never use another
 * server's hub. A result is closed when another pair (or a missing one) replaces it and when collection ends. Hubs
 * compare by `equals`, which for a hub is identity, so a reconnect to the same server rebuilds.
 */
internal fun <S : Any, H : Any, T : Any> perConnection(
    servers: Flow<S?>,
    hubs: Flow<H?>,
    sameServer: (S, H) -> Boolean,
    close: (T) -> Unit,
    build: (S, H) -> T,
): Flow<T?> = flow {
    var last: T? = null
    try {
        combine(servers, hubs) { server, hub -> if (server != null && hub != null && sameServer(server, hub)) server to hub else null }
            .distinctUntilChanged()
            .collect { pair ->
                last?.let(close)
                last = null
                last = pair?.let { (server, hub) -> build(server, hub) }
                emit(last)
            }
    } finally {
        last?.let(close)
    }
}
