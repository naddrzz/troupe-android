package dev.troupe.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.SheetSearchField
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.workspace.WorkspaceShellState

private data class PaletteAction(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val onRun: () -> Unit,
)

/**
 * Quick Command Palette sheet matching desktop/web Cmd+K:
 * Fuzzy search across shell actions, recent chats, terminals, and open editors.
 */
@Composable
fun CommandPaletteSheet(
    state: WorkspaceShellState,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onNewTerminal: () -> Unit,
    onOpenGitChanges: () -> Unit,
    onOpenGitHistory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    onOpenTerminal: (terminalId: String, title: String?) -> Unit,
    onOpenEditor: (filePath: String) -> Unit,
) {
    val palette = TroupeTheme.palette
    var query by remember { mutableStateOf("") }

    val actions = buildList {
        if (!state.viewOnly && state.activeProject != null) {
            add(PaletteAction("new-chat", stringResource(R.string.shell_new_chat), null, TroupeIcons.MessageSquarePlus, onNewChat))
            add(PaletteAction("new-term", stringResource(R.string.terminals_new), null, TroupeIcons.Terminal, onNewTerminal))
        }
        if (state.activeProject?.path != null) {
            add(PaletteAction("git-changes", stringResource(R.string.shell_git_changes), null, TroupeIcons.FileDiff, onOpenGitChanges))
            add(PaletteAction("git-history", stringResource(R.string.shell_git_history), null, TroupeIcons.History, onOpenGitHistory))
            add(PaletteAction("files", stringResource(R.string.shell_files), null, TroupeIcons.Folder, onOpenFiles))
        }
        if (state.activeProject != null) {
            add(PaletteAction("snapshots", stringResource(R.string.shell_snapshots), null, TroupeIcons.Camera, onOpenSnapshots))
            add(PaletteAction("automations", stringResource(R.string.shell_automations), null, TroupeIcons.CalendarClock, onOpenAutomations))
        }
        add(PaletteAction("projects", stringResource(R.string.projects_title), state.activeProject?.name, TroupeIcons.Server, onOpenProjects))
        add(PaletteAction("settings", stringResource(R.string.settings_title), null, TroupeIcons.Settings, onOpenSettings))

        state.sessions.take(10).forEach { s ->
            val title = s.title?.takeIf { it.isNotBlank() } ?: "Untitled chat"
            add(PaletteAction("chat-${s.sessionId}", title, "Chat", TroupeIcons.MessageSquare) { onOpenChat(s.sessionId, s.title) })
        }
        state.terminals.take(10).forEach { t ->
            val name = state.terminalNames[t.id] ?: t.shell.substringAfterLast('/')
            add(PaletteAction("term-${t.id}", name, t.cwd, TroupeIcons.Terminal) { onOpenTerminal(t.id, name) })
        }
        state.workspace.tabs
            .filterIsInstance<dev.troupe.android.workspace.WorkspaceTab.Editor>()
            .forEach { ed ->
                val name = ed.filePath.substringAfterLast('/')
                add(PaletteAction("ed-${ed.id}", name, ed.filePath, TroupeIcons.FileCode) { onOpenEditor(ed.filePath) })
            }
    }.filter {
        query.isBlank() ||
            it.title.contains(query, ignoreCase = true) ||
            (it.subtitle?.contains(query, ignoreCase = true) == true)
    }

    TroupeMobileSheet(
        title = stringResource(R.string.shell_command_palette),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.palette_search_hint),
            )
            SheetGroup {
                actions.forEachIndexed { idx, action ->
                    if (idx > 0) SheetRowDivider()
                    SheetRow(
                        title = action.title,
                        subtitle = action.subtitle,
                        leading = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = palette.mutedForeground,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            onDismiss()
                            action.onRun()
                        },
                    )
                }
            }
        }
    }
}
