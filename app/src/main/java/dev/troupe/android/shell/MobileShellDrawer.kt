package dev.troupe.android.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.chat.protocol.SessionSummary
import dev.troupe.android.core.theme.TroupeColors
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.CountBadge
import dev.troupe.android.core.ui.FadeEndText
import dev.troupe.android.core.ui.SheetSearchField
import dev.troupe.android.core.ui.StatusDot
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.workspace.MobileSection
import dev.troupe.android.workspace.SessionLiveState
import dev.troupe.android.workspace.WorkspaceShellState
import dev.troupe.android.workspace.WorkspaceTab

/**
 * 1:1 port of Troupe's `MobileShellDrawer.tsx`:
 * - Top bar: Project switcher button (colored dot + name + worktree/branch subtitle + `▾`) + `✕` close.
 * - 4 equal pill section tabs: Chats (with attention `CountBadge`) | Terminals | Editors | GitHub.
 * - Search bar (`44dp`, `16sp`).
 * - Single scrolling section list.
 * - Pinned footer: Settings, Snapshots, Git History, Automations + section primary pill
 *   (`New chat` / `New terminal` / `Browse files`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileShellDrawer(
    state: WorkspaceShellState,
    initialSection: MobileSection,
    githubContent: @Composable (onNavigate: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: (terminalId: String, title: String?) -> Unit,
    onNewTerminal: () -> Unit,
    onKillTerminal: (terminalId: String) -> Unit,
    onOpenEditor: (filePath: String) -> Unit,
    onCloseEditor: (tabId: String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenGitHistory: () -> Unit,
    onOpenAutomations: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    var query by remember { mutableStateOf("") }

    val activeProject = state.activeProject
    val activeTab = state.workspace.activeTab
    val activeSessionId = (activeTab as? WorkspaceTab.AgentChat)?.sessionId
    val attentionCount = state.otherAttentionCount()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background,
        contentColor = palette.foreground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top bar: Project switcher row + Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onOpenProjects()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(TroupeColors.project(activeProject?.color)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeProject?.name ?: stringResource(R.string.shell_no_project),
                            style = typo.titleMedium,
                            color = palette.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = state.activeServer?.name ?: ""
                        if (detail.isNotEmpty()) {
                            Text(
                                text = detail,
                                style = typo.bodySmall,
                                color = palette.mutedForeground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = TroupeIcons.ChevronDown,
                        contentDescription = null,
                        tint = palette.mutedForeground,
                        modifier = Modifier.size(16.dp),
                    )
                }

                TroupeIconButton(
                    icon = TroupeIcons.Close,
                    contentDescription = stringResource(R.string.shell_close_menu),
                    onClick = onDismiss,
                )
            }

            // 4-tab pill segmented control: Chats | Terminals | Editors | GitHub
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(CircleShape)
                    .background(palette.secondarySurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DrawerSectionTab(
                    icon = TroupeIcons.MessageSquare,
                    contentDescription = stringResource(R.string.project_tab_chats),
                    selected = section == MobileSection.Chats,
                    badge = if (attentionCount > 0) attentionCount else 0,
                    onClick = { section = MobileSection.Chats },
                    modifier = Modifier.weight(1f),
                )
                DrawerSectionTab(
                    icon = TroupeIcons.Terminal,
                    contentDescription = stringResource(R.string.project_tab_terminals),
                    selected = section == MobileSection.Terminals,
                    badge = 0,
                    onClick = { section = MobileSection.Terminals },
                    modifier = Modifier.weight(1f),
                )
                DrawerSectionTab(
                    icon = TroupeIcons.FileCode,
                    contentDescription = stringResource(R.string.shell_section_editors),
                    selected = section == MobileSection.Editors,
                    badge = 0,
                    onClick = { section = MobileSection.Editors },
                    modifier = Modifier.weight(1f),
                )
                DrawerSectionTab(
                    icon = TroupeIcons.Github,
                    contentDescription = stringResource(R.string.shell_section_github),
                    selected = section == MobileSection.Github,
                    badge = 0,
                    onClick = { section = MobileSection.Github },
                    modifier = Modifier.weight(1f),
                )
            }

            // Search box for Chats / Terminals / Editors
            if (section != MobileSection.Github) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    SheetSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = when (section) {
                            MobileSection.Chats -> stringResource(R.string.shell_search_chats)
                            MobileSection.Terminals -> stringResource(R.string.shell_search_terminals)
                            MobileSection.Editors -> stringResource(R.string.shell_search_editors)
                            MobileSection.Github -> ""
                        },
                    )
                }
            }

            // Main scrollable section list
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    MobileSection.Chats -> {
                        val filteredAttention = state.attentionItems.filter { item ->
                            query.isBlank() ||
                                item.sessionTitle.orEmpty().contains(query, ignoreCase = true) ||
                                item.projectName.orEmpty().contains(query, ignoreCase = true)
                        }
                        val projectSessions = state.sessions.filter { s ->
                            (activeProject == null || s.projectId == activeProject.id || s.projectId == null) &&
                                (query.isBlank() || (s.title ?: "Untitled chat").contains(query, ignoreCase = true))
                        }
                        if (filteredAttention.isEmpty() && projectSessions.isEmpty()) {
                            DrawerEmptyState(
                                text = if (query.isBlank()) {
                                    stringResource(R.string.shell_empty_chats)
                                } else {
                                    stringResource(R.string.shell_search_no_results)
                                },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 6.dp,
                                ),
                            ) {
                                if (filteredAttention.isNotEmpty()) {
                                    item(key = "attention_section") {
                                        dev.troupe.android.attention.AttentionSection(
                                            items = filteredAttention,
                                            onOpen = { item ->
                                                onDismiss()
                                                onOpenChat(item.sessionId, item.sessionTitle)
                                            },
                                            modifier = Modifier
                                                .padding(bottom = 8.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(0.5.dp, palette.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                                        )
                                    }
                                }
                                items(projectSessions, key = { it.sessionId }) { session ->
                                    ChatRecentsRow(
                                        session = session,
                                        isActive = session.sessionId == activeSessionId,
                                        liveState = state.sessionStates[session.sessionId]
                                            ?: when {
                                                state.attentionItems.any { it.sessionId == session.sessionId } -> SessionLiveState.NeedsYou
                                                session.turnActive == true -> SessionLiveState.Running
                                                session.status == "error" -> SessionLiveState.Error
                                                else -> SessionLiveState.Idle
                                            },
                                        onClick = {
                                            onDismiss()
                                            onOpenChat(session.sessionId, session.title)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    MobileSection.Terminals -> {
                        val terminals = state.terminals.filter { t ->
                            val label = state.terminalNames[t.id] ?: t.shell.substringAfterLast('/')
                            query.isBlank() || label.contains(query, ignoreCase = true) || t.cwd.contains(query, ignoreCase = true)
                        }
                        if (terminals.isEmpty()) {
                            DrawerEmptyState(
                                text = if (state.viewOnly) {
                                    stringResource(R.string.terminals_view_only)
                                } else {
                                    stringResource(R.string.terminals_empty)
                                },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 6.dp,
                                ),
                            ) {
                                items(terminals, key = { it.id }) { terminal ->
                                    val label = state.terminalNames[terminal.id] ?: terminal.shell.substringAfterLast('/')
                                    val isCurrent = (activeTab as? WorkspaceTab.Terminal)?.terminalId == terminal.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrent) palette.secondarySurface else androidx.compose.ui.graphics.Color.Transparent)
                                            .clickable {
                                                onDismiss()
                                                onOpenTerminal(terminal.id, label)
                                            }
                                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = TroupeIcons.Terminal,
                                            contentDescription = null,
                                            tint = palette.mutedForeground,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            FadeEndText(text = label, style = typo.bodyLarge, color = palette.foreground)
                                            FadeEndText(
                                                text = if (terminal.hasLiveAttachment) {
                                                    "${terminal.cwd} · ${stringResource(R.string.terminals_open_elsewhere)}"
                                                } else {
                                                    terminal.cwd
                                                },
                                                style = typo.bodySmall,
                                                color = palette.mutedForeground,
                                            )
                                        }
                                        if (!state.viewOnly) {
                                            var confirmKill by remember(terminal.id) { mutableStateOf(false) }
                                            TroupeIconButton(
                                                icon = TroupeIcons.Trash,
                                                contentDescription = stringResource(R.string.terminals_kill),
                                                onClick = { confirmKill = true },
                                                iconSize = 16.dp,
                                                contentColor = palette.mutedForeground,
                                            )
                                            if (confirmKill) {
                                                androidx.compose.material3.AlertDialog(
                                                    onDismissRequest = { confirmKill = false },
                                                    containerColor = palette.popover,
                                                    titleContentColor = palette.foreground,
                                                    textContentColor = palette.secondary,
                                                    title = { Text(stringResource(R.string.terminals_kill)) },
                                                    text = { Text(stringResource(R.string.terminals_kill_confirm)) },
                                                    confirmButton = {
                                                        androidx.compose.material3.TextButton(
                                                            onClick = {
                                                                confirmKill = false
                                                                onKillTerminal(terminal.id)
                                                            },
                                                        ) {
                                                            Text(stringResource(R.string.terminals_kill), color = palette.error)
                                                        }
                                                    },
                                                    dismissButton = {
                                                        androidx.compose.material3.TextButton(onClick = { confirmKill = false }) {
                                                            Text(stringResource(R.string.terminals_cancel), color = palette.foreground)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MobileSection.Editors -> {
                        val editors = state.workspace.tabs.filterIsInstance<WorkspaceTab.Editor>().filter { tab ->
                            query.isBlank() || tab.filePath.contains(query, ignoreCase = true)
                        }
                        if (editors.isEmpty()) {
                            DrawerEmptyState(text = stringResource(R.string.shell_empty_editors))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 6.dp,
                                ),
                            ) {
                                items(editors, key = { it.id }) { tab ->
                                    val isCurrent = activeTab?.id == tab.id
                                    val fileName = tab.filePath.substringAfterLast('/')
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrent) palette.secondarySurface else androidx.compose.ui.graphics.Color.Transparent)
                                            .clickable {
                                                onDismiss()
                                                onOpenEditor(tab.filePath)
                                            }
                                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = TroupeIcons.FileCode,
                                            contentDescription = null,
                                            tint = palette.mutedForeground,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            FadeEndText(text = fileName, style = typo.bodyLarge, color = palette.foreground)
                                            FadeEndText(text = tab.filePath, style = typo.bodySmall, color = palette.mutedForeground)
                                        }
                                        TroupeIconButton(
                                            icon = TroupeIcons.Close,
                                            contentDescription = stringResource(R.string.shell_close_tab),
                                            onClick = { onCloseEditor(tab.id) },
                                            iconSize = 16.dp,
                                            contentColor = palette.mutedForeground,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MobileSection.Github -> {
                        githubContent(onDismiss)
                    }
                }
            }

            // Pinned Drawer Footer: 4 utility icon buttons + section primary CTA pill
            HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TroupeIconButton(
                    icon = TroupeIcons.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
                TroupeIconButton(
                    icon = TroupeIcons.Camera,
                    contentDescription = stringResource(R.string.shell_snapshots),
                    onClick = {
                        onDismiss()
                        onOpenSnapshots()
                    },
                    enabled = activeProject != null,
                )
                TroupeIconButton(
                    icon = TroupeIcons.History,
                    contentDescription = stringResource(R.string.shell_git_history),
                    onClick = {
                        onDismiss()
                        onOpenGitHistory()
                    },
                    enabled = activeProject?.path != null,
                )
                TroupeIconButton(
                    icon = TroupeIcons.CalendarClock,
                    contentDescription = stringResource(R.string.shell_automations),
                    onClick = {
                        onDismiss()
                        onOpenAutomations()
                    },
                    enabled = activeProject != null,
                )

                Spacer(modifier = Modifier.weight(1f))

                when (section) {
                    MobileSection.Chats -> TroupeButton(
                        text = stringResource(R.string.shell_new_chat),
                        icon = TroupeIcons.Plus,
                        onClick = {
                            onDismiss()
                            onNewChat()
                        },
                        enabled = activeProject != null,
                        shape = CircleShape,
                    )
                    MobileSection.Terminals -> TroupeButton(
                        text = stringResource(R.string.terminals_new),
                        icon = TroupeIcons.Plus,
                        onClick = {
                            onDismiss()
                            onNewTerminal()
                        },
                        enabled = activeProject != null && !state.viewOnly,
                        shape = CircleShape,
                    )
                    MobileSection.Editors -> TroupeButton(
                        text = stringResource(R.string.shell_browse_files),
                        icon = TroupeIcons.Folder,
                        onClick = {
                            onDismiss()
                            onOpenFiles()
                        },
                        enabled = activeProject?.path != null,
                        shape = CircleShape,
                    )
                    MobileSection.Github -> Unit
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionTab(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (selected) palette.background else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) palette.foreground else palette.mutedForeground,
                modifier = Modifier.size(18.dp),
            )
            if (badge > 0) {
                CountBadge(count = badge, tone = palette.warning)
            }
        }
    }
}

@Composable
private fun ChatRecentsRow(
    session: SessionSummary,
    isActive: Boolean,
    liveState: SessionLiveState,
    onClick: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (isActive) palette.secondarySurface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FadeEndText(
            text = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chats_untitled),
            style = typo.bodyLarge,
            color = palette.foreground,
            modifier = Modifier.weight(1f),
        )
        when (liveState) {
            SessionLiveState.Running -> TroupeSpinner(size = 14.dp, color = palette.brandText)
            SessionLiveState.NeedsYou -> StatusDot(color = palette.warning)
            SessionLiveState.Error -> StatusDot(color = palette.error)
            SessionLiveState.Idle -> Unit
        }
    }
}

@Composable
private fun DrawerEmptyState(text: String) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typo.bodyMedium,
            color = palette.mutedForeground,
        )
    }
}
