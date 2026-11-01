package dev.troupe.android.integration

import dev.troupe.android.R
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.terminal.UiMessage
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * The chat socket `/ws` against a real Troupe Server: `scripts/test-server.ps1 up`, then
 * `gradlew :app:integrationTest`. The Docker server runs no agent, so its index may be empty;
 * this checks the handshake, the `chat_history` watch and the empty and unknown-session shapes.
 */
class RealChatServerTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val address = ServerAddress.of("http://127.0.0.1:18080/")
    private val graph by lazy { TestGraph(tmp.root) }

    @Before fun serverIsUp() {
        val health = runBlocking { graph.api.health(address) }
        assumeTrue("Troupe Server not running on $address; run scripts/test-server.ps1 up", health is ApiResult.Ok)
    }

    @After fun tearDown() = graph.close()

    private fun pair(token: String = TOKEN) = runBlocking { graph.repository.add("Docker", PairingInput(address, token)).id }

    @Test fun handshakeThenTheChatHistoryWatchSendsTheIndexBeforeItsReply() = runBlocking {
        val connection = graph.chatConnections.connection(pair())
        withTimeout(15_000) { connection.state.first { it == ConnectionState.Connected } }

        val stateFrame = async(start = CoroutineStart.UNDISPATCHED) {
            connection.socket.frames.filterIsInstance<ChatFrame.Watch>().first { it.watch == "chat_history" }
        }
        val reply = connection.socket.request("watch", buildJsonObject { put("watch", "chat_history") })
        assertTrue("watch: ${reply.code} ${reply.message}", reply.ok)
        assertTrue("the state frame arrives before the reply", stateFrame.isCompleted)
        val frame = stateFrame.await()
        assertTrue(frame.reset)
        assertEquals(1L, frame.n)
        assertTrue("state: ${frame.state}", frame.state is JsonArray)

        val index = connection.socket.request("list_persisted_sessions", JsonObject(emptyMap()))
        assertTrue("list_persisted_sessions: ${index.code} ${index.message}", index.ok && index.payload is JsonArray)
    }

    @Test fun theChatsTabOfAProjectWithoutSessionsIsEmpty() {
        val chats = graph.chatsViewModel(pair(), "troupe-it-" + UUID.randomUUID())
        val ui = runBlocking { withTimeout(15_000) { chats.ui.first { !it.loading } } }
        assertEquals(emptyList<Any>(), ui.sessions)
        assertEquals(null, ui.error)
    }

    @Test fun anUnknownSessionIsUnavailable() {
        val chat = graph.chatViewModel(pair(), "no-such-session-" + UUID.randomUUID())
        val ui = runBlocking { withTimeout(15_000) { chat.ui.first { it.error != null } } }
        assertEquals(UiMessage(R.string.chat_unavailable), ui.error)
    }

    @Test fun aWrongTokenIsRefusedForGood() = runBlocking {
        val connection = graph.chatConnections.connection(pair("0".repeat(64)))
        val state = withTimeout(15_000) { connection.state.first { it is ConnectionState.Failed } }
        assertEquals(ConnectionFailure.Unauthorized, (state as ConnectionState.Failed).reason)
    }

    private companion object {
        /** The fixed test-only token from scripts/test-server.ps1. */
        const val TOKEN = "7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c"
    }
}
