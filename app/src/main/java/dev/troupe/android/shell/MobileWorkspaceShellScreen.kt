package dev.troupe.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.chat.ui.ChatStartView
import dev.troupe.android.chat.ui.EmbeddedChatPane
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.files.CodeEditorPane
import dev.troupe.android.files.MobileFileExplorerSheet
import dev.troupe.android.git.GitChangesSheet
import dev.troupe.android.git.GitHistoryPane
import dev.troupe.android.github.GithubItemPane
import dev.troupe.android.github.MobileGithubDrawerSection
import dev.troupe.android.pairing.TokenGateScreen
import dev.troupe.android.snapshots.AutomationsPane
import dev.troupe.android.snapshots.SnapshotsSheet
import dev.troupe.android.terminal.EmbeddedTerminalPane
import dev.troupe.android.terminal.ui.MobileTerminalActionsSheet
import dev.troupe.android.workspace.WorkspaceTab
import dev.troupe.android.workspace.WorkspaceTabIds
import dev.troupe.android.workspace.mobileSection

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
    var projectSheetOpen by remember { mutableStateOf(false) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var gitSheetOpen by remember { mutableStateOf(false) }
    var filesSheetOpen by remember { mutableStateOf(false) }
    var snapshotsSheetOpen by remember { mutableStateOf(false) }
    var paletteSheetOpen by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(drawerOpen, projectSheetOpen) {
        onAttentionSurfaceVisible(drawerOpen || projectSheetOpen)
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

    // Mid-session 401 / unauthorized -> surface 1:1 TokenGateScreen immediately
    val server = state.activeServer
    if (state.tokenGateRequired && server != null) {
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
    val activeTab = state.workspace.activeTab
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
        else -> stringResource(R.string.shell_new_chat)
    }

    val worktreeLabel = if (activeProject?.path != null && activeCwd != null && activeCwd != activeProject.path) {
        stringResource(R.string.worktree_badge)
    } else {
        stringResource(R.string.worktree_local)
    }
    val headerSubtitle = buildString {
        append(activeProject?.name ?: server?.name ?: stringResource(R.string.app_name))
        if (activeProject != null) {
            append(" · ")
            append(worktreeLabel)
        }
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
                canNewChat = activeProject != null && !state.viewOnly,
                connectionState = state.connectionState,
                viewOnly = state.viewOnly,
                onOpenDrawer = {
                    drawerSectionOverride = null
                    drawerOpen = true
                },
                onOpenAttention = {
                    drawerSectionOverride = dev.troupe.android.workspace.MobileSection.Chats
                    drawerOpen = true
                },
                onOpenProjectSheet = { projectSheetOpen = true },
                onNewChat = { viewModel.session.startNewChat() },
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
                    else -> {
                        ChatStartView(
                            server = server,
                            project = activeProject,
                            activeWorktreePath = activeCwd,
                            agents = state.agents,
                            viewOnly = state.viewOnly,
                            connection = viewModel.session.currentConnection(),
                            worktreeApi = viewModel.worktreeApi,
                            tokenProvider = viewModel.session::currentToken,
                            onSelectWorktree = viewModel.session::setWorktreePick,
                            onSessionCreated = { sid, title -> viewModel.session.openChat(sid, title) },
                            onOpenFiles = if (activeCwd != null) ({ filesSheetOpen = true }) else null,
                        )
                    }
                }
            }
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
                onOpenProjects = { projectSheetOpen = true },
                onOpenChat = { sid, title -> viewModel.session.openChat(sid, title) },
                onNewChat = { viewModel.session.startNewChat() },
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
            onOpenChat = { sid, title -> viewModel.session.openChat(sid, title) },
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
                onOpenSettings = onOpenSettings,
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
                snap.terminals.forEach { t -> viewModel.session.spawnTerminal(cwdOverride = t.cwd) }
            },
        )
    }

    if (paletteSheetOpen) {
        CommandPaletteSheet(
            state = state,
            onDismiss = { paletteSheetOpen = false },
            onNewChat = { viewModel.session.startNewChat() },
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
            onOpenProjects = { projectSheetOpen = true },
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
