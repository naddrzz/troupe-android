package dev.troupe.android.core.store

import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.workspace.MobileSection
import dev.troupe.android.workspace.PersistedWorkspace
import dev.troupe.android.workspace.WorkspaceTab
import dev.troupe.android.workspace.WorkspaceTabIds
import dev.troupe.android.workspace.mobileSection
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistenceAndWorkspaceTest {

    @Test
    fun persistenceKeysPartitionSharedLwwSharedCasAndDevice() {
        assertEquals("shared/lww/settings/app", PersistenceKeys.scopedKey(PersistenceKeys.APP_SETTINGS, "dev-1"))
        assertEquals("shared/lww/acp/agents", PersistenceKeys.scopedKey(PersistenceKeys.ACP_AGENTS, "dev-1"))
        assertEquals("shared/cas/ssh/profiles", PersistenceKeys.scopedKey(PersistenceKeys.SSH_PROFILES, "dev-1"))
        assertEquals("device/dev-1/active-project", PersistenceKeys.scopedKey(PersistenceKeys.ACTIVE_PROJECT, "dev-1"))
        assertEquals("device/dev-1/workspace/proj-9", PersistenceKeys.scopedKey(PersistenceKeys.workspace("proj-9"), "dev-1"))
        assertEquals("device/dev-1/shell-layout", PersistenceKeys.scopedKey(PersistenceKeys.SHELL_LAYOUT, "dev-1"))
    }

    @Test
    fun webStoreEnvelopeWrapsAndUnwrapsCleanly() {
        val raw = JsonPrimitive("hello")
        val wrapped = WebStoreClient.wrap(raw)
        assertEquals(raw, WebStoreClient.unwrap(wrapped))
        assertEquals(raw, WebStoreClient.unwrap(raw))
    }

    @Test
    fun persistedWorkspaceOpensFocusesClosesAndSerializesAllTabTypes() {
        val chat = WorkspaceTab.AgentChat(WorkspaceTabIds.chat("s1"), "s1", "Fix login")
        val term = WorkspaceTab.Terminal(WorkspaceTabIds.terminal("t1"), "t1", "zsh")
        val ed = WorkspaceTab.Editor(WorkspaceTabIds.editor("/repo/a.kt"), "/repo/a.kt")
        val gh = WorkspaceTab.GithubItem(WorkspaceTabIds.githubItem("/repo", "origin", 42), "/repo", "origin", "pr", 42, "Parity PR")

        var ws = PersistedWorkspace()
            .openOrFocus(chat)
            .openOrFocus(term)
            .openOrFocus(ed)
            .openOrFocus(gh)

        assertEquals(4, ws.tabs.size)
        assertEquals(gh.id, ws.activeTabId)
        assertEquals(MobileSection.Github, ws.activeTab.mobileSection())

        // Round-trip serialization
        val encoded = TroupeJson.encodeToString(PersistedWorkspace.serializer(), ws)
        val decoded = TroupeJson.decodeFromString(PersistedWorkspace.serializer(), encoded)
        assertEquals(ws, decoded)

        // Closing active tab selects previous neighbor
        ws = ws.closeTab(gh.id)
        assertEquals(ed.id, ws.activeTabId)
        assertEquals(MobileSection.Editors, ws.activeTab.mobileSection())

        ws = ws.closeTab(ed.id).closeTab(term.id).closeTab(chat.id)
        assertNull(ws.activeTab)
    }
}
