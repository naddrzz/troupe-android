package dev.troupe.android.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Mobile drawer sections matching `hooks/use-mobile-section.ts`.
 */
enum class MobileSection {
    Chats,
    Terminals,
    Editors,
    Github,
}

/**
 * All 11 Troupe workspace tab types (`src/renderer/stores/workspace-store/types.ts`).
 * Serialized with `type` discriminator matching desktop & browser persistence 1:1.
 */
@Serializable
sealed interface WorkspaceTab {
    val id: String

    @Serializable
    @SerialName("agent-chat")
    data class AgentChat(
        override val id: String,
        val sessionId: String,
        val title: String? = null,
        val mountKey: String? = null,
    ) : WorkspaceTab

    @Serializable
    @SerialName("terminal")
    data class Terminal(
        override val id: String,
        val terminalId: String,
        val title: String? = null,
    ) : WorkspaceTab

    @Serializable
    @SerialName("editor")
    data class Editor(
        override val id: String,
        val filePath: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("browser")
    data class Browser(
        override val id: String,
        val browserTabId: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("git")
    data class Git(
        override val id: String,
        val cwd: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("git-history")
    data class GitHistory(
        override val id: String,
        val cwd: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("github-item")
    data class GithubItem(
        override val id: String,
        val cwd: String,
        val repo: String = "origin",
        val kind: String = "issue",
        val number: Int,
        val title: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("diff")
    data class Diff(
        override val id: String,
        val cwd: String,
        val path: String,
        val staged: Boolean = false,
    ) : WorkspaceTab

    @Serializable
    @SerialName("commit-diff")
    data class CommitDiff(
        override val id: String,
        val cwd: String,
        val commit: String,
        val path: String,
        val oldPath: String? = null,
    ) : WorkspaceTab

    @Serializable
    @SerialName("canvas")
    data class Canvas(
        override val id: String,
        val projectId: String,
        val docPath: String,
    ) : WorkspaceTab

    @Serializable
    @SerialName("automations")
    data class Automations(
        override val id: String,
        val projectId: String,
    ) : WorkspaceTab
}

@Serializable
data class PersistedWorkspace(
    val tabs: List<WorkspaceTab> = emptyList(),
    val activeTabId: String? = null,
) {
    val activeTab: WorkspaceTab?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()

    fun openOrFocus(tab: WorkspaceTab): PersistedWorkspace {
        val existing = tabs.firstOrNull { it.id == tab.id }
        val nextTabs = if (existing != null) {
            tabs.map { if (it.id == tab.id) tab else it }
        } else {
            tabs + tab
        }
        return copy(tabs = nextTabs, activeTabId = tab.id)
    }

    fun closeTab(tabId: String): PersistedWorkspace {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return this
        val nextTabs = tabs.filterNot { it.id == tabId }
        val nextActive = if (activeTabId == tabId) {
            nextTabs.getOrNull((index - 1).coerceAtLeast(0))?.id
        } else {
            activeTabId
        }
        return copy(tabs = nextTabs, activeTabId = nextActive)
    }

    fun focusTab(tabId: String): PersistedWorkspace =
        if (tabs.any { it.id == tabId }) copy(activeTabId = tabId) else this
}

@Serializable
data class ShellLayoutRecord(
    val version: Int = 1,
    val worktreePickByProject: Map<String, String> = emptyMap(),
)

/** Deterministic tab IDs matching `stores/workspace-store/tab-ids.ts`. */
object WorkspaceTabIds {
    fun chat(sessionId: String): String = "chat-$sessionId"
    fun terminal(terminalId: String): String = "term-$terminalId"
    fun editor(filePath: String): String = "editor-$filePath"
    fun gitHistory(cwd: String): String = "git-history-$cwd"
    fun githubItem(cwd: String, repo: String, number: Int): String = "github-$cwd-$repo-$number"
    fun diff(cwd: String, path: String, staged: Boolean): String = "diff-$cwd-$path-$staged"
    fun commitDiff(cwd: String, commit: String, path: String): String = "commit-diff-$cwd-$commit-$path"
    fun automations(projectId: String): String = "automations-$projectId"
    fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"
}

fun WorkspaceTab?.mobileSection(): MobileSection = when (this) {
    is WorkspaceTab.Terminal -> MobileSection.Terminals
    is WorkspaceTab.Editor -> MobileSection.Editors
    is WorkspaceTab.GithubItem -> MobileSection.Github
    else -> MobileSection.Chats
}
