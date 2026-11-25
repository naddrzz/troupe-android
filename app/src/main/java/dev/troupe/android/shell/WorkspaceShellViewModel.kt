package dev.troupe.android.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.files.FilesApi
import dev.troupe.android.git.GitApi
import dev.troupe.android.github.GithubApi
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.snapshots.AutomationsApi
import dev.troupe.android.terminal.TerminalCursorStore
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.workspace.AppSettingsStore
import dev.troupe.android.workspace.ProjectApi
import dev.troupe.android.workspace.WorkspaceSessionManager
import dev.troupe.android.worktree.WorktreeApi
import javax.inject.Inject

/**
 * Hilt ViewModel bridging [WorkspaceSessionManager] and all V2 feature APIs to
 * [MobileWorkspaceShellScreen] and [dev.troupe.android.lock.SettingsScreen].
 */
@HiltViewModel
class WorkspaceShellViewModel @Inject constructor(
    val session: WorkspaceSessionManager,
    val appSettings: AppSettingsStore,
    val repository: PairedServerRepository,
    val projectApi: ProjectApi,
    val worktreeApi: WorktreeApi,
    val filesApi: FilesApi,
    val gitApi: GitApi,
    val githubApi: GithubApi,
    val automationsApi: AutomationsApi,
    val chatConnections: ChatConnections,
    val terminalConnections: TerminalConnections,
    val terminalCursors: TerminalCursorStore,
    val viewOnlyRegistry: ViewOnlyRegistry,
) : ViewModel() {
    init {
        session.ensureStarted()
    }
}
