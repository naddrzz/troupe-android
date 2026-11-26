package dev.troupe.android.automations.ui

import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.state.Mutation
import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A controller's repository sends its own server's token, whichever server is active. */
class ServerBoundTokenTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.cancel()
        graph.close()
        server.close()
    }

    @Test fun requestsCarryTheBoundServersTokenNotTheActiveOnes() {
        server.onHttpRoute = { req ->
            if (req.url.encodedPath == "/api/automations/a1/seen") {
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"success":true,"data":null}""").build()
            } else {
                null
            }
        }
        val a = runBlocking { graph.repository.add("A", server.pairingInput()) }
        val hub = runBlocking { graph.chatConnections.connection(a.id) }.watchHub
        val repository = automationsRepository(AutomationsApi(graph.api), graph.repository, ConnectionKey(a.id, a.address), hub, scope)

        // Pairing B makes it the active server, as a server switch does.
        runBlocking { graph.repository.add("B", PairingInput(ServerAddress("http://127.0.0.1:9/".toHttpUrl()), "token-b")) }
        runBlocking { assertEquals(Mutation.Ok(Unit), repository.markSeen(AutomationId("a1"))) }
        // A new token for A applies to the next request.
        runBlocking { graph.repository.updateToken(a.id, "rotated-a") }
        runBlocking { assertEquals(Mutation.Ok(Unit), repository.markSeen(AutomationId("a1"))) }

        val sent = server.httpRequests.filter { it.url.encodedPath == "/api/automations/a1/seen" }
        assertEquals(listOf("Bearer ${server.token}", "Bearer rotated-a"), sent.map { it.headers["Authorization"] })
    }
}
