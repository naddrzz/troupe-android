package dev.troupe.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.troupe.android.R
import dev.troupe.android.agents.AgentSetupSheet
import dev.troupe.android.chat.ui.ChatStartView
import dev.troupe.android.chat.ui.EmbeddedChatPane
import dev.troupe.android.core.theme.AppearanceMode
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.files.CodeEditorPane
import dev.troupe.android.files.MobileFileExplorerSheet
import dev.troupe.android.git.GitChangesSheet
import dev.troupe.android.git.GitHistoryPane
import dev.troupe.android.github.GithubItemPane
import dev.troupe.android.github.MobileGithubDrawerSection
import dev.troupe.android.onboarding.OnboardingScreen
import dev.troupe.android.pairing.TokenGateScreen
import dev.troupe.android.snapshots.AutomationsPane
import dev.troupe.android.snapshots.SnapshotsSheet
import dev.troupe.android.terminal.EmbeddedTerminalPane
import dev.troupe.android.terminal.ui.MobileTerminalActionsSheet
import dev.troupe.android.workspace.WorkspaceTab
import dev.troupe.android.workspace.WorkspaceTabIds
import dev.troupe.android.workspace.mobileSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root Troupe Mobile Workspace Shell V2 screen, matching `MobileWorkspaceShell.tsx` + `MobileChatShell.tsx`:
 * - Persistent 5-slot `MobileShellHeader` (`☰` · Title/Subtitle · NeedsYouPill · `✎` · `⋯`).
 * - Active tab workspace pane (Chat Start Hero / Agent Chat V2 / Terminal V2 / Code Editor /
 *   Git History / GitHub Item / Automations).
 * - Full-screen `MobileShellDrawer` (4 sections + search + footer).
 * - Native bottom sheets: Project & Server Switcher, More Actions / Terminal Actions,
 *   Git Changes, Mobile File Explorer, Snapshots, Command Palette.
 * - Mid-session `TokenGateScreen` overlay if the server returns 401/unauthorized.
 */
