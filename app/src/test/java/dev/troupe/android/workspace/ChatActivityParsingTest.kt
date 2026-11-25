package dev.troupe.android.workspace

import dev.troupe.android.attention.AttentionItem
import dev.troupe.android.attention.AttentionKind
import dev.troupe.android.core.protocol.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies Troupe Server `chat_activity` wire parsing and server-wide Attention counts (#12). */
class ChatActivityParsingTest {

    @Test fun parsesCanonicalTroupeServerResetAndFlatDeltaFrames() {
        val resetPayload = buildJsonObject {
            putJsonObject("sessions") {
                putJsonObject("s1") {
                    put("busy", true)
                    put("needsYou", false)
                    put("unread", false)
                }
                putJsonObject("s2") {
                    put("busy", false)
                    put("needsYou", true)
                    put("unread", true)
                }
            }
        }
        val afterReset = applyChatActivityUpdate(emptyMap(), resetPayload, reset = true)
        assertEquals(
            mapOf(
                "s1" to SessionLiveState.Running,
                "s2" to SessionLiveState.NeedsYou,
            ),
            afterReset,
        )

        // Real Troupe Server flat delta: `{sessionId, busy, needsYou, turnEnd, unread}`
        val deltaNeedsYou = buildJsonObject {
            put("sessionId", "s1")
            put("busy", false)
            put("needsYou", true)
            put("unread", false)
        }
        val afterDelta1 = applyChatActivityUpdate(afterReset, deltaNeedsYou, reset = false)
        assertEquals(SessionLiveState.NeedsYou, afterDelta1["s1"])
        assertEquals(SessionLiveState.NeedsYou, afterDelta1["s2"])

        // Answered elsewhere -> needsYou flips false, busy resumes
        val deltaAnswered = buildJsonObject {
            put("sessionId", "s2")
            put("busy", true)
            put("needsYou", false)
            put("unread", false)
        }
        val afterDelta2 = applyChatActivityUpdate(afterDelta1, deltaAnswered, reset = false)
        assertEquals(SessionLiveState.NeedsYou, afterDelta2["s1"])
        assertEquals(SessionLiveState.Running, afterDelta2["s2"])

        // Turn ended with error stopReason -> Error state
        val deltaError = buildJsonObject {
            put("sessionId", "s2")
            put("busy", false)
            put("needsYou", false)
            putJsonObject("turnEnd") { put("stopReason", "error") }
        }
        val afterDelta3 = applyChatActivityUpdate(afterDelta2, deltaError, reset = false)
        assertEquals(SessionLiveState.Error, afterDelta3["s2"])
    }

    @Test fun otherAttentionCountIncludesOtherProjectsAndExcludesVisibleChat() {
        val state = WorkspaceShellState(
            projects = listOf(
                Project(id = "p1", name = "Alpha"),
                Project(id = "p2", name = "Beta"),
            ),
            activeProjectId = "p1",
            workspace = PersistedWorkspace(
                tabs = listOf(WorkspaceTab.AgentChat(WorkspaceTabIds.chat("s1"), "s1", "Current")),
                activeTabId = WorkspaceTabIds.chat("s1"),
            ),
            attentionItems = listOf(
                AttentionItem("srv", "req-1", "s1", "Alpha", "Current", AttentionKind.Permission, 100L),
                AttentionItem("srv", "req-2", "s2", "Beta", "Other project chat", AttentionKind.Question, 200L),
            ),
            sessionStates = mapOf(
                "s3" to SessionLiveState.NeedsYou,
            ),
        )

        // s1 is visible on screen (excluded); s2 (in Project Beta) and s3 (from chat_activity) are counted.
        assertEquals(2, state.otherAttentionCount())
    }
}
