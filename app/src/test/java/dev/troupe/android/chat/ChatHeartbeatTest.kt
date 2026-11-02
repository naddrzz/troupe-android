package dev.troupe.android.chat

import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.testing.FakeChatServer.Companion.userPrompt
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `/ws` liveness on virtual time: the host's 10 s heartbeats keep the connection, 25 s of silence reconnects it. */
class ChatHeartbeatTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root, StandardTestDispatcher(scheduler)) }

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun until(condition: () -> Boolean) = eventually {
        scheduler.runCurrent()
        condition()
    }

    private fun advance(by: Duration) {
        scheduler.advanceTimeBy(by)
        scheduler.runCurrent()
    }

    @Test fun heartbeatsKeepTheChatConnectedAndSilenceResubscribes() {
        server.chat.session("s1")
        server.chat.append("s1", "user_prompt", userPrompt("s1", "Hi"))
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val vm = graph.chatViewModel(serverId, "s1")
        until { vm.connection.value == ConnectionState.Connected && vm.ui.value.items.size == 1 }

        repeat(6) {
            advance(10.seconds)
            server.chat.heartbeat()
            Thread.sleep(100)
            scheduler.runCurrent()
        }
        assertEquals(1, server.chat.upgrades.get())

        advance(25.seconds)
        until { vm.connection.value is ConnectionState.Reconnecting }
        advance(500.milliseconds)

        until { server.chat.requests("subscribe").size == 2 && vm.connection.value == ConnectionState.Connected }
        assertEquals(2, server.chat.upgrades.get())
        assertEquals(1, vm.ui.value.items.size)
    }
}