@Composable
fun MobileWorkspaceShellScreen(
    onOpenConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onAttentionSurfaceVisible: (Boolean) -> Unit = {},
    viewModel: WorkspaceShellViewModel = hiltViewModel(),
) {
    val state by viewModel.session.state.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.settings.collectAsStateWithLifecycle()
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    val backStack = rememberOverlayBackStack()
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerSectionOverride by remember { mutableStateOf<dev.troupe.android.workspace.MobileSection?>(null) }
    var drawerCurrentSection by remember { mutableStateOf(dev.troupe.android.workspace.MobileSection.Chats) }
    var projectSheetOpen by remember { mutableStateOf(false) }
    var projectSheetOnRoot by remember { mutableStateOf(true) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var gitSheetOpen by remember { mutableStateOf(false) }
    var filesSheetOpen by remember { mutableStateOf(false) }
    var snapshotsSheetOpen by remember { mutableStateOf(false) }
    var paletteSheetOpen by remember { mutableStateOf(false) }
    var agentsSheetOpen by remember { mutableStateOf(false) }
    // With no Project, "New chat" opens the launcher as a quick chat instead of the empty state.
    var quickLauncher by rememberSaveable { mutableStateOf(false) }
    val scope = viewModel.viewModelScope

    val server = state.activeServer
    val tokenGateVisible = state.tokenGateRequired && server != null
    val attentionSurfaceVisible = !tokenGateVisible && (
        (drawerOpen && drawerCurrentSection == dev.troupe.android.workspace.MobileSection.Chats) ||
            (projectSheetOpen && projectSheetOnRoot)
        )

    androidx.compose.runtime.LaunchedEffect(attentionSurfaceVisible) {
        onAttentionSurfaceVisible(attentionSurfaceVisible)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onAttentionSurfaceVisible(false) }
    }

    backStack.Register("drawer", drawerOpen) { drawerOpen = false }
    backStack.Register("projects", projectSheetOpen) { projectSheetOpen = false }
    backStack.Register("more", moreSheetOpen) { moreSheetOpen = false }
    backStack.Register("git", gitSheetOpen) { gitSheetOpen = false }
    backStack.Register("files", filesSheetOpen) { filesSheetOpen = false }
    backStack.Register("snapshots", snapshotsSheetOpen) { snapshotsSheetOpen = false }
    backStack.Register("palette", paletteSheetOpen) { paletteSheetOpen = false }
    backStack.Register("agents", agentsSheetOpen) { agentsSheetOpen = false }

    // Mid-session 401 / unauthorized -> surface 1:1 TokenGateScreen immediately
    if (tokenGateVisible && server != null) {
        TokenGateScreen(
            serverHost = server.address.host,
            rejected = state.tokenGateRejected,
            checking = state.tokenGateChecking,
            onSubmitToken = viewModel.session::submitGateToken,
            onSwitchServer = onOpenConnect,
        )
        return
    }

    val activeProject = state.activeProject

    // First run on a fresh server, as in Troupe: agents, then a Project, then the first message.
    if (state.onboarding && server != null) {
        val systemDark = isSystemInDarkTheme()
        OnboardingScreen(
            state = state,
            darkTheme = when (settings.resolvedAppearance()) {
                AppearanceMode.Dark -> true
                AppearanceMode.Light -> false
                else -> systemDark
            },
            projectApi = viewModel.projectApi,
            tokenProvider = viewModel.session::currentToken,
            onSetDarkTheme = { dark ->
                viewModel.appSettings.update(scope) { it.copy(appearanceMode = if (dark) "dark" else "light") }
            },
            onInstallAgent = { agent -> scope.launch { viewModel.session.currentAgentSetup()?.install(agent) } },
            onCheckAgents = { scope.launch { viewModel.session.currentAgentSetup()?.refresh() } },
            onCreateProject = viewModel.session::createProject,
            onFinish = { picked, projectCreated ->
                if (picked != null) {
                    scope.launch {
                        viewModel.session.currentAgentSetup()?.let { if (it.save(picked)) it.select(picked.id) }
                    }
                }
                // Troupe lands in the Project's launcher, in a quick chat when only an agent was
                // picked, and in the empty workspace when neither was.
                quickLauncher = picked != null && !projectCreated
                viewModel.session.finishOnboarding()
            },
        )
        return
    }

    // Choosing a Project leaves the quick launcher: its launcher is the Project's from then on.
    LaunchedEffect(state.activeProjectId) {
        if (state.activeProjectId != null) quickLauncher = false
    }

    val activeTab = state.workspace.activeTab
    val showLauncher = activeProject != null || quickLauncher
    // "New chat" starts in the Project when there is one; the drawer's "+" under Quick chats never does.
    val newChat = {
        quickLauncher = activeProject == null
        viewModel.session.startNewChat()
    }
    val newQuickChat = {
        quickLauncher = true
        viewModel.session.startNewChat()
    }
    val launcherProject = activeProject.takeUnless { quickLauncher }
    val activeQuickChat = (activeTab as? WorkspaceTab.AgentChat)?.let { tab ->
        state.sessions.firstOrNull { it.sessionId == tab.sessionId }?.isQuick
    } ?: (activeTab == null && quickLauncher)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val isTerminal = activeTab is WorkspaceTab.Terminal
    val activeCwd = state.activeWorktreePath ?: activeProject?.path

    val headerTitle = when (activeTab) {
        is WorkspaceTab.AgentChat -> activeTab.title?.takeIf { it.isNotBlank() }
            ?: state.sessions.firstOrNull { it.sessionId == activeTab.sessionId }?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.chat_title)
        is WorkspaceTab.Terminal -> activeTab.title
            ?: state.terminalNames[activeTab.terminalId]
            ?: stringResource(R.string.terminal_title)
        is WorkspaceTab.Editor -> activeTab.filePath.substringAfterLast('/')
        is WorkspaceTab.GitHistory -> stringResource(R.string.shell_git_history)
        is WorkspaceTab.GithubItem -> "#${activeTab.number} ${activeTab.title}"
        is WorkspaceTab.Automations -> stringResource(R.string.shell_automations)
        // Troupe: "New chat" names the quick launcher only; a Project's launcher is just "Troupe".
        else -> stringResource(if (launcherProject == null && showLauncher) R.string.shell_new_chat else R.string.app_name)
    }

    // Troupe's `describeProjectSubtitle`: the Project, plus the worktree folder when in one.
    val headerSubtitle = when {
        activeQuickChat -> stringResource(R.string.shell_quick_chat)
        activeProject == null -> stringResource(R.string.shell_no_project)
        activeCwd != null && activeProject.path != null && activeCwd != activeProject.path ->
            "${activeProject.name} · ${activeCwd.trimEnd('/').substringAfterLast('/')}"
        else -> activeProject.name
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MobileShellHeader(
                title = headerTitle,
                subtitle = headerSubtitle,
                attentionCount = state.otherAttentionCount(),
                isTerminal = isTerminal,
                canNewChat = !state.viewOnly,
                connectionState = state.connectionState,
                viewOnly = state.viewOnly,
                onOpenDrawer = {
                    val initial = activeTab.mobileSection()
                    drawerSectionOverride = null
                    drawerCurrentSection = initial
                    drawerOpen = true
                },
                onOpenAttention = {
                    drawerSectionOverride = dev.troupe.android.workspace.MobileSection.Chats
                    drawerCurrentSection = dev.troupe.android.workspace.MobileSection.Chats
                    drawerOpen = true
                },
                onOpenProjectSheet = {
                    projectSheetOnRoot = true
                    projectSheetOpen = true
                },
                onNewChat = newChat,
                onNewTerminal = { viewModel.session.spawnTerminal() },
                onOpenMore = { moreSheetOpen = true },
                onRetryConnection = viewModel.session::retryConnection,
            )

            // Main workspace pane
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when {
                    state.loading && server == null -> {
                        TroupeSpinner(size = 24.dp, modifier = Modifier.align(Alignment.Center))
                    }
                    server == null -> {
                        Text(
                            text = stringResource(R.string.servers_empty_body),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }
                    activeTab is WorkspaceTab.AgentChat -> {
                        EmbeddedChatPane(
                            serverId = server.id,
                            sessionId = activeTab.sessionId,
                            agents = state.agents,
                            connections = viewModel.chatConnections,
                            viewOnlyRegistry = viewModel.viewOnlyRegistry,
                            onOpenGitChanges = if (activeCwd != null) ({ gitSheetOpen = true }) else null,
                            onOpenFiles = if (activeCwd != null) ({ filesSheetOpen = true }) else null,
                        )
                    }
                    activeTab is WorkspaceTab.Terminal -> {
                        EmbeddedTerminalPane(
                            serverId = server.id,
                            terminalId = activeTab.terminalId,
                            fontSizePx = settings.terminalFontSize,
                            connections = viewModel.terminalConnections,
                            cursors = viewModel.terminalCursors,
                            onMetadataChanged = { cwd, branch, _ ->
                                viewModel.session.updateTerminalMetadata(activeTab.terminalId, cwd, branch)
                            },
                        )
                    }
                    activeTab is WorkspaceTab.Editor -> {
                        CodeEditorPane(
                            server = server,
                            filePath = activeTab.filePath,
                            viewOnly = state.viewOnly,
                            filesApi = viewModel.filesApi,
                            tokenProvider = viewModel.session::currentToken,
                        )
                    }
                    activeTab is WorkspaceTab.GitHistory -> {
                        GitHistoryPane(
                            server = server,
                            cwd = activeTab.cwd,
                            gitApi = viewModel.gitApi,
                            tokenProvider = viewModel.session::currentToken,
                        )
                    }
                    activeTab is WorkspaceTab.GithubItem -> {
                        GithubItemPane(
                            server = server,
                            cwd = activeTab.cwd,
                            repo = activeTab.repo,
                            kind = activeTab.kind,
                            number = activeTab.number,
                            viewOnly = state.viewOnly,
                            githubApi = viewModel.githubApi,
                            tokenProvider = viewModel.session::currentToken,
                        )
                    }
                    activeTab is WorkspaceTab.Automations -> {
                        AutomationsPane(
                            server = server,
                            projectId = activeTab.projectId,
                            viewOnly = state.viewOnly,
                            automationsApi = viewModel.automationsApi,
                            tokenProvider = viewModel.session::currentToken,
                        )
                    }
                    !showLauncher -> {
                        NoProjectsView(
                            canCreate = !state.viewOnly,
                            onCreate = {
                                projectSheetOnRoot = true
                                projectSheetOpen = true
                            },
                        )
                    }
                    else -> {
                        ChatStartView(
                            server = server,
                            project = launcherProject,
                            activeWorktreePath = activeCwd.takeIf { launcherProject != null },
                            agentSetup = state.agentSetup,
                            viewOnly = state.viewOnly,
                            connection = viewModel.session.currentConnection(),
                            worktreeApi = viewModel.worktreeApi,
                            tokenProvider = viewModel.session::currentToken,
                            onSelectWorktree = viewModel.session::setWorktreePick,
                            onSelectAgent = { id -> viewModel.session.currentAgentSetup()?.select(id) },
                            onSaveAgent = { config -> viewModel.session.currentAgentSetup()?.save(config) ?: false },
                            onSetUpAgents = { agentsSheetOpen = true },
                            onSessionCreated = { sid, title -> viewModel.session.openChat(sid, title) },
                            onOpenFiles = if (activeCwd != null) ({ filesSheetOpen = true }) else null,
                        )
                    }
                }
            }
        }

        state.bannerMessage?.let { message ->
            LaunchedEffect(message) {
                delay(5_000)
                viewModel.session.dismissBanner()
            }
            Text(
                text = message,
                style = typo.bodyMedium,
                color = palette.foreground,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.popover)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.shell_dismiss),
                        onClick = viewModel.session::dismissBanner,
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }

        // Full-screen Drawer overlay
        if (drawerOpen) {
            MobileShellDrawer(
                state = state,
                initialSection = drawerSectionOverride ?: activeTab.mobileSection(),
                githubContent = { closeDrawer ->
                    MobileGithubDrawerSection(
                        server = server,
                        cwd = activeCwd,
                        githubApi = viewModel.githubApi,
                        tokenProvider = viewModel.session::currentToken,
                        onOpenItem = { item ->
                            closeDrawer()
                            if (activeCwd != null) {
                                viewModel.session.openTab(
                                    WorkspaceTab.GithubItem(
                                        id = WorkspaceTabIds.githubItem(activeCwd, "origin", item.number),
                                        cwd = activeCwd,
                                        repo = "origin",
                                        kind = item.kind,
                                        number = item.number,
                                        title = item.title,
                                    ),
                                )
                            }
                        },
                    )
                },
                onDismiss = { drawerOpen = false },
                onOpenProjects = {
                    projectSheetOnRoot = true
                    projectSheetOpen = true
                },
                onOpenChat = { sid, title, projectId -> viewModel.session.openChat(sid, title, projectId) },
                onNewChat = newChat,
                onNewQuickChat = newQuickChat,
                onOpenTerminal = { tid, label ->
                    viewModel.session.openTab(
                        WorkspaceTab.Terminal(
                            id = WorkspaceTabIds.terminal(tid),
                            terminalId = tid,
                            title = label,
                        ),
                    )
                },
                onNewTerminal = { viewModel.session.spawnTerminal() },
                onKillTerminal = viewModel.session::killTerminal,
                onOpenEditor = { path ->
                    viewModel.session.openTab(WorkspaceTab.Editor(WorkspaceTabIds.editor(path), path))
                },
                onCloseEditor = viewModel.session::closeTab,
                onOpenFiles = { filesSheetOpen = true },
                onOpenSettings = onOpenSettings,
                onOpenSnapshots = { snapshotsSheetOpen = true },
                onOpenGitHistory = {
                    if (activeCwd != null) {
                        viewModel.session.openTab(WorkspaceTab.GitHistory(WorkspaceTabIds.gitHistory(activeCwd), activeCwd))
                    }
                },
                onOpenAutomations = {
                    if (activeProject != null) {
                        viewModel.session.openTab(WorkspaceTab.Automations(WorkspaceTabIds.automations(activeProject.id), activeProject.id))
                    }
                },
                onSectionChanged = { drawerCurrentSection = it },
            )
        }
    }

    // Bottom sheets
    if (projectSheetOpen) {
        ProjectAndServerSheet(
            state = state,
            projectApi = viewModel.projectApi,
            tokenProvider = viewModel.session::currentToken,
            onDismiss = { projectSheetOpen = false },
            onSelectProject = viewModel.session::selectProject,
            onSetDefaultProject = viewModel.session::setDefaultProject,
            onCreateProject = viewModel.session::createProject,
            onSelectServer = viewModel.session::selectServer,
            onAddServer = onOpenConnect,
            onOpenChat = { sid, title, projectId -> viewModel.session.openChat(sid, title, projectId) },
            onShowingProjectsRoot = { projectSheetOnRoot = it },
        )
    }

    if (moreSheetOpen) {
        if (activeTab is WorkspaceTab.Terminal) {
            MobileTerminalActionsSheet(
                name = headerTitle,
                lastExitCode = null,
                viewOnly = state.viewOnly,
                hasProjectPath = activeCwd != null,
                onDismiss = { moreSheetOpen = false },
                onRename = { newName -> viewModel.session.renameTerminal(activeTab.terminalId, newName) },
                onRestart = { viewModel.session.restartTerminal(activeTab.terminalId) },
                onOpenGitChanges = { gitSheetOpen = true },
                onOpenFiles = { filesSheetOpen = true },
                onOpenCommandPalette = { paletteSheetOpen = true },
                onOpenSettings = onOpenSettings,
                onKill = { viewModel.session.killTerminal(activeTab.terminalId) },
            )
        } else {
            MobileHeaderMoreSheet(
                title = headerTitle,
                subtitle = headerSubtitle,
                hasProjectPath = activeCwd != null,
                isChatTab = activeTab is WorkspaceTab.AgentChat,
                hasActiveTab = activeTab != null,
                onDismiss = { moreSheetOpen = false },
                onOpenGitChanges = { gitSheetOpen = true },
                onOpenFiles = { filesSheetOpen = true },
                onOpenCommandPalette = { paletteSheetOpen = true },
                onNewTerminal = { viewModel.session.spawnTerminal() }.takeIf { activeProject != null && !state.viewOnly },
                onReportBug = { uriHandler.openUri("https://github.com/naddrzz/troupe-android/issues/new") },
                onReloadChat = if (activeTab is WorkspaceTab.AgentChat) {
                    { viewModel.session.openChat(activeTab.sessionId, activeTab.title) }
                } else null,
                onCloseTab = activeTab?.let { tab -> { viewModel.session.closeTab(tab.id) } },
            )
        }
    }

    if (gitSheetOpen && server != null && activeCwd != null) {
        GitChangesSheet(
            server = server,
            cwd = activeCwd,
            viewOnly = state.viewOnly,
            gitApi = viewModel.gitApi,
            tokenProvider = viewModel.session::currentToken,
            onDismiss = { gitSheetOpen = false },
        )
    }

    if (filesSheetOpen && server != null && activeCwd != null) {
        MobileFileExplorerSheet(
            server = server,
            rootPath = activeCwd,
            viewOnly = state.viewOnly,
            filesApi = viewModel.filesApi,
            tokenProvider = viewModel.session::currentToken,
            onDismiss = { filesSheetOpen = false },
            onOpenFile = { path ->
                viewModel.session.openTab(WorkspaceTab.Editor(WorkspaceTabIds.editor(path), path))
            },
        )
    }

    if (snapshotsSheetOpen && activeProject != null) {
        SnapshotsSheet(
            projectId = activeProject.id,
            terminals = state.terminals,
            storeClient = viewModel.session.currentStoreClient(),
            viewOnly = state.viewOnly,
            onDismiss = { snapshotsSheetOpen = false },
            onRestoreSnapshot = { snap ->
                viewModel.session.replaceTerminals(snap.terminals.map { it.cwd })
            },
        )
    }

    if (agentsSheetOpen) {
        AgentSetupSheet(
            state = state.agentSetup,
            viewOnly = state.viewOnly,
            onSelect = { config ->
                scope.launch { viewModel.session.currentAgentSetup()?.let { if (it.save(config)) it.select(config.id) } }
            },
            onInstall = { agent -> scope.launch { viewModel.session.currentAgentSetup()?.install(agent) } },
            onCheckAgain = { scope.launch { viewModel.session.currentAgentSetup()?.refresh() } },
            onDismiss = { agentsSheetOpen = false },
        )
    }

    if (paletteSheetOpen) {
        CommandPaletteSheet(
            state = state,
            onDismiss = { paletteSheetOpen = false },
            onNewChat = newChat,
            onNewTerminal = { viewModel.session.spawnTerminal() },
            onOpenGitChanges = { gitSheetOpen = true },
            onOpenGitHistory = {
                if (activeCwd != null) {
                    viewModel.session.openTab(WorkspaceTab.GitHistory(WorkspaceTabIds.gitHistory(activeCwd), activeCwd))
                }
            },
            onOpenFiles = { filesSheetOpen = true },
            onOpenSnapshots = { snapshotsSheetOpen = true },
            onOpenAutomations = {
                if (activeProject != null) {
                    viewModel.session.openTab(WorkspaceTab.Automations(WorkspaceTabIds.automations(activeProject.id), activeProject.id))
                }
            },
            onOpenProjects = {
                projectSheetOnRoot = true
                projectSheetOpen = true
            },
            onOpenSettings = onOpenSettings,
            onOpenChat = { sid, title -> viewModel.session.openChat(sid, title) },
            onOpenTerminal = { tid, label ->
                viewModel.session.openTab(WorkspaceTab.Terminal(WorkspaceTabIds.terminal(tid), tid, label))
            },
            onOpenEditor = { path ->
                viewModel.session.openTab(WorkspaceTab.Editor(WorkspaceTabIds.editor(path), path))
            },
        )
    }
}

/** Troupe's empty workspace (`WorkspaceMain.tsx`): shown after first run while the server has no Project. */
@Composable
private fun NoProjectsView(canCreate: Boolean, onCreate: () -> Unit) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(TroupeIcons.Folder, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(40.dp))
        Text(text = stringResource(R.string.shell_no_projects_title), style = typo.titleLarge, color = palette.foreground)
        Text(
            text = stringResource(R.string.shell_no_projects_body),
            style = typo.bodyMedium,
            color = palette.mutedForeground,
            textAlign = TextAlign.Center,
        )
        if (canCreate) {
            TroupeButton(text = stringResource(R.string.shell_no_projects_action), onClick = onCreate)
        }
    }
}
