package dev.troupe.android.attention

import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.testing.FakeChatServer.Companion.permissionRequest
import dev.troupe.android.testing.FakeChatServer.Companion.promptComplete
import dev.troupe.android.testing.FakeChatServer.Companion.questionCancelled
import dev.troupe.android.testing.FakeChatServer.Companion.questionRequest
import dev.troupe.android.testing.FakeChatServer.Companion.summary
import dev.troupe.android.testing.FakeChatServer.Companion.toolCallUpdate
import dev.troupe.android.testing.FakeChatServer.Companion.userPrompt
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Deriving the Attention list from the fake Troupe Server's index and session events, chats open or not. */
class AttentionTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val chat get() = server.chat
    private lateinit var serverId: String

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    /** A live Session `s1` ("Fix login", Project troupe) with a running turn. */
    private fun liveSession(sid: String = "s1", title: String = "Fix login", turnActive: Boolean = false) {
        chat.session(sid)
        chat.append(sid, "user_prompt", userPrompt(sid, "Fix the bug"))
        chat.history += summary(sid, "p-home", title, lastActivityAt = 5_000, status = "active", turnActive = turnActive)
    }

    private fun start() {
        serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        graph.attention.start()
        eventually { graph.attention.servers.value[serverId]?.ready == true }
    }

    private fun items() = graph.attention.servers.value[serverId]?.items.orEmpty()
    private fun keys() = items().map { it.key }

    @Test fun aPermissionRequestInAChatThatIsNotOpenIsARowWithProjectSessionKindAndAge() {
        liveSession()
        start()
        graph.now = 2_000_000L

        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))

        eventually { keys() == listOf("req-1") }
        assertEquals(
            AttentionItem(serverId, "req-1", "s1", projectName = "troupe", sessionTitle = "Fix login", AttentionKind.Permission, since = 2_000_000L),
            items().single(),
        )
    }

    @Test fun onlyLiveSessionsAreSubscribedAndWithoutReplayWhenNoTurnIsOpen() {
        liveSession("live")
        chat.session("old")
        chat.history += summary("old", "p-home", "Old chat", lastActivityAt = 1_000, status = "closed")

        start()

        eventually { chat.requests("subscribe").isNotEmpty() }
        val subscribe = chat.requests("subscribe").single()
        assertEquals("live", subscribe.payloadString("sessionId"))
        assertFalse("live-only: no cursor, no replay", "lastSeq" in subscribe.getValue("payload").jsonObject)
    }

    @Test fun aQuestionAskedBeforeTheAppConnectedIsReplayedWhenATurnIsOpen() {
        liveSession(turnActive = true)
        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?"))

        start()

        eventually { keys() == listOf("q-1") }
        assertEquals(AttentionKind.Question, items().single().kind)
        assertEquals("0", chat.requests("subscribe").single().payloadString("lastSeq"))
    }

    @Test fun aPermissionAlreadyPendingComesFromTheSubscribeReply() {
        liveSession()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))

        start()

        eventually { keys() == listOf("req-1") }
    }

    @Test fun aStoppedChatIsARowAgedByItsLastActivity() {
        chat.history = listOf(summary("s9", "p-zeta", "Crashed run", lastActivityAt = 7_000, status = "error"))

        start()

        eventually { keys() == listOf("stopped:s9") }
        assertEquals(
            AttentionItem(serverId, "stopped:s9", "s9", "zeta", "Crashed run", AttentionKind.Stopped, since = 7_000),
            items().single(),
        )
    }

    @Test fun aPermissionAnsweredOnAnotherDeviceLeavesWhenItsToolCallRuns() {
        liveSession()
        start()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { keys() == listOf("req-1") }

        chat.answerElsewhere("req-1")
        chat.append("s1", "tool_call_update", toolCallUpdate("s1", "tc-req-1", "in_progress"))

        eventually { keys().isEmpty() }
    }

    @Test fun aQuestionLeavesWhenCancelledAndEverythingLeavesWhenTheTurnEnds() {
        liveSession()
        start()
        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?"))
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { keys().toSet() == setOf("q-1", "req-1") }

        chat.append("s1", "question_cancelled", questionCancelled("s1", "q-1"))
        eventually { keys() == listOf("req-1") }

        chat.append("s1", "prompt_complete", promptComplete("s1"))
        eventually { keys().isEmpty() }
    }

    @Test fun aReconnectResumesFromTheCursorAndTakesTheHostsPendingList() {
        liveSession()
        start()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        chat.append("s1", "permission_request", permissionRequest("s1", "req-2", "Run tests"))
        eventually { keys().toSet() == setOf("req-1", "req-2") }

        // Answered elsewhere while this connection drops: no event says so, the next subscribe does.
        chat.answerElsewhere("req-1")
        chat.closeAll(1011)

        eventually { chat.requests("subscribe").size == 2 }
        val resume = chat.requests("subscribe").last()
        assertEquals("3", resume.payloadString("lastSeq"))
        assertEquals("ep-1", resume.payloadString("logEpoch"))
        eventually { keys() == listOf("req-2") }
    }

    @Test fun aSessionThatBecomesLiveIsSubscribedOnTheIndexDelta() {
        start()
        assertTrue(chat.requests("subscribe").isEmpty())

        liveSession()
        chat.historyChanged("s1")
        eventually { chat.requests("subscribe").size == 1 }
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))

        eventually { keys() == listOf("req-1") }
    }

    @Test fun anAnswerFromAnOpenAgentChatRemovesTheRowAtOnce() {
        liveSession()
        start()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { keys() == listOf("req-1") }
        val vm = graph.chatViewModel(serverId, "s1")
        eventually { vm.ui.value.requests.isNotEmpty() }

        vm.respond(vm.ui.value.requests.single() as PendingRequest.Permission, "allow")

        eventually { keys().isEmpty() }
    }

    @Test fun removingTheServerDropsItsAttention() {
        chat.history = listOf(summary("s9", "p-zeta", "Crashed run", lastActivityAt = 7_000, status = "error"))
        start()
        eventually { keys().isNotEmpty() }

        runBlocking { graph.repository.remove(serverId) }

        eventually { graph.attention.servers.value[serverId] == null }
    }

    @Test fun theProjectsScreenListsTheServersAttention() {
        chat.history = listOf(summary("s9", "p-zeta", "Crashed run", lastActivityAt = 7_000, status = "error"))
        start()
        val vm = graph.projectsViewModel(serverId)

        eventually { vm.attention.value.map { it.key } == listOf("stopped:s9") }
    }
}
