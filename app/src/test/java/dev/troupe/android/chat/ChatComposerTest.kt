package dev.troupe.android.chat

import dev.troupe.android.R
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.testing.FakeChatServer.Companion.messageChunk
import dev.troupe.android.testing.FakeChatServer.Companion.promptComplete
import dev.troupe.android.testing.FakeChatServer.Companion.userPrompt
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prompting and cancelling an agent from an open Agent Chat, against the fake Troupe Server. */
class ChatComposerTest {
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

    /** Session s1 whose agent already finished one turn. */
    private fun open(): ChatViewModel {
        chat.session("s1")
        chat.append("s1", "user_prompt", userPrompt("s1", "Fix the bug"))
        chat.append("s1", "message_chunk", messageChunk("s1", "Done."))
        chat.append("s1", "prompt_complete", promptComplete("s1"))
        serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val vm = graph.chatViewModel(serverId, "s1")
        eventually { vm.ui.value.items.size == 3 }
        return vm
    }

    private fun prompts(vm: ChatViewModel) = vm.ui.value.items.filterIsInstance<ChatItem.UserPrompt>().map { it.text }

    private fun send(vm: ChatViewModel, text: String) {
        vm.onDraftChange(text)
        vm.send()
    }

    @Test fun sendSendsAPromptUnderAFreshTurnIdAndShowsItOnceFromTheRecord() {
        val vm = open()

        send(vm, "Now add a test")

        eventually { prompts(vm) == listOf("Fix the bug", "Now add a test") }
        val request = chat.requests("send_prompt").single()
        val turnId = request.payloadString("turnId")!!
        assertEquals(turnId, request["command_id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", request.payloadString("agentId"))
        assertEquals("s1", request.payloadString("sessionId"))
        val block = request.getValue("payload").jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("text", block.getValue("type").jsonPrimitive.content)
        assertEquals("Now add a test", block.getValue("text").jsonPrimitive.content)
        assertEquals("", vm.draft.value)
        assertTrue(vm.ui.value.turnRunning)

        send(vm, "And another")
        chat.completeTurn("s1")
        eventually { !vm.ui.value.turnRunning }
        send(vm, "Thanks")

        eventually { chat.requests("send_prompt").size == 2 }
        assertTrue(chat.requests("send_prompt")[1].payloadString("turnId") != turnId)
        eventually { prompts(vm) == listOf("Fix the bug", "Now add a test", "Thanks") }
    }

    @Test fun aPromptResentAfterAReconnectAppearsOnce() {
        val vm = open()

        send(vm, "Now add a test")
        eventually { prompts(vm).size == 2 }
        chat.closeAll(1011)

        eventually { chat.requests("send_prompt").size == 2 }
        val (first, again) = chat.requests("send_prompt")
        assertEquals(first["command_id"], again["command_id"])
        assertEquals(first.payloadString("turnId"), again.payloadString("turnId"))
        chat.completeTurn("s1")
        eventually { !vm.ui.value.turnRunning }
        Thread.sleep(200)
        assertEquals(listOf("Fix the bug", "Now add a test"), prompts(vm))
        assertEquals(null, vm.ui.value.notice)
    }

    @Test fun whileATurnRunsCancelSendsCancelPromptAndTheTurnEndsOnPromptComplete() {
        val vm = open()
        assertFalse(vm.ui.value.turnRunning)

        send(vm, "Refactor everything")
        eventually { vm.ui.value.turnRunning && prompts(vm).size == 2 }
        vm.cancel()

        eventually { !vm.ui.value.turnRunning }
        val cancel = chat.requests("cancel_prompt").single()
        assertEquals("agent-1", cancel.payloadString("agentId"))
        assertEquals("s1", cancel.payloadString("sessionId"))
        assertEquals(ChatItem.TurnComplete::class, vm.ui.value.items.last()::class)
        assertEquals("cancelled", (vm.ui.value.items.last() as ChatItem.TurnComplete).stopReason)
    }

    @Test fun aTurnRunningOnAnotherDeviceShowsCancel() {
        val vm = open()

        chat.startTurnElsewhere("s1", "From my laptop")

        eventually { vm.ui.value.turnRunning }
        chat.completeTurn("s1")
        eventually { !vm.ui.value.turnRunning }
    }

    @Test fun aTurnInProgressReplySaysTheAgentIsStillWorking() {
        val vm = open()
        // The other device's turn starts after this one sent its prompt, as a race the host decides.
        chat.sessions.getValue("s1").runningTurn = "turn-elsewhere"

        send(vm, "Are you there?")

        eventually { vm.ui.value.notice != null }
        assertEquals(UiMessage(R.string.chat_agent_busy), vm.ui.value.notice)
        assertEquals("Are you there?", vm.draft.value)
        assertEquals(listOf("Fix the bug"), prompts(vm))
    }

    @Test fun aViewOnlyServerGetsNoPrompt() {
        val vm = open()
        graph.viewOnly.set(serverId, true)

        send(vm, "Hello")
        Thread.sleep(200)

        assertTrue(vm.viewOnly.value)
        assertEquals(0, chat.requests("send_prompt").size)
    }

    @Test fun aForbiddenRefusalMakesTheServerViewOnly() {
        val vm = open()
        server.allowRemoteWrites = false

        send(vm, "Hello")

        eventually { vm.viewOnly.value }
        assertEquals("Hello", vm.draft.value)
    }
}
