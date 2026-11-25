package dev.troupe.android.workspace

import dev.troupe.android.agents.AgentSetup
import dev.troupe.android.agents.AgentSetupState
import dev.troupe.android.attention.AttentionCenter
import dev.troupe.android.attention.AttentionItem
import dev.troupe.android.chat.net.ChatConnectFailed
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.chat.protocol.UNASSIGNED_PROJECT_ID
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
import dev.troupe.android.terminal.TerminalCursorStore
import dev.troupe.android.terminal.net.ClientIdentityStore
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.protocol.PreservedTerminal
import dev.troupe.android.terminal.protocol.PreservedTerminals
import dev.troupe.android.terminal.protocol.SpawnedTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** An agent a chat can start with: a launch config's id and name. */
data class AgentDescriptor(
    val id: String,
    val name: String,
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
    val attentionItemsByServer: Map<String, List<AttentionItem>> = emptyMap(),
    val terminals: List<PreservedTerminal> = emptyList(),
    val terminalNames: Map<String, String> = emptyMap(),
    /** False until the server's Project list arrived once, so "no Projects" is not a guess. */
    val projectsLoaded: Boolean = false,
    val agentSetup: AgentSetupState = AgentSetupState(),
    /** Troupe's first-run flow is due: see [shouldShowOnboarding]. */
    val onboarding: Boolean = false,
    val worktreePickByProject: Map<String, String> = emptyMap(),
    val bannerMessage: String? = null,
) {
    val activeProject: Project?
        get() = projects.firstOrNull { it.id == activeProjectId && !it.isArchived }
            ?: projects.firstOrNull { !it.isArchived }

    /** The agents a new chat can start with: saved configs and ready catalog agents. */
    val agents: List<AgentDescriptor>
        get() = agentSetup.launchable.map { AgentDescriptor(id = it.id, name = it.name) }

    val activeWorktreePath: String?
        get() = activeProject?.let { worktreePickByProject[it.id] ?: it.path }

    /**
     * Number of chats on the active Paired Server needing user attention, excluding the currently
     * visible chat (spans all Projects on the server so nothing waiting in another Project is missed).
     */
    fun otherAttentionCount(): Int {
        val visibleChatId = (workspace.activeTab as? WorkspaceTab.AgentChat)?.sessionId
        val attentionFromCenter = attentionItems
            .filter { it.sessionId != visibleChatId }
            .map { it.sessionId }
            .toSet()
        val attentionFromActivity = sessionStates
            .filter { (sid, st) -> st == SessionLiveState.NeedsYou && sid != visibleChatId }
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
    private val terminalCursors: TerminalCursorStore,
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
    @Volatile private var activeAgentSetup: AgentSetup? = null
    @Volatile private var explicitProjectSelection: Boolean = false
    private val sessionMutex = Mutex()
    /** `spawn` and `list_preserved` both issue claims; one at a time, so the stored claim is the newest. */
    private val terminalsMutex = Mutex()
    private var pendingTransferWrites: Pair<String, WebStoreClient.UnsentWrites>? = null
    private var sessionJob: Job? = null
    private var terminalsWatchJob: Job? = null
    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            attentionCenter.servers.collect { byServer ->
                val mapped = byServer.mapValues { it.value.items }
                _state.update { cur ->
                    val activeId = cur.activeServer?.id
                    cur.copy(
                        attentionItemsByServer = mapped,
                        attentionItems = activeId?.let { mapped[it] }.orEmpty(),
                    )
                }
            }
        }
        scope.launch {
            combine(repository.servers, repository.activeServerId) { servers, activeId ->
                servers to servers.firstOrNull { it.id == activeId }
            }.distinctUntilChanged().collect { (servers, active) ->
                val prevState = _state.value
                val previousId = prevState.activeServer?.id
                val currentServerIds = servers.map { it.id }.toSet()
                prevState.servers.forEach { old ->
                    if (old.id !in currentServerIds) appSettingsStore.forget(old.id)
                }
                val switchingServer = active != null && previousId != null && previousId != active.id
                _state.update { cur ->
                    if (switchingServer) {
                        WorkspaceShellState(
                            loading = true,
                            activeServer = active,
                            servers = servers,
                            attentionItems = cur.attentionItemsByServer[active.id].orEmpty(),
                            attentionItemsByServer = cur.attentionItemsByServer,
                        )
                    } else {
                        cur.copy(
                            servers = servers,
                            activeServer = active,
                            attentionItems = active?.id?.let { cur.attentionItemsByServer[it] }.orEmpty(),
                            loading = active != null && previousId != active.id,
                        )
                    }
                }
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
            attachToServer(server.copy(hasToken = true), rebindingSameServer = true)
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
    fun currentAgentSetup(): AgentSetup? = activeAgentSetup

    /** Records that first run is over (Troupe's `onboardingCompletedAt`, an ISO-8601 instant). */
    fun finishOnboarding() {
        _state.update { it.copy(onboarding = false) }
        appSettingsStore.update(scope) { it.copy(onboardingCompletedAt = java.time.Instant.now().toString()) }
    }

    /** Selects a project, parks the outgoing workspace, and restores the target project's workspace. */
    fun selectProject(projectId: String) {
        val current = _state.value
        if (current.activeProjectId == projectId) return
        explicitProjectSelection = true
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

    fun openChat(sessionId: String, title: String? = null, projectId: String? = null) {
        val cur = _state.value
        val summary = cur.sessions.firstOrNull { it.sessionId == sessionId }
        val attentionItem = cur.attentionItems.firstOrNull { it.sessionId == sessionId }
        val resolvedProject = projectId ?: summary?.projectId ?: attentionItem?.projectId
        // A quick chat belongs to the reserved Project `__unassigned__`, which is never selectable.
        if (resolvedProject != null && resolvedProject != UNASSIGNED_PROJECT_ID && resolvedProject != cur.activeProjectId) {
            if (cur.projects.isEmpty() || cur.projects.any { it.id == resolvedProject && !it.isArchived }) {
                selectProject(resolvedProject)
            }
        }
        openTab(
            WorkspaceTab.AgentChat(
                id = WorkspaceTabIds.chat(sessionId),
                sessionId = sessionId,
                title = title ?: summary?.title ?: attentionItem?.sessionTitle,
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
        val project = _state.value.activeProject
            ?: return _state.update { it.copy(bannerMessage = "Add a project to open a terminal.") }
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
        // Read before waiting for the lock, and from this Project's own pick: the active Project
        // may be another one by the time the request goes out.
        val cwd = cwdOverride ?: _state.value.worktreePickByProject[project.id] ?: project.path.orEmpty()
        try {
            val socket = terminalConnections.socket(server.id)
            val spawned = terminalsMutex.withLock {
                val reply = socket.request(
                    "spawn",
                buildJsonObject {
                    put("projectId", project.id)
                    put("cwd", cwd)
                    put("cols", 80)
                    put("rows", 24)
                },
                    ledgered = true,
                )
                if (!reply.success || reply.data == null) {
                    _state.update { it.copy(bannerMessage = reply.error ?: "Failed to start terminal.") }
                    return
                }
                // The Terminal pane attaches with this claim.
                TroupeJson.decodeFromJsonElement(SpawnedTerminal.serializer(), reply.data).also {
                    terminalCursors.putClaim(server.id, it.id, it.claim)
                }
            }
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

    /**
     * A snapshot restore, as in Troupe: closes the Project's terminals, then starts one per saved
     * working directory. In order, so a new Terminal never races the close of an old one.
     */
    fun replaceTerminals(cwds: List<String?>) {
        val server = _state.value.activeServer ?: return
        val project = _state.value.activeProject ?: return
        if (_state.value.viewOnly) {
            _state.update { it.copy(bannerMessage = "Server is in View-only mode.") }
            return
        }
        scope.launch {
            // This Project's own list, asked for now: the one in state can still be the previous
            // Project's just after a switch, and closing those would hit the wrong Project.
            val existing = refreshProjectTerminals(project.id)
            if (existing == null) {
                _state.update { it.copy(bannerMessage = "Could not list this Project's terminals.") }
                return@launch
            }
            // A Terminal that will not close stops the restore: its banner stays, nothing is added.
            if (existing.any { !killTerminalSuspend(server, project.id, it.id) }) return@launch
            cwds.forEach { spawnTerminalSuspend(server, project, it) }
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

    private suspend fun detachActiveSession() = sessionMutex.withLock {
        pendingTransferWrites?.second?.abandon("Session detached")
        pendingTransferWrites = null
        val oldStore = activeStoreClient
        oldStore?.flushAll()
        sessionJob?.cancelAndJoin()
        sessionJob = null
        oldStore?.drainUnsentWrites()?.abandon("Session detached")
        terminalsWatchJob?.cancel()
        terminalsWatchJob = null
        appSettingsStore.detach()
        activeConnection = null
        activeWatchHub = null
        activeStoreClient = null
        activeAgentSetup = null
        explicitProjectSelection = false
        hydratedServerId.value = null
        parkedWorkspaces.clear()
        hydratedProjects.clear()
        _state.update {
            WorkspaceShellState(
                loading = false,
                servers = it.servers,
                attentionItemsByServer = it.attentionItemsByServer,
            )
        }
    }

    private suspend fun attachToServer(server: PairedServer, rebindingSameServer: Boolean = false) = sessionMutex.withLock {
        attachToServerLocked(server, rebindingSameServer)
    }

    private suspend fun reattachIfClosed(serverId: String, closedConn: ChatConnection) = sessionMutex.withLock {
        val currentActive = _state.value.activeServer ?: return@withLock
        if (currentActive.id != serverId || activeConnection !== closedConn) return@withLock
        attachToServerLocked(currentActive, rebindingSameServer = true)
    }

    private suspend fun attachToServerLocked(server: PairedServer, rebindingSameServer: Boolean) {
        val oldStore = activeStoreClient
        val carriedWrites: WebStoreClient.UnsentWrites?
        if (rebindingSameServer) {
            // The previous connection was already invalidated/stopped; cancel its session scope first
            // and drain unsent writes without attempting I/O on the dead socket.
            sessionJob?.cancelAndJoin()
            sessionJob = null
            terminalsWatchJob?.cancel()
            terminalsWatchJob = null
            val drained = oldStore?.drainUnsentWrites()
            val previous = pendingTransferWrites?.takeIf { it.first == server.id }?.second
            if (pendingTransferWrites != null && pendingTransferWrites?.first != server.id) {
                pendingTransferWrites?.second?.abandon("Switched server")
            }
            carriedWrites = when {
                previous != null && drained != null -> previous.mergeWith(drained)
                drained != null && !drained.isEmpty() -> drained
                else -> previous
            }
            pendingTransferWrites = carriedWrites?.takeUnless { it.isEmpty() }?.let { server.id to it }
        } else {
            if (pendingTransferWrites != null && pendingTransferWrites?.first != server.id) {
                pendingTransferWrites?.second?.abandon("Switched server")
                pendingTransferWrites = null
            }
            oldStore?.flushAll()
            sessionJob?.cancelAndJoin()
            sessionJob = null
            oldStore?.drainUnsentWrites()?.abandon("Switched server")
            terminalsWatchJob?.cancel()
            terminalsWatchJob = null
            carriedWrites = pendingTransferWrites?.takeIf { it.first == server.id }?.second
        }
        activeConnection = null
        activeWatchHub = null
        activeStoreClient = null
        activeAgentSetup = null
        if (!rebindingSameServer) {
            explicitProjectSelection = false
            parkedWorkspaces.clear()
        }
        hydratedServerId.value = null
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

        val writesToAdopt = carriedWrites?.takeUnless { it.isEmpty() }

        activeConnection = conn
        val deviceId = identityStore.get().deviceId
        val adopted = kotlinx.coroutines.CompletableDeferred<Unit>()

        sessionJob = scope.launch {
            val hub = conn.watchHub
            val store = WebStoreClient(conn, hub, deviceId, this)
            try {
                if (writesToAdopt != null) {
                    store.adoptUnsentWrites(writesToAdopt, retryImmediately = false)
                }
                activeWatchHub = hub
                activeStoreClient = store
            } finally {
                adopted.complete(Unit)
            }
            appSettingsStore.attach(server.id, store, this)

            val agentSetup = AgentSetup(conn.socket, store, this)
            activeAgentSetup = agentSetup
            launch { agentSetup.state.collect { setup -> _state.update { it.copy(agentSetup = setup) } } }

            // First run: no Projects, never completed, and this client may write.
            launch {
                combine(_state, appSettingsStore.settings, appSettingsStore.loaded(server.id)) { st, settings, loaded ->
                    shouldShowOnboarding(st.projectsLoaded, st.projects.size, loaded, settings.onboardingCompletedAt, st.viewOnly)
                }.distinctUntilChanged().collect { show -> _state.update { it.copy(onboarding = show) } }
            }

            // Rebind automatically if ChatConnections invalidates this connection (e.g. token update / re-pair).
            launch {
                conn.awaitClosed()
                if (activeConnection === conn && _state.value.activeServer?.id == server.id) {
                    scope.launch { reattachIfClosed(server.id, conn) }
                }
            }

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

            // Watch 5: terminals (live sync when terminals are spawned or killed on desktop/other clients)
            terminalsWatchJob = launch {
                hub.watch(WatchHub.TERMINALS).collect {
                    val pid = _state.value.activeProjectId
                    if (pid != null) {
                        refreshProjectTerminals(pid)
                    }
                }
            }

            // Flush any carried writes before hydrating device-scoped active project + shell layout
            launch {
                conn.generation.collect { gen ->
                    if (gen <= 0) return@collect
                    store.flushPendingRetries()
                    hydrateDeviceState(server.id, store, conn)
                    agentSetup.refresh()
                }
            }
        }
        adopted.await()
        if (pendingTransferWrites?.first == server.id) {
            pendingTransferWrites = null
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
            explicitProjectSelection && _state.value.activeProjectId != null &&
                (currentProjects.isEmpty() || currentProjects.any { it.id == _state.value.activeProjectId }) ->
                _state.value.activeProjectId
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
                val base = if (nextActive != null) parkedWorkspaces[nextActive] ?: PersistedWorkspace() else PersistedWorkspace()
                if (previousActiveId == null && cur.workspace.tabs.isNotEmpty()) {
                    var merged = base
                    for (t in cur.workspace.tabs) merged = merged.openOrFocus(t)
                    merged.copy(activeTabId = cur.workspace.activeTabId ?: merged.activeTabId).also {
                        if (nextActive != null) parkedWorkspaces[nextActive] = it
                    }
                } else {
                    base
                }
            } else {
                cur.workspace
            }
            cur.copy(
                loading = false,
                projectsLoaded = true,
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
        _state.update { cur ->
            cur.copy(sessionStates = applyChatActivityUpdate(cur.sessionStates, obj, reset))
        }
    }

    /** Reads [projectId]'s running Terminals into state; returns them, or null when they could not be read. */
    private suspend fun refreshProjectTerminals(projectId: String): List<PreservedTerminal>? {
        val server = _state.value.activeServer ?: return null
        if (_state.value.viewOnly) return null
        return try {
            val socket = terminalConnections.socket(server.id)
            val decoded = terminalsMutex.withLock {
                val reply = socket.request("list_preserved", buildJsonObject { put("projectId", projectId) })
                if (!reply.success || reply.data == null) return null
                // `list_preserved` reissues the claim of every Terminal that is not attached.
                TroupeJson.decodeFromJsonElement(PreservedTerminals.serializer(), reply.data).also { list ->
                    list.terminals.forEach { t -> t.claim?.let { terminalCursors.putClaim(server.id, t.id, it) } }
                }
            }
            if (_state.value.activeProjectId == projectId) {
                _state.update { it.copy(terminals = decoded.terminals) }
            }
            decoded.terminals
        } catch (_: IOException) {
            // Terminal socket reconnects in background.
            null
        }
    }
}

/**
 * Applies one `chat_activity` watch frame onto [current]. Supports both the canonical Troupe
 * Server wire shapes (`{sessions: {sid: {busy, needsYou, turnEnd}}}` on reset and flat
 * `{sessionId, busy, needsYou, turnEnd}` on delta) and legacy map shapes.
 */
internal fun applyChatActivityUpdate(
    current: Map<String, SessionLiveState>,
    obj: JsonObject,
    reset: Boolean,
): Map<String, SessionLiveState> {
    val next = if (reset) LinkedHashMap() else LinkedHashMap(current)
    val deltaSessionId = (obj["sessionId"] as? JsonPrimitive)?.contentOrNull
    if (deltaSessionId != null && obj["sessions"] !is JsonObject) {
        next[deltaSessionId] = classifyChatActivityRecord(obj)
        return next
    }
    val entries = (obj["sessions"] as? JsonObject) ?: obj
    for ((sid, el) in entries) {
        val info = el as? JsonObject ?: continue
        next[sid] = classifyChatActivityRecord(info)
    }
    return next
}

private fun classifyChatActivityRecord(info: JsonObject): SessionLiveState {
    val turnEndReason = (info["turnEnd"] as? JsonObject)?.get("stopReason")?.jsonPrimitive?.contentOrNull
    return when {
        info["needsYou"]?.jsonPrimitive?.booleanOrNull == true ||
            info["needsUser"]?.jsonPrimitive?.booleanOrNull == true ||
            info["pendingApproval"]?.jsonPrimitive?.booleanOrNull == true ||
            info["status"]?.jsonPrimitive?.contentOrNull == "needs_user" -> SessionLiveState.NeedsYou
        info["busy"]?.jsonPrimitive?.booleanOrNull == true ||
            info["turnActive"]?.jsonPrimitive?.booleanOrNull == true ||
            info["status"]?.jsonPrimitive?.contentOrNull == "running" -> SessionLiveState.Running
        info["status"]?.jsonPrimitive?.contentOrNull == "error" ||
            turnEndReason == "error" ||
            turnEndReason == "crashed" -> SessionLiveState.Error
        else -> SessionLiveState.Idle
    }
}

/**
 * Troupe's `onboarding-gate.ts`: first run shows only on a server with no Projects whose
 * `settings/app` was read and never recorded a completion, and only to a client that may write.
 */
fun shouldShowOnboarding(
    projectsLoaded: Boolean,
    projectCount: Int,
    settingsLoaded: Boolean,
    completedAt: String?,
    viewOnly: Boolean,
): Boolean = projectsLoaded && projectCount == 0 && settingsLoaded && completedAt == null && !viewOnly
