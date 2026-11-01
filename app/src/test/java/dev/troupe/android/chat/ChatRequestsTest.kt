package dev.troupe.android.chat

import dev.troupe.android.R
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.testing.FakeChatServer.Companion.permissionRequest
import dev.troupe.android.testing.FakeChatServer.Companion.promptComplete
import dev.troupe.android.testing.FakeChatServer.Companion.questionCancelled
import dev.troupe.android.testing.FakeChatServer.Companion.questionRequest
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Answering an agent's permission requests and questions from an open Agent Chat, against the fake Troupe Server. */
class ChatRequestsTest {
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

    /** Session s1 with a running turn; [before] runs before the chat opens. */
    private fun open(before: () -> Unit = {}): ChatViewModel {
        chat.session("s1")
        chat.append("s1", "user_prompt", userPrompt("s1", "Fix the bug"))
        before()
        serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val vm = graph.chatViewModel(serverId, "s1")
        eventually { vm.ui.value.items.isNotEmpty() }
        return vm
    }

    private fun ids(vm: ChatViewModel) = vm.ui.value.requests.map { it.id }

    /** What the server's chat connection exposes to every screen, the Attention list included. */
    private fun serverPending() = graph.chatConnections.pending(serverId).bySession.value

    @Test fun aPermissionRequestIsACardWhoseOptionIsSentAndResolvesIt() {
        val vm = open()

        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))

        eventually { ids(vm) == listOf("req-1") }
        val card = vm.ui.value.requests.single() as PendingRequest.Permission
        assertEquals("Edit Login.kt", card.title)
        assertEquals(listOf("allow" to "Allow", "deny" to "Deny"), card.options.map { it.optionId to it.name })
        assertEquals(listOf("req-1"), serverPending()["s1"]?.map { it.id })

        vm.respond(card, "allow")

        eventually { ids(vm).isEmpty() }
        val sent = chat.requests("respond_permission").single()
        assertEquals("req-1", sent.payloadString("requestId"))
        assertEquals("allow", sent.payloadString("optionId"))
        assertEquals("agent-1", sent.payloadString("agentId"))
        assertTrue(!sent["command_id"]?.jsonPrimitive?.content.isNullOrEmpty())
        assertEquals(null, serverPending()["s1"])
        assertEquals(null, vm.ui.value.notice)
    }

    @Test fun aQuestionIsAnsweredWithTheChosenValue() {
        val vm = open()

        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?", listOf("main" to "Main", "dev" to "Develop")))

        eventually { ids(vm) == listOf("q-1") }
        val card = vm.ui.value.requests.single() as PendingRequest.Question
        assertEquals("Which branch?", card.question)
        assertEquals(listOf("Main", "Develop"), card.options.map { it.label })
        assertEquals(false, card.allowFreeText)

        vm.answer(card, listOf("dev"))

        eventually { ids(vm).isEmpty() }
        val sent = chat.requests("answer_question").single()
        assertEquals("q-1", sent.payloadString("questionId"))
        assertEquals(listOf("dev"), sent.getValue("payload").jsonObject.getValue("values").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aFreeTextQuestionIsAnsweredWithTheText() {
        val vm = open()

        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Name the branch", allowFreeText = true))
        eventually { ids(vm) == listOf("q-1") }
        val card = vm.ui.value.requests.single() as PendingRequest.Question
        assertTrue(card.allowFreeText)

        vm.answer(card, listOf("fix/login"))

        eventually { ids(vm).isEmpty() }
        val values = chat.requests("answer_question").single().getValue("payload").jsonObject.getValue("values").jsonArray
        assertEquals(listOf("fix/login"), values.map { it.jsonPrimitive.content })
    }

    @Test fun questionCancelledRemovesTheCardAndTheTurnsEndReleasesTheRest() {
        val vm = open()

        chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?"))
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { ids(vm) == listOf("q-1", "req-1") }
        chat.append("s1", "question_cancelled", questionCancelled("s1", "q-1"))
        eventually { ids(vm) == listOf("req-1") }

        chat.append("s1", "prompt_complete", promptComplete("s1"))

        eventually { ids(vm).isEmpty() }
        assertEquals(null, serverPending()["s1"])
    }

    @Test fun anAnswerThatLostTheRaceSaysAnotherDeviceAnsweredAndResolvesTheCard() {
        val vm = open()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { ids(vm) == listOf("req-1") }
        chat.answerElsewhere("req-1")

        vm.respond(vm.ui.value.requests.single() as PendingRequest.Permission, "deny")

        eventually { ids(vm).isEmpty() }
        assertEquals(UiMessage(R.string.chat_already_answered), vm.ui.value.notice)
    }

    @Test fun requestsPendingWhenTheChatOpensAppearAndAnsweredOnesDoNot() {
        val vm = open {
            chat.append("s1", "permission_request", permissionRequest("s1", "req-old", "Read secrets"))
            chat.answerElsewhere("req-old")
            chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
            chat.append("s1", "question_request", questionRequest("s1", "q-1", "Which branch?"))
        }

        eventually { ids(vm).toSet() == setOf("req-1", "q-1") }
        Thread.sleep(200)
        assertEquals(setOf("req-1", "q-1"), ids(vm).toSet())
        assertEquals(setOf("req-1", "q-1"), serverPending()["s1"]?.map { it.id }?.toSet())
    }

    @Test fun aReconnectRestoresPendingPermissionsAndDropsOnesAnsweredMeanwhile() {
        val vm = open()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        chat.append("s1", "permission_request", permissionRequest("s1", "req-2", "Run tests"))
        eventually { ids(vm) == listOf("req-1", "req-2") }

        chat.closeAll(1011)
        chat.answerElsewhere("req-1")

        eventually { chat.requests("subscribe").size == 2 && ids(vm) == listOf("req-2") }
    }

    @Test fun onAViewOnlyServerTheCardsShowButSendNoAnswer() {
        val vm = open()
        graph.viewOnly.set(serverId, true)
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { ids(vm) == listOf("req-1") }

        vm.respond(vm.ui.value.requests.single() as PendingRequest.Permission, "allow")
        Thread.sleep(200)

        assertEquals(listOf("req-1"), ids(vm))
        assertEquals(0, chat.requests("respond_permission").size)
    }

    @Test fun aForbiddenAnswerMakesTheServerViewOnlyAndKeepsTheCard() {
        val vm = open()
        chat.append("s1", "permission_request", permissionRequest("s1", "req-1", "Edit Login.kt"))
        eventually { ids(vm) == listOf("req-1") }
        server.allowRemoteWrites = false

        vm.respond(vm.ui.value.requests.single() as PendingRequest.Permission, "allow")

        eventually { vm.viewOnly.value }
        assertEquals(listOf("req-1"), ids(vm))
    }
}
