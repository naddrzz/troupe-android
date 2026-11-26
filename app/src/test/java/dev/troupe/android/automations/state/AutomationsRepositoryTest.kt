package dev.troupe.android.automations.state

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

/** [AutomationsRepository] over the real [AutomationEvents] and `AutomationsApi`, against the fake Troupe Server. */
class AutomationsRepositoryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a1 = AutomationId("a1")

    @Volatile private var token: String? = server.token

    private val repository by lazy {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val hub = runBlocking { graph.chatConnections.connection(serverId) }.watchHub
        AutomationsRepository(AutomationsApi(graph.api), server.address, { token }, AutomationEvents(hub), scope)
    }

    @After fun tearDown() {
        scope.cancel()
        graph.close()
        server.close()
    }

    private fun reply(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun route(path: String, respond: () -> MockResponse) {
        server.onHttpRoute = { req: RecordedRequest -> if (req.url.encodedPath == path) respond() else null }
    }

    private fun listBody(automation: String) = """{"success":true,"data":[$automation]}"""

    private fun renamed(json: String): String {
        val result = json.replace("\"name\": \"Morning digest\"", "\"name\": \"Renamed\"")
        assertNotEquals("fixture must contain the name to replace", json, result)
        return result
    }

    private fun change(projectId: String, automationId: String?) = server.chat.agentEvent("automation_changed", buildJsonObject {
        put("project_id", projectId)
        put("automation_id", automationId)
    })

    @Test fun listReloadsOnChangeForItsProject() {
        val first = listBody(AutomationFixtures.load("automation.json"))
        val second = listBody(renamed(AutomationFixtures.load("automation.json")))
        val changed = AtomicBoolean(false)
        route("/api/automations") { reply(200, if (changed.get()) second else first) }

        val query = repository.list("p1")
        eventually { query.state.value.data?.map { it.name } == listOf("Morning digest") }
        eventually { server.chat.watching("agents") }
        changed.set(true)
        change("p1", null)

        // A spurious extra reload (a resync) only loads the same second body, so the final content is what counts.
        eventually(message = { "state: ${query.state.value}" }) { query.state.value.data?.map { it.name } == listOf("Renamed") }
    }

    @Test fun projectWideChangeReloadsDetail() {
        val first = AutomationFixtures.load("automation-detail.json")
        val second = renamed(first)
        val changed = AtomicBoolean(false)
        route("/api/automations/a1") { reply(200, if (changed.get()) second else first) }

        val query = repository.detail(a1)
        eventually { query.state.value.data?.automation?.name == "Morning digest" }
        eventually { server.chat.watching("agents") }
        changed.set(true)
        change("p1", null)

        eventually(message = { "state: ${query.state.value}" }) { query.state.value.data?.automation?.name == "Renamed" }
    }

    @Test fun listOn404IsUnsupported() {
        route("/api/automations") { reply(404, "not found") }

        val query = repository.list("p1")

        eventually(message = { "state: ${query.state.value}" }) { query.state.value.error == QueryError.Unsupported }
    }

    @Test fun contractMismatchIsInvalid() {
        val body = listBody(AutomationFixtures.load("automation.json")).replace("\"kind\": \"fail\"", "\"kind\": \"teleport\"")
        assertNotEquals("fixture must contain a fail node to corrupt", listBody(AutomationFixtures.load("automation.json")), body)
        route("/api/automations") { reply(200, body) }

        val query = repository.list("p1")

        eventually(message = { "state: ${query.state.value}" }) { query.state.value.error == QueryError.Invalid }
    }

    @Test fun runNowNotApprovedMapsToNotApproved() {
        route("/api/automations/a1/run") {
            reply(409, """{"success":false,"error":"approve the current revision before running it","code":"not_approved"}""")
        }

        val result = runBlocking { repository.runNow(a1) }

        assertEquals(Mutation.Failed(QueryError.NotApproved), result)
    }

    @Test fun mutationsUseTheCurrentToken() {
        route("/api/automations/a1/seen") { reply(200, """{"success":true,"data":null}""") }

        runBlocking {
            token = "first"
            assertEquals(Mutation.Ok(Unit), repository.markSeen(a1))
            token = "second"
            assertEquals(Mutation.Ok(Unit), repository.markSeen(a1))
        }

        val sent = server.httpRequests.filter { it.url.encodedPath == "/api/automations/a1/seen" }
        assertEquals(listOf("Bearer first", "Bearer second"), sent.map { it.headers["Authorization"] })
    }
}
