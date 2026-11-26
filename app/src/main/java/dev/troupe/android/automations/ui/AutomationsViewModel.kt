package dev.troupe.android.automations.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.state.AutomationEvents
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsRepository
import dev.troupe.android.workspace.WorkspaceSessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Builds an [AutomationsController] per project for the active Paired Server, anew whenever its connection changes. */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val session: WorkspaceSessionManager,
    private val api: AutomationsApi,
) : ViewModel() {

    private val controllers = HashMap<String, StateFlow<AutomationsController?>>()

    /** Null until the server's connection is adopted and while it is replaced; a new pair replaces (and closes) the last controller. */
    fun controllerFor(projectId: String): StateFlow<AutomationsController?> = controllers.getOrPut(projectId) {
        perConnection(
            servers = session.state.map { it.activeServer }.distinctUntilChanged(),
            hubs = session.watchHub,
            close = AutomationsController::close,
        ) { server, hub ->
            val repository = AutomationsRepository(api, server.address, session::currentToken, AutomationEvents(hub), viewModelScope)
            AutomationsController(repository, projectId, viewModelScope)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    override fun onCleared() {
        controllers.values.forEach { it.value?.close() }
    }
}

/**
 * [build]'s result for the latest server and hub while both are known, else null. A result is closed when another
 * pair (or a missing one) replaces it and when collection ends. Hubs compare by `equals`, which for a hub is identity,
 * so a reconnect to the same server rebuilds.
 */
internal fun <S : Any, H : Any, T : Any> perConnection(
    servers: Flow<S?>,
    hubs: Flow<H?>,
    close: (T) -> Unit,
    build: (S, H) -> T,
): Flow<T?> = flow {
    var last: T? = null
    try {
        combine(servers, hubs) { server, hub -> if (server != null && hub != null) server to hub else null }
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
