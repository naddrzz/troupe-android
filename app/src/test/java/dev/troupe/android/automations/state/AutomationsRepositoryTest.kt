package dev.troupe.android.automations.state

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun hits(path: String) = server.hits(path)

    private fun settle(path: String) = server.settle(path)

    /**
     * [ignored] must not make [path] reload and [control] must. The server sends events in order, so seeing exactly
     * one new request once the control's reload is in shows that the ignored event caused none.
     */
    private fun assertIgnoredThenReloads(path: String, ignored: () -> Unit, control: () -> Unit) {
        val before = hits(path)
        ignored()
        control()
        eventually(message = { "no reload after the control event" }) { hits(path) > before }
        Thread.sleep(300)
        assertEquals("the ignored event reloaded as well", before + 1, hits(path))
    }

    /**
     * A query on automation `a1` of project `p1`, already settled, must ignore `a2` and another project's project-wide
     * change, and reload for `a1` and its own project's project-wide changes.
     */
    private fun assertScopedToA1(path: String) {
        assertIgnoredThenReloads(path, ignored = { change("p1", "a2") }, control = { change("p1", "a1") })
        assertIgnoredThenReloads(path, ignored = { change("p2", null) }, control = { change("p1", null) })
    }

    private fun change(projectId: String, automationId: String?) = server.automationChanged(projectId, automationId)

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

    @Test fun aChangeBeforeTheWatchStartsIsLoadedOnItsFirstSnapshot() {
        val stale = listBody(AutomationFixtures.load("automation.json"))
        val fresh = listBody(renamed(AutomationFixtures.load("automation.json")))
        val changed = AtomicBoolean(false)
        val initialRead = CountDownLatch(1)
        route("/api/automations") {
            val body = if (changed.get()) fresh else stale
            // The automation changes right after the initial GET read it, before the subscription starts: no delta is sent.
            changed.set(true)
            initialRead.countDown()
            reply(200, body)
        }
        // The `agents` snapshot goes out only once the initial GET has read the old data.
        server.chat.beforeWatch = { watch -> if (watch == "agents") initialRead.await(10, TimeUnit.SECONDS) }

        val query = repository.list("p1")

        eventually(message = { "state: ${query.state.value}" }) { query.state.value.data?.map { it.name } == listOf("Renamed") }
    }

    @Test fun projectWideChangeReloadsDetail() {
        val first = AutomationFixtures.load("automation-detail.json")
        val second = renamed(first)
        val changed = AtomicBoolean(false)
        route("/api/automations/a1") { reply(200, if (changed.get()) second else first) }

        val query = repository.detail("p1", a1)
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

    @Test fun listIgnoresChangeForAnotherProject() {
        route("/api/automations") { reply(200, listBody(AutomationFixtures.load("automation.json"))) }
        repository.list("p1")
        settle("/api/automations")

        assertIgnoredThenReloads("/api/automations", ignored = { change("p2", "a1") }, control = { change("p1", "a1") })
    }

    @Test fun detailReloadsOnlyForItsAutomationOrAProjectWideChange() {
        route("/api/automations/a1") { reply(200, AutomationFixtures.load("automation-detail.json")) }
        repository.detail("p1", a1)
        settle("/api/automations/a1")

        assertScopedToA1("/api/automations/a1")
    }

    @Test fun runsReloadOnlyForTheirAutomationOrAProjectWideChange() {
        route("/api/automations/a1/runs") { reply(200, AutomationFixtures.load("runs.json")) }
        repository.runs("p1", a1)
        settle("/api/automations/a1/runs")

        assertScopedToA1("/api/automations/a1/runs")
    }

    @Test fun stepsLoadAndReloadOnlyForTheirAutomationOrAProjectWideChange() {
        val path = "/api/automation-runs/r1/steps"
        route(path) { reply(200, AutomationFixtures.load("steps.json")) }
        val query = repository.steps("p1", a1, RunId("r1")) { false }
        eventually(message = { "state: ${query.state.value}" }) { query.state.value.data?.isNotEmpty() == true }
        settle(path)

        assertScopedToA1(path)
    }

    @Test fun stepsPollWhileRunningAndStopWhenItEnds() {
        val path = "/api/automation-runs/r1/steps"
        route(path) { reply(200, AutomationFixtures.load("steps.json")) }
        val running = AtomicBoolean(true)

        repository.steps("p1", a1, RunId("r1")) { running.get() }
        settle(path)
        val settled = hits(path)

        // No event is sent after the watch's first snapshot: the count can only grow through the 2 s poll.
        eventually(message = { "polled ${hits(path) - settled} time(s)" }) { hits(path) >= settled + 2 }
        running.set(false)
        Thread.sleep(500) // a poll that began just before the flip finishes
        val stopped = hits(path)
        Thread.sleep(2_600) // longer than one poll interval
        assertEquals("polled after running() turned false", stopped, hits(path))
    }
}
