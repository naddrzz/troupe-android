package dev.troupe.android.projects

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.protocol.Project
import dev.troupe.android.core.protocol.ProjectsState
import dev.troupe.android.servers.PairedServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProjectsUi {
    data object Loading : ProjectsUi
    data class Loaded(val serverName: String, val projects: List<Project>) : ProjectsUi
    data class Error(@StringRes val message: Int, val arg: String? = null) : ProjectsUi
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: ServerApi,
    private val repository: PairedServerRepository,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val _ui = MutableStateFlow<ProjectsUi>(ProjectsUi.Loading)
    val ui: StateFlow<ProjectsUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = ProjectsUi.Loading
        viewModelScope.launch { _ui.value = load() }
    }

    private suspend fun load(): ProjectsUi {
        val server = repository.server(serverId)
        val token = repository.token(serverId)
        if (server == null || token == null) return ProjectsUi.Error(R.string.pair_error_unauthorized)
        return when (val result = api.projects(server.address, token)) {
            is ApiResult.Ok -> ProjectsUi.Loaded(server.name, visibleProjects(result.value))
            ApiResult.Unauthorized -> ProjectsUi.Error(R.string.pair_error_unauthorized)
            is ApiResult.Unreachable -> ProjectsUi.Error(R.string.pair_error_unreachable)
            is ApiResult.ServerError -> ProjectsUi.Error(R.string.pair_error_server, result.message)
        }
    }
}

/** As on the desktop: archived Projects hidden, the default one first, the rest by name. */
internal fun visibleProjects(state: ProjectsState): List<Project> {
    fun isDefault(p: Project) = p.isDefault || p.id == state.defaultProjectId
    return state.projects
        .filterNot { it.isArchived }
        .map { if (isDefault(it)) it.copy(isDefault = true) else it }
        .sortedWith(compareBy<Project> { !it.isDefault }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
