package dev.troupe.android.workspace

import dev.troupe.android.attention.AttentionCenter
import dev.troupe.android.attention.AttentionItem
import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.core.protocol.Project
import dev.troupe.android.core.protocol.ProjectsState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.PersistenceKeys
import dev.troupe.android.core.store.StoreReadResult
import dev.troupe.android.core.store.WatchHub
import dev.troupe.android.core.store.WebStoreClient
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.terminal.net.ClientIdentityStore
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.protocol.PreservedTerminal
import dev.troupe.android.terminal.protocol.PreservedTerminals
import dev.troupe.android.terminal.protocol.SpawnedTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Live status of a single chat session from `chat_activity` watch + `AttentionCenter`. */
enum class SessionLiveState { Idle, Running, NeedsYou, Error }

data class AgentDescriptor(
    val id: String,
    val name: String,
    val provider: String = "",
    val installed: Boolean = true,
    val authRequired: Boolean = false,
)

data class WorkspaceShellState(
    val loading: Boolean = true,
    val activeServer: PairedServer? = null,
    val servers: List<PairedServer> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val viewOnly: Boolean = false,
    val tokenGateRequired: Boolean = false,
    val tokenGateRejected: Boolean = false,
    val tokenGateChecking: Boolean = false,
    val projects: List<Project> = emptyList(),
    val defaultProjectId: String? = null,
    val activeProjectId: String? = null,
    val workspace: PersistedWorkspace = PersistedWorkspace(),
    val sessions: List<SessionSummary> = emptyList(),
    val sessionStates: Map<String, SessionLiveState> = emptyMap(),
    val attentionItems: List<AttentionItem> = emptyList(),
    val terminals: List<PreservedTerminal> = emptyList(),
    val terminalNames: Map<String, String> = emptyMap(),
    val agents: List<AgentDescriptor> = emptyList(),
    val worktreePickByProject: Map<String, String> = emptyMap(),
    val bannerMessage: String? = null,
) {
    val activeProject: Project?
        get() = projects.firstOrNull { it.id == activeProjectId && !it.isArchived }
            ?: projects.firstOrNull { !it.isArchived }

    val activeWorktreePath: String?
        get() = activeProject?.let { worktreePickByProject[it.id] ?: it.path }

    /** Number of chats in the active project needing user attention, excluding the currently visible chat. */
    fun otherAttentionCount(): Int {
        val visibleChatId = (workspace.activeTab as? WorkspaceTab.AgentChat)?.sessionId
        val projectId = activeProject?.id
        val attentionFromCenter = attentionItems
            .filter { item ->
                item.sessionId != visibleChatId &&
                    (projectId == null || sessions.firstOrNull { it.sessionId == item.sessionId }?.projectId == projectId)
            }
            .map { it.sessionId }
            .toSet()
        val attentionFromActivity = sessionStates
            .filter { (sid, st) ->
                st == SessionLiveState.NeedsYou && sid != visibleChatId &&
                    (projectId == null || sessions.firstOrNull { it.sessionId == sid }?.projectId == projectId)
            }
            .keys
        return (attentionFromCenter + attentionFromActivity).size
    }
}

/**
 * Central state coordinator for the Troupe Android Mobile Workspace Shell V2.
 * Mirrors desktop/browser store coordination across `project-store`, `workspace-store`,
 * `shell-layout-store`, `app-settings-store`, `chat-activity-store`, and `terminal-store`.
 */
