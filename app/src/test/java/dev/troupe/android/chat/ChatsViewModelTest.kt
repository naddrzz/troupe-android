package dev.troupe.android.chat

import dev.troupe.android.testing.FakeChatServer.Companion.summary
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Chats tab against the fake Troupe Server's `chat_history` watch. */
class ChatsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun open(): ChatsViewModel {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        return graph.chatsViewModel(serverId, "p1")
    }

    private fun ids(vm: ChatsViewModel) = vm.ui.value.sessions.map { it.id }

    @Test fun listsTheProjectsSessionsNewestFirstWithTitleAndLastActivity() {
        server.chat.history = listOf(
            summary("old", "p1", "Fix the login bug", lastActivityAt = 1_000),
            summary("other-project", "p2", "Elsewhere", lastActivityAt = 9_000),
            summary("new", "p1", "Add dark mode", lastActivityAt = 3_000),
            summary("untitled", "p1", null, lastActivityAt = 2_000),
        )

        val vm = open()

        eventually { !vm.ui.value.loading }
        assertEquals(
            listOf(
                SessionRow("new", "Add dark mode", 3_000),
                SessionRow("untitled", null, 2_000),
                SessionRow("old", "Fix the login bug", 1_000),
            ),
            vm.ui.value.sessions,
        )
        assertEquals("chat_history", server.chat.requests("watch").single().payloadString("watch"))
    }

    @Test fun anEmptyIndexShowsNoSessions() {
        val vm = open()

        eventually { !vm.ui.value.loading }
        assertEquals(emptyList<SessionRow>(), vm.ui.value.sessions)
        assertEquals(null, vm.ui.value.error)
    }

    @Test fun aDeltaRefetchesTheIndex() {
        server.chat.history = listOf(summary("a", "p1", "A", 1_000))
        val vm = open()
        eventually { ids(vm) == listOf("a") }

        server.chat.history = listOf(summary("a", "p1", "A", 1_000), summary("b", "p1", "B", 2_000))
        server.chat.historyChanged("b")

        eventually { ids(vm) == listOf("b", "a") }
        assertEquals(1, server.chat.requests("list_persisted_sessions").size)
        assertEquals(1, server.chat.requests("watch").size)
    }

    @Test fun aMissedDeltaWatchesAgainForFreshState() {
        server.chat.history = listOf(summary("a", "p1", "A", 1_000))
        val vm = open()
        eventually { ids(vm) == listOf("a") }

        server.chat.history = listOf(summary("b", "p1", "B", 2_000))
        server.chat.historyChanged("b", lost = true)
        server.chat.historyChanged("b")

        eventually { server.chat.requests("watch").size == 2 && ids(vm) == listOf("b") }
        assertEquals(0, server.chat.requests("list_persisted_sessions").size)
    }

    @Test fun aReconnectWatchesAgain() {
        server.chat.history = listOf(summary("a", "p1", "A", 1_000))
        val vm = open()
        eventually { ids(vm) == listOf("a") }

        server.chat.history = listOf(summary("a", "p1", "A", 1_000), summary("b", "p1", "B", 2_000))
        server.chat.closeAll(1011)

        eventually { server.chat.requests("watch").size == 2 && ids(vm) == listOf("b", "a") }
    }
}
