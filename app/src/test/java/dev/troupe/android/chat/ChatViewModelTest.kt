package dev.troupe.android.chat

import dev.troupe.android.R
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.testing.FakeChatServer
import dev.troupe.android.testing.FakeChatServer.Companion.messageChunk
import dev.troupe.android.testing.FakeChatServer.Companion.permissionRequest
import dev.troupe.android.testing.FakeChatServer.Companion.planUpdate
import dev.troupe.android.testing.FakeChatServer.Companion.promptComplete
import dev.troupe.android.testing.FakeChatServer.Companion.questionRequest
import dev.troupe.android.testing.FakeChatServer.Companion.toolCall
import dev.troupe.android.testing.FakeChatServer.Companion.toolCallUpdate
import dev.troupe.android.testing.FakeChatServer.Companion.userPrompt
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An open Agent Chat against the fake Troupe Server's session logs. */
class ChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val chat get() = server.chat
    private lateinit var log: FakeChatServer.Session

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    /** Session s1 holding a prompt and the start of the agent's reply (seqs 1 and 2). */
    private fun open(): ChatViewModel {
        log = chat.session("s1")
        chat.append("s1", "user_prompt", userPrompt("s1", "Fix the bug"))
        chat.append("s1", "message_chunk", messageChunk("s1", "On it"))
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val vm = graph.chatViewModel(serverId, "s1")
        eventually { texts(vm) == listOf("Fix the bug", "On it") }
        return vm
    }

    private fun texts(vm: ChatViewModel) = vm.ui.value.items.map {
        when (it) {
            is ChatItem.UserPrompt -> it.text
            is ChatItem.AgentMessage -> it.text
            is ChatItem.ToolCall -> "${it.title}:${it.status}"
            is ChatItem.TurnComplete -> "done:${it.stopReason}"
        }
    }

    /** No duplicated or missing message: the transcript holds exactly [expected] and stays that way. */
    private fun assertSettles(vm: ChatViewModel, expected: List<String>) {
        eventually(message = { "transcript: ${texts(vm)}" }) { texts(vm) == expected }
        Thread.sleep(200)
        assertEquals(expected, texts(vm))
    }

    @Test fun opensFromTheStartAndRendersEveryKindOfEvent() {
        val vm = open()
        val subscribe = chat.requests("subscribe").single()
        assertEquals("s1", subscribe.payloadString("sessionId"))
        assertEquals(0, subscribe.payloadInt("lastSeq"))
        assertEquals(null, subscribe.payloadString("logEpoch"))

        chat.append("s1", "message_chunk", messageChunk("s1", ", reading the code."))
        chat.append("s1", "message_chunk", messageChunk("s1", "Where is it?", role = "thought", messageId = "msg-2"))
        chat.append("s1", "tool_call", toolCall("s1", "tc-1", "Read Login.kt"))
        chat.append("s1", "tool_call_update", toolCallUpdate("s1", "tc-1", "in_progress"))
        chat.append("s1", "tool_call_update", toolCallUpdate("s1", "tc-1", "completed"))
        chat.append("s1", "plan_update", planUpdate("s1", "Read the code" to "completed", "Fix the bug" to "in_progress"))
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?"))
        chat.append("s1", "prompt_complete", promptComplete("s1"))

        assertSettles(
            vm,
            listOf(
                "Fix the bug",
                "On it, reading the code.",
                "Where is it?",
                "Read Login.kt:completed",
                "done:end_turn",
            ),
        )
        assertEquals(true, (vm.ui.value.items[2] as ChatItem.AgentMessage).thought)
        assertEquals(listOf(PlanEntry("Read the code", "completed"), PlanEntry("Fix the bug", "in_progress")), vm.ui.value.plan)
        assertEquals(0, chat.requests("recover_session_snapshot").size)
    }

    @Test fun replayThatFollowsTheSubscribeReplyRendersTheSame() {
        chat.replayBeforeReply = false
        val vm = open()

        chat.append("s1", "prompt_complete", promptComplete("s1"))

        assertSettles(vm, listOf("Fix the bug", "On it", "done:end_turn"))
        assertEquals(0, chat.requests("recover_session_snapshot").size)
    }

    @Test fun aGapFrameRecoversFromASnapshotAndContinuesFromItsWatermark() {
        val vm = open()

        chat.append("s1", "message_chunk", messageChunk("s1", " and lost"), deliver = false)
        chat.gap("s1")
        eventually { chat.requests("recover_session_snapshot").size == 1 }
        chat.append("s1", "prompt_complete", promptComplete("s1"))

        assertSettles(vm, listOf("Fix the bug", "On it and lost", "done:end_turn"))
        assertEquals(1, chat.requests("recover_session_snapshot").size)
    }

    @Test fun aSkippedSeqRecoversFromASnapshot() {
        val vm = open()

        chat.append("s1", "message_chunk", messageChunk("s1", ", dropped"), deliver = false)
        chat.append("s1", "message_chunk", messageChunk("s1", " then this."))
        eventually { chat.requests("recover_session_snapshot").size == 1 }
        chat.append("s1", "prompt_complete", promptComplete("s1"))

        assertSettles(vm, listOf("Fix the bug", "On it, dropped then this.", "done:end_turn"))
        assertEquals(1, chat.requests("recover_session_snapshot").size)
    }

    @Test fun aReconnectResubscribesFromTheCursor() {
        val vm = open()

        chat.closeAll(1011)
        chat.append("s1", "message_chunk", messageChunk("s1", ", while you were away"))
        chat.append("s1", "prompt_complete", promptComplete("s1"))

        eventually { chat.requests("subscribe").size == 2 }
        val again = chat.requests("subscribe")[1]
        assertEquals(2, again.payloadInt("lastSeq"))
        assertEquals("ep-1", again.payloadString("logEpoch"))
        assertSettles(vm, listOf("Fix the bug", "On it, while you were away", "done:end_turn"))
        assertEquals(0, chat.requests("recover_session_snapshot").size)
    }

    @Test fun aStaleCursorRecoversFromASnapshot() {
        val vm = open()

        chat.closeAll(1011)
        // The host restarted and its log was reborn under a new epoch.
        log.logEpoch = "ep-2"
        chat.append("s1", "prompt_complete", promptComplete("s1"))

        eventually { chat.requests("recover_session_snapshot").size == 1 }
        assertSettles(vm, listOf("Fix the bug", "On it", "done:end_turn"))
        chat.append("s1", "user_prompt", userPrompt("s1", "Thanks", turnId = "turn-2"))
        assertSettles(vm, listOf("Fix the bug", "On it", "done:end_turn", "Thanks"))
        assertEquals("ep-1", chat.requests("subscribe")[1].payloadString("logEpoch"))
    }

    @Test fun anUnknownSessionSaysTheChatIsUnavailable() {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val vm = graph.chatViewModel(serverId, "gone")

        eventually { vm.ui.value.error != null }
        assertEquals(UiMessage(R.string.chat_unavailable), vm.ui.value.error)
    }
}
