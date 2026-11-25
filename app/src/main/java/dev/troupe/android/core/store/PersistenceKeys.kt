package dev.troupe.android.core.store

/**
 * Exact Kotlin port of `src/shared/types/persistence.types.ts` (`PersistenceKeys` + `KEY_SCOPES`).
 * Partitioning rule:
 * - `SharedLww` (`shared/lww/<key>`): cross-device shared settings & catalogs merged per field by the host.
 * - `SharedCas` (`shared/cas/<key>`): cross-device whole-value compare-and-set records.
 * - `Device` (`device/<deviceId>/<key>`): device-isolated workspace/navigation/draft state so phone
 *   navigation never clobbers desktop split-pane layouts.
 */
object PersistenceKeys {
    fun terminals(projectId: String): String = "terminals/$projectId"
    fun snapshots(projectId: String): String = "snapshots/$projectId"
    const val WINDOW_STATE = "window-state"

    // Shared LWW settings & agent/MCP registries
    const val APP_SETTINGS = "settings/app"
    const val KEYBOARD_SHORTCUTS = "settings/keyboard-shortcuts"
    const val CONTEXT_BAR_SETTINGS = "settings/context-bar"
    const val TOC_SETTINGS = "settings/toc"
    const val PINNED_COMMANDS = "settings/pinned-commands"
    const val CUSTOM_AGENTS = "agents/custom"
    const val ACP_AGENTS = "acp/agents"
    const val ACP_AUTH_METHODS = "acp/auth-methods"
    const val ACP_MCP_SERVERS = "acp/mcp-servers"

    // Shared CAS
    const val SSH_PROFILES = "ssh/profiles"

    // Device-scoped keys
    const val RECENT_COMMANDS = "settings/recent-commands"
    fun commandHistory(projectId: String): String = "projects/$projectId/command-history"
    fun lastLaunchDraft(projectId: String): String = "last-launch-draft/$projectId"
    fun chatDraft(projectId: String, sessionId: String): String = "chat-draft/$projectId/$sessionId"
    fun githubChatLinks(projectId: String): String = "github/chat-links/$projectId"
    fun acpMentionRecents(partitionId: String): String = "acp/mention-recents/$partitionId"
    const val LAST_SELECTED_AGENT = "agents/last-selected"
    fun lastComposerOptions(configId: String): String = "agents/composer-options/$configId"
    fun agentModelCatalog(configId: String): String = "agents/model-catalog/$configId"
    fun agentRejectedOptionValues(configId: String): String = "agents/rejected-option-values/$configId"
    fun mobileFileExplorerFolder(projectId: String): String = "mobile-file-explorer/$projectId"
    const val ACTIVE_PROJECT = "active-project"
    const val PROJECT_PREFS = "project-prefs"
    fun workspace(projectId: String): String = "workspace/$projectId"
    const val SHELL_LAYOUT = "shell-layout"

    val SHARED_LWW_KEYS: Set<String> = setOf(
        APP_SETTINGS,
        KEYBOARD_SHORTCUTS,
        CONTEXT_BAR_SETTINGS,
        TOC_SETTINGS,
        PINNED_COMMANDS,
        CUSTOM_AGENTS,
        ACP_AGENTS,
        ACP_AUTH_METHODS,
        ACP_MCP_SERVERS,
    )

    val SHARED_CAS_KEYS: Set<String> = setOf(
        SSH_PROFILES,
    )

    enum class KeyScope { Device, SharedLww, SharedCas }

    fun scopeOf(logicalKey: String): KeyScope = when {
        logicalKey in SHARED_LWW_KEYS -> KeyScope.SharedLww
        logicalKey in SHARED_CAS_KEYS -> KeyScope.SharedCas
        else -> KeyScope.Device
    }

    /** Host-side store key: `shared/lww/<k>`, `shared/cas/<k>`, or `device/<deviceId>/<k>`. */
    fun scopedKey(logicalKey: String, deviceId: String): String = when (scopeOf(logicalKey)) {
        KeyScope.SharedLww -> "shared/lww/$logicalKey"
        KeyScope.SharedCas -> "shared/cas/$logicalKey"
        KeyScope.Device -> "device/$deviceId/$logicalKey"
    }
}
