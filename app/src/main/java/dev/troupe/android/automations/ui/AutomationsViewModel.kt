package dev.troupe.android.automations.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.state.AutomationEvents
import dev.troupe.android.automations.state.AutomationsController
import dev.troupe.android.automations.state.AutomationsRepository
import dev.troupe.android.core.store.WatchHub
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.workspace.WorkspaceSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Builds an [AutomationsController] per project for the active Paired Server, anew whenever its connection changes. */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val session: WorkspaceSessionManager,
    private val api: AutomationsApi,
) : ViewModel() {

    /** The active server's connection; the hub is compared by identity, as a reconnect replaces it. */
    private class Link(val serverId: String, val address: ServerAddress, val hub: WatchHub) {
        override fun equals(other: Any?) =
            other is Link && other.serverId == serverId && other.address == address && other.hub === hub

        override fun hashCode() = serverId.hashCode()
    }

    private val repository: StateFlow<AutomationsRepository?> = session.state
        .map { state ->
            val server = state.activeServer
            val hub = session.currentWatchHub()
            if (server != null && hub != null) Link(server.id, server.address, hub) else null
        }
        .distinctUntilChanged()
        .map { link ->
            link?.let { AutomationsRepository(api, it.address, session::currentToken, AutomationEvents(it.hub), viewModelScope) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val controllers = HashMap<String, MutableStateFlow<AutomationsController?>>()

    /** Null until the server's connection is ready; a new controller replaces (and closes) the last one when it changes. */
    fun controllerFor(projectId: String): StateFlow<AutomationsController?> = controllers.getOrPut(projectId) {
        val controller = MutableStateFlow<AutomationsController?>(null)
        viewModelScope.launch {
            repository.collect { repo ->
                controller.value?.close()
                controller.value = repo?.let { AutomationsController(it, projectId, viewModelScope) }
            }
        }
        controller
    }

    override fun onCleared() {
        controllers.values.forEach { it.value?.close() }
    }
}