@Singleton
class WorkspaceSessionManager @Inject constructor(
    private val repository: PairedServerRepository,
    private val projectApi: ProjectApi,
    private val chatConnections: ChatConnections,
    private val terminalConnections: TerminalConnections,
    private val identityStore: ClientIdentityStore,
    private val viewOnlyRegistry: ViewOnlyRegistry,
    private val attentionCenter: AttentionCenter,
    val appSettingsStore: AppSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WorkspaceShellState())
    val state: StateFlow<WorkspaceShellState> = _state.asStateFlow()

    /** Parked workspaces for non-active projects so switching preserves tab state & PTYs. */
    private val parkedWorkspaces = HashMap<String, PersistedWorkspace>()
    /** Projects whose persisted workspace has been loaded from WebStore during this server session. */
    private val hydratedProjects = HashSet<String>()
    private val hydratedServerId = MutableStateFlow<String?>(null)

    @Volatile private var activeConnection: ChatConnection? = null
    @Volatile private var activeWatchHub: WatchHub? = null
    @Volatile private var activeStoreClient: WebStoreClient? = null
    private var sessionJob: Job? = null
    private var terminalsWatchJob: Job? = null
    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            combine(repository.servers, repository.activeServerId) { servers, activeId ->
                servers to servers.firstOrNull { it.id == activeId }
            }.distinctUntilChanged().collect { (servers, active) ->
                val previousId = _state.value.activeServer?.id
                _state.update { it.copy(servers = servers, activeServer = active, loading = active != null && previousId != active.id) }
                if (active == null) {
                    detachActiveSession()
                } else if (previousId != active.id || activeConnection == null) {
                    attachToServer(active)
                }
            }
        }
    }

    fun selectServer(serverId: String) {
        scope.launch { repository.setActiveServer(serverId) }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    fun retryConnection() {
        activeConnection?.retry()
        val server = _state.value.activeServer ?: return
        if (activeConnection == null) {
            scope.launch { attachToServer(server) }
        }
    }

    fun submitGateToken(rawToken: String) {
        val server = _state.value.activeServer ?: return
        val clean = rawToken.trim()
        if (clean.isEmpty()) return
        _state.update { it.copy(tokenGateChecking = true, tokenGateRejected = false) }
        scope.launch {
            repository.updateToken(server.id, clean)
            chatConnections.resetConnection(server.id)
            attachToServer(server.copy(hasToken = true))
        }
    }

    fun onAppBackground() {
        chatConnections.notifyLifecycle(background = true)
        scope.launch { activeStoreClient?.flushAll() }
    }

    fun onAppForeground() {
        chatConnections.notifyLifecycle(background = false)
    }

    suspend fun currentToken(): String? {
        val server = _state.value.activeServer ?: return null
        return repository.token(server.id)
    }

    fun currentStoreClient(): WebStoreClient? = activeStoreClient
    fun currentWatchHub(): WatchHub? = activeWatchHub
    fun currentConnection(): ChatConnection? = activeConnection

    /** Selects a project, parks the outgoing workspace, and restores the target project's workspace. */
    fun selectProject(projectId: String) {
        val current = _state.value
        if (current.activeProjectId == projectId) return
        current.activeProjectId?.let { oldId ->
            parkedWorkspaces[oldId] = current.workspace
        }
        val restored = parkedWorkspaces[projectId] ?: PersistedWorkspace()
        _state.update { it.copy(activeProjectId = projectId, workspace = restored) }

        scope.launch {
            activeStoreClient?.write(PersistenceKeys.ACTIVE_PROJECT, JsonPrimitive(projectId), immediate = true)
            if (projectId !in hydratedProjects) {
                loadProjectWorkspace(projectId)
            }
            refreshProjectTerminals(projectId)
        }
    }

    fun setWorktreePick(projectId: String, worktreePath: String) {
        val nextMap = _state.value.worktreePickByProject + (projectId to worktreePath)
        _state.update { it.copy(worktreePickByProject = nextMap) }
        scope.launch {
            val encoded = TroupeJson.encodeToJsonElement(
                ShellLayoutRecord.serializer(),
                ShellLayoutRecord(worktreePickByProject = nextMap),
            )
            activeStoreClient?.write(PersistenceKeys.SHELL_LAYOUT, encoded)
        }
    }

    fun openTab(tab: WorkspaceTab) {
        val next = _state.value.workspace.openOrFocus(tab)
        updateWorkspace(next)
    }

    fun focusTab(tabId: String) {
        val next = _state.value.workspace.focusTab(tabId)
        updateWorkspace(next)
    }

    fun closeTab(tabId: String) {
        val closing = _state.value.workspace.tabs.firstOrNull { it.id == tabId }
        val next = _state.value.workspace.closeTab(tabId)
        updateWorkspace(next)
        if (closing is WorkspaceTab.Terminal) {
            val serverId = _state.value.activeServer?.id
            if (serverId != null) terminalConnections.detach(serverId, closing.terminalId)
        }
    }

    /** Clears active tab to display the Start Chat V2 launcher hero for the active project. */
    fun startNewChat() {
        val current = _state.value.workspace
        if (current.activeTabId != null) {
            updateWorkspace(current.copy(activeTabId = null))
        }
    }

    fun openChat(sessionId: String, title: String? = null) {
        val summary = _state.value.sessions.firstOrNull { it.sessionId == sessionId }
        val resolvedProject = summary?.projectId
        if (resolvedProject != null && resolvedProject != _state.value.activeProjectId && _state.value.projects.any { it.id == resolvedProject }) {
            selectProject(resolvedProject)
        }
        openTab(
            WorkspaceTab.AgentChat(
                id = WorkspaceTabIds.chat(sessionId),
                sessionId = sessionId,
                title = title ?: summary?.title,
            ),
        )
    }

    suspend fun openChatOnServer(serverId: String, sessionId: String, title: String?) {
        if (_state.value.activeServer?.id != serverId) {
            repository.setActiveServer(serverId)
        }
        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            hydratedServerId.first { it == serverId }
        }
        openChat(sessionId, title)
    }

    fun spawnTerminal(cwdOverride: String? = null) {
        val server = _state.value.activeServer ?: return
        val project = _state.value.activeProject ?: return
        if (_state.value.viewOnly) {
            _state.update { it.copy(bannerMessage = "Server is in View-only mode.") }
            return
        }
        scope.launch {
            spawnTerminalSuspend(server, project, cwdOverride)
        }
    }

    private suspend fun spawnTerminalSuspend(
        server: PairedServer,
        project: Project,
        cwdOverride: String?,
    ) {
        try {
            val socket = terminalConnections.socket(server.id)
            val reply = socket.request(
                "spawn",
                buildJsonObject {
                    put("projectId", project.id)
                    put("cwd", cwdOverride ?: _state.value.activeWorktreePath ?: project.path.orEmpty())
                    put("cols", 80)
                    put("rows", 24)
                },
                ledgered = true,
            )
            if (!reply.success || reply.data == null) {
                _state.update { it.copy(bannerMessage = reply.error ?: "Failed to start terminal.") }
                return
            }
            val spawned = TroupeJson.decodeFromJsonElement(SpawnedTerminal.serializer(), reply.data)
            val tab = WorkspaceTab.Terminal(
                id = WorkspaceTabIds.terminal(spawned.id),
                terminalId = spawned.id,
                title = spawned.shell.substringAfterLast('/'),
            )
            openTab(tab)
            refreshProjectTerminals(project.id)
        } catch (e: IOException) {
            _state.update { it.copy(bannerMessage = e.message ?: "Could not start terminal.") }
        }
    }

    fun killTerminal(terminalId: String) {
        val server = _state.value.activeServer ?: return
        val projectId = _state.value.activeProject?.id
        scope.launch {
            killTerminalSuspend(server, projectId, terminalId)
        }
    }

    fun restartTerminal(terminalId: String, cwdOverride: String? = null) {
        val server = _state.value.activeServer ?: return
        val project = _state.value.activeProject ?: return
        if (_state.value.viewOnly) {
            _state.update { it.copy(bannerMessage = "Server is in View-only mode.") }
            return
        }
        scope.launch {
            if (killTerminalSuspend(server, project.id, terminalId)) {
                spawnTerminalSuspend(server, project, cwdOverride)
            }
        }
    }

    private suspend fun killTerminalSuspend(
        server: PairedServer,
        projectId: String?,
        terminalId: String,
    ): Boolean {
        return try {
            val socket = terminalConnections.socket(server.id)
            val reply = socket.request("kill", buildJsonObject { put("terminalId", terminalId) }, ledgered = true)
            if (!reply.success) {
                _state.update { it.copy(bannerMessage = reply.error ?: "Failed to kill terminal.") }
                return false
            }
            closeTab(WorkspaceTabIds.terminal(terminalId))
            if (projectId != null) refreshProjectTerminals(projectId)
            true
        } catch (e: IOException) {
            _state.update { it.copy(bannerMessage = e.message ?: "Failed to kill terminal.") }
            false
        }
    }

    fun renameTerminal(terminalId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        val updatedTabs = _state.value.workspace.tabs.map { tab ->
            if (tab is WorkspaceTab.Terminal && tab.terminalId == terminalId) tab.copy(title = clean) else tab
        }
        updateWorkspace(_state.value.workspace.copy(tabs = updatedTabs))
        _state.update { st ->
            st.copy(terminalNames = st.terminalNames + (terminalId to clean))
        }
    }

    fun updateTerminalMetadata(
        terminalId: String,
        cwd: String? = null,
        gitBranch: String? = null,
    ) {
        _state.update { st ->
            val updatedTerminals = st.terminals.map { term ->
                if (term.id == terminalId && cwd != null) {
                    PreservedTerminal(
                        id = term.id,
                        shell = term.shell,
                        cwd = cwd,
                        claim = term.claim,
                        hasLiveAttachment = term.hasLiveAttachment,
                    )
                } else {
                    term
                }
            }
            st.copy(terminals = updatedTerminals)
        }
    }

    fun createProject(name: String, path: String, color: String, onDone: (String?) -> Unit = {}) {
        val server = _state.value.activeServer ?: return
        scope.launch {
            val token = repository.token(server.id)
            when (val res = projectApi.createProject(server.address, token, name, path, color)) {
                is ApiResult.Ok -> {
                    refreshProjectsHttp(server)
                    selectProject(res.value.id)
                    onDone(null)
                }
                is ApiResult.ServerError -> onDone(res.message)
                ApiResult.Unauthorized -> {
                    _state.update { it.copy(tokenGateRequired = true) }
                    onDone("Authentication required.")
                }
                is ApiResult.Unreachable -> onDone("Server unreachable.")
            }
        }
    }

    fun setDefaultProject(projectId: String) {
        val server = _state.value.activeServer ?: return
        scope.launch {
            val token = repository.token(server.id)
            if (projectApi.setDefaultProject(server.address, token, projectId) is ApiResult.Ok) {
                refreshProjectsHttp(server)
            }
        }
    }

    fun archiveProject(projectId: String, archived: Boolean) {
        val server = _state.value.activeServer ?: return
        scope.launch {
            val token = repository.token(server.id)
            if (projectApi.updateProject(server.address, token, projectId, isArchived = archived) is ApiResult.Ok) {
                refreshProjectsHttp(server)
            }
        }
    }

    fun deleteProject(projectId: String, cascade: Boolean = false) {
        val server = _state.value.activeServer ?: return
        scope.launch {
            val token = repository.token(server.id)
            if (projectApi.removeProject(server.address, token, projectId, cascade) is ApiResult.Ok) {
                parkedWorkspaces.remove(projectId)
                hydratedProjects.remove(projectId)
                refreshProjectsHttp(server)
            }
        }
    }

    private fun updateWorkspace(next: PersistedWorkspace) {
        val projectId = _state.value.activeProjectId
        if (projectId != null) parkedWorkspaces[projectId] = next
        _state.update { it.copy(workspace = next) }
        if (projectId != null) {
            scope.launch {
                val encoded = TroupeJson.encodeToJsonElement(PersistedWorkspace.serializer(), next)
                activeStoreClient?.write(PersistenceKeys.workspace(projectId), encoded)
            }
        }
    }

    private suspend fun detachActiveSession() {
        activeStoreClient?.flushAll()
        sessionJob?.cancel()
        sessionJob = null
        terminalsWatchJob?.cancel()
        terminalsWatchJob = null
        appSettingsStore.detach()
        activeConnection = null
        activeWatchHub = null
        activeStoreClient = null
        hydratedServerId.value = null
        parkedWorkspaces.clear()
        hydratedProjects.clear()
        _state.update {
            WorkspaceShellState(
                loading = false,
                servers = it.servers,
            )
        }
    }

    private suspend fun attachToServer(server: PairedServer) {
        activeStoreClient?.flushAll()
        sessionJob?.cancel()
        terminalsWatchJob?.cancel()
        hydratedServerId.value = null
        parkedWorkspaces.clear()
        hydratedProjects.clear()
        appSettingsStore.detach()

        val conn = try {
            chatConnections.connection(server.id)
        } catch (e: ChatConnectFailed) {
            _state.update {
                it.copy(
                    loading = false,
                    tokenGateRequired = e.failure == ConnectionFailure.Unauthorized,
                    tokenGateRejected = it.tokenGateChecking,
                    tokenGateChecking = false,
                    connectionState = ConnectionState.Failed(e.failure),
                )
            }
            return
        }

        activeConnection = conn
        val deviceId = identityStore.get().deviceId

        sessionJob = scope.launch {
            val hub = WatchHub(conn, this)
            val store = WebStoreClient(conn, hub, deviceId, this)
            activeWatchHub = hub
            activeStoreClient = store
            appSettingsStore.attach(store, this)

            // Track connection state & mid-session 401 -> TokenGateScreen
            launch {
                conn.state.collect { st ->
                    val unauth = st is ConnectionState.Failed && st.reason == ConnectionFailure.Unauthorized
                    _state.update { cur ->
                        cur.copy(
                            connectionState = st,
                            tokenGateRequired = unauth,
                            tokenGateRejected = unauth && cur.tokenGateChecking,
                            tokenGateChecking = if (st == ConnectionState.Connected || st is ConnectionState.Failed) false else cur.tokenGateChecking,
                        )
                    }
                }
            }

            // Track view-only capability
            launch {
                viewOnlyRegistry.state(server.id).collect { vo ->
                    _state.update { it.copy(viewOnly = vo) }
                }
            }

            // Track AttentionCenter items for this server
            launch {
                attentionCenter.attention(server.id).collect { items ->
                    _state.update { it.copy(attentionItems = items) }
                }
            }

            // Initial HTTP fetch for projects (fast path before watch reset arrives)
            launch { refreshProjectsHttp(server) }

            // Watch 1: projects
            launch {
                hub.watch(WatchHub.PROJECTS).collect { update ->
                    val payload = if (update.reset) update.state else update.delta
                    if (payload != null) {
                        val decoded = runCatching {
                            TroupeJson.decodeFromJsonElement(ProjectsState.serializer(), payload)
                        }.getOrNull()
                        if (decoded != null) applyProjectsState(decoded)
                    }
                }
            }

            // Watch 2: chat_history
            launch {
                hub.watch(WatchHub.CHAT_HISTORY).collect { update ->
                    if (update.reset && update.state != null) {
                        parseHistoryList(update.state)?.let { list ->
                            _state.update { it.copy(sessions = list.sortedByDescending { s -> s.lastActivityAt }) }
                        }
                    } else if (!update.reset) {
                        fetchPersistedSessions(conn)
                    }
                }
            }

            // Watch 3: chat_activity
            launch {
                hub.watch(WatchHub.CHAT_ACTIVITY).collect { update ->
                    val raw = if (update.reset) update.state else update.delta
                    if (raw is JsonObject) {
                        applyChatActivity(raw, reset = update.reset)
                    }
                }
            }

            // Watch 4: agents
            launch {
                hub.watch(WatchHub.AGENTS).collect { update ->
                    val raw = if (update.reset) update.state else update.delta
                    if (raw != null) applyAgentsWatch(raw)
                }
            }

            // Watch 5: terminals (live sync when terminals are spawned or killed on desktop/other clients)
            terminalsWatchJob = launch {
                hub.watch(WatchHub.TERMINALS).collect {
                    val pid = _state.value.activeProjectId
                    if (pid != null) {
                        refreshProjectTerminals(pid)
                    }
                }
            }

            // Hydrate device-scoped active project + shell layout once connected
            launch {
                conn.generation.collect { gen ->
                    if (gen <= 0) return@collect
                    hydrateDeviceState(server.id, store, conn)
                }
            }
        }
    }

    private suspend fun hydrateDeviceState(serverId: String, store: WebStoreClient, conn: ChatConnection) {
        // Hydrate shell layout (worktree picks)
        val layoutRes = store.read(PersistenceKeys.SHELL_LAYOUT)
        if (layoutRes is StoreReadResult.Found) {
            runCatching { TroupeJson.decodeFromJsonElement(ShellLayoutRecord.serializer(), layoutRes.data) }
                .getOrNull()
                ?.let { rec -> _state.update { it.copy(worktreePickByProject = rec.worktreePickByProject) } }
        }
        // Hydrate device active-project
        val activeRes = store.read(PersistenceKeys.ACTIVE_PROJECT)
        val savedProjectId = (activeRes as? StoreReadResult.Found)?.data?.let {
            (it as? JsonPrimitive)?.contentOrNull
                ?: (it as? JsonObject)?.get("projectId")?.jsonPrimitive?.contentOrNull
        }
        val currentProjects = _state.value.projects.filterNot { it.isArchived }
        val resolvedId = when {
            savedProjectId != null && currentProjects.any { it.id == savedProjectId } -> savedProjectId
            _state.value.activeProjectId != null && currentProjects.any { it.id == _state.value.activeProjectId } -> _state.value.activeProjectId
            _state.value.defaultProjectId != null -> _state.value.defaultProjectId
            else -> currentProjects.firstOrNull()?.id
        }
        if (resolvedId != null) {
            _state.update { it.copy(activeProjectId = resolvedId, loading = false) }
            loadProjectWorkspace(resolvedId)
            refreshProjectTerminals(resolvedId)
        } else {
            _state.update { it.copy(loading = false) }
        }
        fetchPersistedSessions(conn)
        hydratedServerId.value = serverId
    }

    private suspend fun loadProjectWorkspace(projectId: String) {
        val store = activeStoreClient ?: return
        val res = store.read(PersistenceKeys.workspace(projectId))
        if (res is StoreReadResult.Found || res is StoreReadResult.NotFound) {
            hydratedProjects.add(projectId)
        }
        if (res is StoreReadResult.Found) {
            val loaded = runCatching {
                TroupeJson.decodeFromJsonElement(PersistedWorkspace.serializer(), res.data)
            }.getOrNull()
            if (loaded != null) {
                val localParked = parkedWorkspaces[projectId]
                val merged = if (localParked != null && localParked.tabs.isNotEmpty()) {
                    var acc: PersistedWorkspace = loaded
                    for (t in localParked.tabs) {
                        acc = acc.openOrFocus(t)
                    }
                    acc.copy(activeTabId = localParked.activeTabId ?: acc.activeTabId)
                } else {
                    loaded
                }
                parkedWorkspaces[projectId] = merged
                if (_state.value.activeProjectId == projectId) {
                    _state.update { it.copy(workspace = merged) }
                }
            }
        }
    }

    private suspend fun refreshProjectsHttp(server: PairedServer) {
        val token = repository.token(server.id)
        when (val res = projectApi.list(server.address, token)) {
            is ApiResult.Ok -> applyProjectsState(res.value)
            ApiResult.Unauthorized -> _state.update { it.copy(tokenGateRequired = true, loading = false) }
            else -> _state.update { it.copy(loading = false) }
        }
    }

    private fun applyProjectsState(data: ProjectsState) {
        val previousActiveId = _state.value.activeProjectId
        var nextActiveId: String? = null
        _state.update { cur ->
            val nonArchived = data.projects.filterNot { it.isArchived }
            val nextActive = when {
                cur.activeProjectId != null && nonArchived.any { it.id == cur.activeProjectId } -> cur.activeProjectId
                data.defaultProjectId != null && nonArchived.any { it.id == data.defaultProjectId } -> data.defaultProjectId
                else -> nonArchived.firstOrNull()?.id
            }
            nextActiveId = nextActive
            val nextWorkspace = if (nextActive != previousActiveId) {
                if (previousActiveId != null) {
                    parkedWorkspaces[previousActiveId] = cur.workspace
                }
                if (nextActive != null) parkedWorkspaces[nextActive] ?: PersistedWorkspace() else PersistedWorkspace()
            } else {
                cur.workspace
            }
            cur.copy(
                loading = false,
                projects = data.projects,
                defaultProjectId = data.defaultProjectId,
                activeProjectId = nextActive,
                workspace = nextWorkspace,
            )
        }
        val pid = nextActiveId
        if (pid != null) {
            scope.launch {
                if (pid != previousActiveId && pid !in hydratedProjects) {
                    loadProjectWorkspace(pid)
                }
                refreshProjectTerminals(pid)
            }
        }
    }

    private suspend fun fetchPersistedSessions(conn: ChatConnection) {
        val reply = try {
            conn.socket.request("list_persisted_sessions", buildJsonObject {})
        } catch (_: IOException) {
            return
        }
        if (reply.ok && reply.payload != null) {
            parseHistoryList(reply.payload)?.let { list ->
                _state.update { it.copy(sessions = list.sortedByDescending { s -> s.lastActivityAt }) }
            }
        }
    }

    private fun parseHistoryList(element: kotlinx.serialization.json.JsonElement): List<SessionSummary>? {
        val arr = (element as? JsonArray)
            ?: (element as? JsonObject)?.get("sessions") as? JsonArray
            ?: return null
        return arr.mapNotNull {
            runCatching { TroupeJson.decodeFromJsonElement(SessionSummary.serializer(), it) }.getOrNull()
        }
    }

    private fun applyChatActivity(obj: JsonObject, reset: Boolean) {
        val entries = (obj["sessions"] as? JsonObject) ?: obj
        _state.update { cur ->
            val next = if (reset) LinkedHashMap() else LinkedHashMap(cur.sessionStates)
            for ((sid, el) in entries) {
                val info = el as? JsonObject ?: continue
                val status = when {
                    info["needsUser"]?.jsonPrimitive?.booleanOrNull == true ||
                        info["pendingApproval"]?.jsonPrimitive?.booleanOrNull == true ||
                        info["status"]?.jsonPrimitive?.contentOrNull == "needs_user" -> SessionLiveState.NeedsYou
                    info["turnActive"]?.jsonPrimitive?.booleanOrNull == true ||
                        info["status"]?.jsonPrimitive?.contentOrNull == "running" -> SessionLiveState.Running
                    info["status"]?.jsonPrimitive?.contentOrNull == "error" -> SessionLiveState.Error
                    else -> SessionLiveState.Idle
                }
                next[sid] = status
            }
            cur.copy(sessionStates = next)
        }
    }

    private fun applyAgentsWatch(element: kotlinx.serialization.json.JsonElement) {
        val arr = (element as? JsonArray)
            ?: (element as? JsonObject)?.get("agents") as? JsonArray
            ?: return
        val list = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["configId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            AgentDescriptor(
                id = id,
                name = name,
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                installed = obj["installed"]?.jsonPrimitive?.booleanOrNull ?: true,
                authRequired = obj["authRequired"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        _state.update { it.copy(agents = list) }
    }

    private suspend fun refreshProjectTerminals(projectId: String) {
        val server = _state.value.activeServer ?: return
        if (_state.value.viewOnly) return
        try {
            val socket = terminalConnections.socket(server.id)
            val reply = socket.request("list_preserved", buildJsonObject { put("projectId", projectId) })
            if (reply.success && reply.data != null) {
                val decoded = TroupeJson.decodeFromJsonElement(PreservedTerminals.serializer(), reply.data)
                if (_state.value.activeProjectId == projectId) {
                    _state.update { it.copy(terminals = decoded.terminals) }
                }
            }
        } catch (_: IOException) {
            // Terminal socket reconnects in background.
        }
    }
}
