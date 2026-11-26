package dev.troupe.android.automations.state

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** [AutomationsController] over the real repository, events and API, against the fake Troupe Server, in real time. */
class AutomationsControllerTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val id = AutomationId("auto-1")
    private val runId = RunId("run-3") // parked on its approval node `gate`, so its steps are not polled

    private val listPath = "/api/automations"
    private val detailPath = "/api/automations/auto-1"
    private val runsPath = "/api/automations/auto-1/runs"
    private val seenPath = "/api/automations/auto-1/seen"
    private val enabledPath = "/api/automations/auto-1/enabled"
    private val runPath = "/api/automations/auto-1/run"
    private val stepsPath = "/api/automation-runs/run-3/steps"
    private val cancelPath = "/api/automation-runs/run-3/cancel"
    private val approvalPath = "/api/automation-runs/run-3/approval"

    private val routes = ConcurrentHashMap<String, () -> MockResponse>()

    private val controller by lazy {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        val hub = runBlocking { graph.chatConnections.connection(serverId) }.watchHub
        val repository = AutomationsRepository(AutomationsApi(graph.api), server.address, { server.token }, AutomationEvents(hub), scope)
        AutomationsController(repository, "project-1", scope)
    }

    init {
        server.onHttpRoute = { req -> routes[req.url.encodedPath]?.invoke() }
        routes[listPath] = { ok(listBody(AutomationFixtures.load("automation.json"))) }
        routes[detailPath] = { ok(AutomationFixtures.load("automation-detail.json")) }
        routes[runsPath] = { ok(AutomationFixtures.load("runs.json")) }
        routes[stepsPath] = { ok(AutomationFixtures.load("steps.json")) }
        routes[seenPath] = { ok(NULL_DATA) }
    }

    @After fun tearDown() {
        controller.close()
        scope.cancel()
        graph.close()
        server.close()
    }

    private fun reply(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun ok(body: String) = reply(200, body)

    private fun listBody(automation: String) = """{"success":true,"data":[$automation]}"""

    private fun replaced(json: String, from: String, to: String): String {
        val result = json.replace(from, to)
        assertNotEquals("fixture must contain $from", json, result)
        return result
    }

    private fun disabled(json: String) = replaced(json, "\"enabled\": true", "\"enabled\": false")

    private fun unapproved(json: String) = replaced(json, "\"approved_revision\": 1", "\"approved_revision\": null")

    /** The first run of `runs.json`, as the run route replies it. */
    private fun runBody(): String {
        val run = Json.parseToJsonElement(AutomationFixtures.load("runs.json")).jsonObject.getValue("data").jsonArray[0]
        return """{"success":true,"data":$run}"""
    }

    private fun hits(path: String) = server.httpRequests.count { it.url.encodedPath == path }

    private val ui get() = controller.ui.value

    /** Returns once the `agents` watch is up, [path] was requested, and its count stopped moving for half a second. */
    private fun settle(path: String) {
        eventually { server.chat.watching("agents") }
        eventually { hits(path) >= 1 }
        var last = -1
        while (hits(path) != last) {
            last = hits(path)
            Thread.sleep(500)
        }
    }

    private fun change(projectId: String, automationId: String?) = server.chat.agentEvent("automation_changed", buildJsonObject {
        put("project_id", projectId)
        put("automation_id", automationId)
    })

    private fun openRun() {
        controller.open(id)
        eventually(message = { "ui: $ui" }) { ui.runs?.data != null }
        controller.openRun(runId)
        settle(stepsPath)
        eventually(message = { "ui: $ui" }) { ui.route == Route.Run(id, runId) && ui.steps?.data != null }
    }

    @Test fun toggleEnabledFlipsImmediatelyAndRollsBack() {
        val release = CountDownLatch(1)
        routes[enabledPath] = {
            release.await(10, TimeUnit.SECONDS)
            reply(500, """{"success":false,"error":"boom"}""")
        }
        val control = Control(id.value, ControlAction.Enable)
        controller
        settle(listPath)

        controller.toggleEnabled(id, false)

        eventually(message = { "ui: $ui" }) { ui.enabledOverride[id] == false && control in ui.pending }
        release.countDown()
        eventually(message = { "ui: $ui" }) { ui.failures[control] != null && control !in ui.pending }
        assertTrue("failure: ${ui.failures[control]}", ui.failures[control] is QueryError.Server)
        assertNull("override kept after the failure", ui.enabledOverride[id])
    }

    @Test fun toggleEnabledSuccessWaitsForEventThenSettles() {
        val applied = AtomicBoolean(false)
        val automation = AutomationFixtures.load("automation.json")
        routes[listPath] = { ok(listBody(if (applied.get()) disabled(automation) else automation)) }
        routes[enabledPath] = {
            applied.set(true)
            ok(NULL_DATA)
        }
        val control = Control(id.value, ControlAction.Enable)
        controller
        settle(listPath)

        controller.toggleEnabled(id, false)

        eventually(message = { "ui: $ui" }) { control !in ui.pending }
        // The list still holds the old value: the override stands until a reload brings the server's.
        assertEquals(false, ui.enabledOverride[id])
        assertEquals(true, ui.list.data?.single()?.enabled)
        change("project-1", "auto-1")
        eventually(message = { "ui: $ui" }) { ui.enabledOverride.isEmpty() && ui.list.data?.single()?.enabled == false }
        assertNull(ui.failures[control])
    }

    @Test fun runNowDisablesItsControlWhileInFlight() {
        val release = CountDownLatch(1)
        routes[runPath] = {
            release.await(10, TimeUnit.SECONDS)
            ok(runBody())
        }
        val control = Control(id.value, ControlAction.Run)
        controller
        settle(listPath)

        controller.runNow(id)
        eventually(message = { "ui: $ui" }) { hits(runPath) == 1 && control in ui.pending }
        controller.runNow(id)
        release.countDown()

        eventually(message = { "ui: $ui" }) { control !in ui.pending }
        Thread.sleep(300)
        assertEquals("the second Run now was sent", 1, hits(runPath))
        assertNull(ui.failures[control])
    }

    @Test fun runNowBlockedWhenApprovalNeeded() {
        val approved = AtomicBoolean(false)
        val automation = AutomationFixtures.load("automation.json")
        routes[listPath] = { ok(listBody(if (approved.get()) automation else unapproved(automation))) }
        routes[runPath] = { ok(runBody()) }
        val control = Control(id.value, ControlAction.Run)
        controller
        eventually(message = { "ui: $ui" }) { ui.list.data != null }

        controller.runNow(id)

        eventually(message = { "ui: $ui" }) { ui.failures[control] == QueryError.NotApproved }
        assertTrue(control !in ui.pending)
        // Once the revision is approved the same call sends, so the refusal above was the gate, not a slow request.
        approved.set(true)
        controller.refresh()
        eventually(message = { "ui: $ui" }) { ui.list.data?.single()?.approvedRevision == 1L }
        assertEquals("Run now was sent while approval was needed", 0, hits(runPath))
        controller.runNow(id)
        eventually(message = { "ui: $ui" }) { hits(runPath) == 1 && control !in ui.pending && ui.failures[control] == null }
    }

    @Test fun connectionDropDuringMutationShowsFailureAndReenables() {
        val dropped = AtomicBoolean(false)
        routes[runPath] = {
            if (dropped.compareAndSet(false, true)) {
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            } else {
                ok(runBody())
            }
        }
        val control = Control(id.value, ControlAction.Run)
        controller
        settle(listPath)
        val listBefore = hits(listPath)

        controller.runNow(id)

        eventually(message = { "ui: $ui" }) { ui.failures[control] == QueryError.Unreachable && control !in ui.pending }
        // The write may have been applied, so the list re-reads once at once.
        eventually(message = { "list read ${hits(listPath) - listBefore} more time(s)" }) { hits(listPath) == listBefore + 1 }
        Thread.sleep(300)
        assertEquals(listBefore + 1, hits(listPath))
        assertEquals("the dropped request was resent", 1, hits(runPath))

        controller.runNow(id)
        eventually(message = { "ui: $ui" }) { hits(runPath) == 2 && control !in ui.pending && ui.failures[control] == null }
    }

    @Test fun decideFalseShowsNoticeAndRereads() {
        routes[approvalPath] = { ok("""{"success":true,"data":false}""") }
        controller
        openRun()
        val stepsBefore = hits(stepsPath)

        controller.decide(runId, NodeId("gate"), approved = true)

        eventually(message = { "ui: $ui" }) { Notice.ApprovalAlreadyHandled in ui.notices }
        // Sooner than the fallback window: the re-read is immediate.
        eventually(timeoutMs = 1_000, message = { "steps not re-read" }) { hits(stepsPath) > stepsBefore }
        assertEquals(1, hits(approvalPath))
        controller.dismissNotice(Notice.ApprovalAlreadyHandled)
        eventually(message = { "ui: $ui" }) { ui.notices.isEmpty() }
    }

    private fun raiseNotice() {
        routes[approvalPath] = { ok("""{"success":true,"data":false}""") }
        controller
        openRun()
        controller.decide(runId, NodeId("gate"), approved = true)
        eventually(message = { "ui: $ui" }) { Notice.ApprovalAlreadyHandled in ui.notices && ui.pending.isEmpty() }
    }

    @Test fun noticeStaysWhileOnTheRunThatRaisedIt() {
        raiseNotice()
        Thread.sleep(300)
        assertEquals(listOf(Notice.ApprovalAlreadyHandled), ui.notices)
    }

    @Test fun noticeIsDroppedWhenAnotherRunOpens() {
        raiseNotice()

        controller.openRun(RunId("run-1"))

        eventually(message = { "ui: $ui" }) { ui.route == Route.Run(id, RunId("run-1")) }
        assertTrue("notices: ${ui.notices}", ui.notices.isEmpty())
    }

    @Test fun noticeIsDroppedWhenLeavingTheRun() {
        raiseNotice()

        assertTrue(controller.back())

        eventually(message = { "ui: $ui" }) { ui.route == Route.Detail(id) }
        assertTrue("notices: ${ui.notices}", ui.notices.isEmpty())
    }

    @Test fun lateAnswerAfterLeavingTheRunRaisesNoNotice() {
        val release = CountDownLatch(1)
        routes[approvalPath] = {
            release.await(10, TimeUnit.SECONDS)
            ok("""{"success":true,"data":false}""")
        }
        controller
        openRun()
        controller.decide(runId, NodeId("gate"), approved = true)
        eventually(message = { "ui: $ui" }) { hits(approvalPath) == 1 }

        assertTrue(controller.back())
        release.countDown()

        eventually(message = { "ui: $ui" }) { ui.pending.isEmpty() }
        Thread.sleep(300)
        assertTrue("notices: ${ui.notices}", ui.notices.isEmpty())
    }

    @Test fun cancelRunAndDecideSendOnce() {
        val release = CountDownLatch(1)
        routes[cancelPath] = {
            release.await(10, TimeUnit.SECONDS)
            ok(NULL_DATA)
        }
        routes[approvalPath] = {
            release.await(10, TimeUnit.SECONDS)
            ok("""{"success":true,"data":true}""")
        }
        controller
        openRun()

        controller.cancelRun(runId)
        controller.decide(runId, NodeId("gate"), approved = true)
        eventually(message = { "ui: $ui" }) { hits(cancelPath) == 1 && hits(approvalPath) == 1 }
        controller.cancelRun(runId)
        controller.decide(runId, NodeId("gate"), approved = true)
        controller.decide(runId, NodeId("gate"), approved = false)
        release.countDown()

        eventually(message = { "ui: $ui" }) { ui.pending.isEmpty() }
        Thread.sleep(300)
        assertEquals(1, hits(cancelPath))
        assertEquals(1, hits(approvalPath))
        assertTrue("failures: ${ui.failures}", ui.failures.isEmpty())
    }

    @Test fun openingDetailWithUnseenFailuresMarksSeenOnce() {
        controller.setViewOnly(false)
        controller.setVisible(true)
        controller.open(id)
        settle(detailPath)
        eventually { hits(seenPath) == 1 }
        val detailBefore = hits(detailPath)

        change("project-1", "auto-1")

        eventually(message = { "detail not reloaded" }) { hits(detailPath) > detailBefore }
        Thread.sleep(300)
        assertEquals("a reload of the same opening marked seen again", 1, hits(seenPath))
        assertTrue(controller.back())
        controller.open(id)
        eventually(message = { "the next opening did not mark seen" }) { hits(seenPath) == 2 }
    }

    /** The detail route replies the fixture with [unseen] unseen failures. */
    private fun detailWithUnseen(unseen: AtomicInteger) {
        val detail = AutomationFixtures.load("automation-detail.json")
        routes[detailPath] = {
            val n = unseen.get()
            ok(if (n == 2) detail else replaced(detail, "\"unseen_failures\": 2", "\"unseen_failures\": $n"))
        }
    }

    private fun unseenOnScreen() = ui.detail?.data?.automation?.unseenFailures

    @Test fun failureWhileHiddenIsMarkedSeenOnlyOnceVisible() {
        val unseen = AtomicInteger(0)
        detailWithUnseen(unseen)
        controller.setViewOnly(false)
        controller.open(id)
        settle(detailPath)

        unseen.set(2)
        change("project-1", "auto-1")
        eventually(message = { "ui: $ui" }) { unseenOnScreen() == 2 }
        Thread.sleep(500)
        assertEquals("marked seen while hidden", 0, hits(seenPath))

        controller.setVisible(true)
        eventually(message = { "not marked seen once visible" }) { hits(seenPath) == 1 }
        settle(detailPath)
        assertEquals(1, hits(seenPath))
    }

    @Test fun viewOnlyNeverMarksSeen() {
        controller.setViewOnly(true)
        controller.setVisible(true)
        controller.open(id)
        settle(detailPath)
        eventually(message = { "ui: $ui" }) { unseenOnScreen() == 2 }
        Thread.sleep(500)
        assertEquals("a view-only session marked seen", 0, hits(seenPath))

        // The same screen marks once the session may write, so the silence above was the view-only guard.
        controller.setViewOnly(false)
        eventually(message = { "not marked seen" }) { hits(seenPath) == 1 }
    }

    @Test fun runRouteDoesNotMarkSeen() {
        val unseen = AtomicInteger(0)
        detailWithUnseen(unseen)
        controller.setViewOnly(false)
        controller.setVisible(true)
        openRun()

        unseen.set(2)
        change("project-1", "auto-1")
        eventually(message = { "ui: $ui" }) { unseenOnScreen() == 2 }
        Thread.sleep(500)
        assertEquals("the run route marked seen", 0, hits(seenPath))

        assertTrue(controller.back())
        eventually(message = { "the detail route did not mark seen" }) { hits(seenPath) == 1 }
    }

    @Test fun newFailureWhileLookingIsMarkedAgain() {
        val unseen = AtomicInteger(2)
        detailWithUnseen(unseen)
        controller.setViewOnly(false)
        controller.setVisible(true)
        controller.open(id)
        eventually(message = { "not marked seen" }) { hits(seenPath) == 1 }
        settle(detailPath)
        assertEquals(1, hits(seenPath))

        unseen.set(3)
        change("project-1", "auto-1")
        eventually(message = { "a new failure was not marked seen" }) { hits(seenPath) == 2 }

        // Seen on the server, then failing again with the count marked before.
        unseen.set(0)
        change("project-1", "auto-1")
        eventually(message = { "ui: $ui" }) { unseenOnScreen() == 0 }
        unseen.set(3)
        change("project-1", "auto-1")
        eventually(message = { "a failure after none was not marked seen" }) { hits(seenPath) == 3 }
        settle(detailPath)
        assertEquals(3, hits(seenPath))
    }

    /** `runs.json` with run-3 running and not parked, so its steps are polled. */
    private fun runsWithRun3Polled(): String {
        val runs = Json.parseToJsonElement(AutomationFixtures.load("runs.json")).jsonObject.getValue("data").jsonArray.map { run ->
            if (run.jsonObject["id"]?.jsonPrimitive?.content == runId.value) JsonObject(run.jsonObject + ("parked" to JsonNull)) else run
        }
        return """{"success":true,"data":${JsonArray(runs)}}"""
    }

    @Test fun hiddenControllerDoesNotPollSteps() {
        routes[runsPath] = { ok(runsWithRun3Polled()) }
        controller.setVisible(true)
        openRun()

        val visibleBefore = hits(stepsPath)
        eventually(timeoutMs = 6_000, message = { "steps not polled while visible" }) { hits(stepsPath) >= visibleBefore + 2 }

        controller.setVisible(false)
        Thread.sleep(500)
        val hiddenBefore = hits(stepsPath)
        Thread.sleep(4_500)
        assertEquals("steps polled while hidden", hiddenBefore, hits(stepsPath))
    }

    @Test fun becomingVisibleReloadsOnce() {
        controller.setVisible(true)
        controller.open(id)
        settle(detailPath)
        settle(runsPath)
        controller.setVisible(false)
        Thread.sleep(300)
        val detailBefore = hits(detailPath)
        val runsBefore = hits(runsPath)

        controller.setVisible(true)
        eventually(message = { "detail +${hits(detailPath) - detailBefore}, runs +${hits(runsPath) - runsBefore}" }) {
            hits(detailPath) == detailBefore + 1 && hits(runsPath) == runsBefore + 1
        }
        controller.setVisible(true)
        Thread.sleep(500)
        assertEquals(detailBefore + 1, hits(detailPath))
        assertEquals(runsBefore + 1, hits(runsPath))
    }

    @Test fun runNowFromDetailWaitsForTheNewRun() {
        val newRun = RunId("run-1")
        val started = AtomicBoolean(false)
        val all = AutomationFixtures.load("runs.json")
        val without = Json.parseToJsonElement(all).jsonObject.getValue("data").jsonArray
            .filter { it.jsonObject["id"]?.jsonPrimitive?.content != newRun.value }
        routes[runsPath] = { ok(if (started.get()) all else """{"success":true,"data":${JsonArray(without)}}""") }
        routes[runPath] = {
            started.set(true)
            ok(runBody())
        }
        controller.open(id)
        settle(runsPath)
        eventually(message = { "ui: $ui" }) { ui.runs?.data?.none { it.id == newRun } == true }
        // What the run route would call "not in the history any more": a settled history without the run.
        val calledMissing = AtomicBoolean(false)
        val watcher = scope.launch {
            controller.ui.collect { u ->
                val runs = u.runs
                if (u.route == Route.Run(id, newRun) && runs != null && runs.loaded && !runs.refreshing && runs.data?.none { it.id == newRun } == true) {
                    calledMissing.set(true)
                }
            }
        }
        val runsBefore = hits(runsPath)

        controller.runNow(id)

        eventually(message = { "ui: $ui" }) { ui.route == Route.Run(id, newRun) }
        // Sooner than the fallback window: the runs re-read at once.
        eventually(timeoutMs = 1_000, message = { "runs not re-read" }) { hits(runsPath) > runsBefore }
        eventually(message = { "ui: $ui" }) { ui.runs?.data?.any { it.id == newRun } == true && ui.runs?.refreshing == false }
        watcher.cancel()
        assertFalse("the run route showed a settled history without the new run", calledMissing.get())
    }

    @Test fun failureLinesClearOnRefreshAndRouteChange() {
        routes[listPath] = { ok(listBody(unapproved(AutomationFixtures.load("automation.json")))) }
        val control = Control(id.value, ControlAction.Run)
        controller
        eventually(message = { "ui: $ui" }) { ui.list.data != null }

        controller.runNow(id)
        eventually(message = { "ui: $ui" }) { ui.failures[control] == QueryError.NotApproved }
        controller.refresh()
        eventually(message = { "refresh kept: ${ui.failures}" }) { ui.failures.isEmpty() }

        controller.runNow(id)
        eventually(message = { "ui: $ui" }) { ui.failures[control] == QueryError.NotApproved }
        controller.open(id)
        eventually(message = { "route change kept: ${ui.failures}" }) { ui.route == Route.Detail(id) && ui.failures.isEmpty() }
    }

    @Test fun failureOfRequestInFlightOutlivesRouteChangeAndRefresh() {
        val release = CountDownLatch(1)
        routes[enabledPath] = {
            release.await(10, TimeUnit.SECONDS)
            reply(500, """{"success":false,"error":"boom"}""")
        }
        val control = Control(id.value, ControlAction.Enable)
        controller
        settle(listPath)
        controller.toggleEnabled(id, false)
        eventually(message = { "ui: $ui" }) { control in ui.pending }

        controller.open(id)
        controller.refresh()
        release.countDown()

        eventually(message = { "ui: $ui" }) { control !in ui.pending }
        assertTrue("failure: ${ui.failures[control]}", ui.failures[control] is QueryError.Server)
    }

    @Test fun backFromDetailClosesDetailQueries() {
        controller.open(id)
        settle(detailPath)
        settle(runsPath)

        assertTrue(controller.back())

        eventually(message = { "ui: $ui" }) { ui.route == Route.Overview && ui.detail == null && ui.runs == null }
        val listBefore = hits(listPath)
        val detailBefore = hits(detailPath)
        val runsBefore = hits(runsPath)
        change("project-1", "auto-1")
        eventually(message = { "the list did not reload" }) { hits(listPath) > listBefore }
        Thread.sleep(300)
        assertEquals("the closed detail reloaded", detailBefore, hits(detailPath))
        assertEquals("the closed runs reloaded", runsBefore, hits(runsPath))
    }

    @Test fun backAtOverviewReturnsFalse() {
        assertFalse(controller.back())

        openRun()
        assertTrue(controller.back())
        eventually(message = { "ui: $ui" }) { ui.route == Route.Detail(id) && ui.steps == null && ui.detail != null }
        assertTrue(controller.back())
        assertFalse(controller.back())
    }

    @Test fun postMutationRereadsOnceWhenNoEventArrives() {
        routes[enabledPath] = { ok(NULL_DATA) }
        val control = Control(id.value, ControlAction.Enable)
        controller
        settle(listPath)
        val listBefore = hits(listPath)

        controller.toggleEnabled(id, false)
        eventually(message = { "ui: $ui" }) { control !in ui.pending }

        // No event is sent: only the 1.5 s fallback can re-read.
        eventually(timeoutMs = 5_000, message = { "list read ${hits(listPath) - listBefore} more time(s)" }) { hits(listPath) > listBefore }
        Thread.sleep(2_000)
        assertEquals(listBefore + 1, hits(listPath))
        assertEquals(1, hits(enabledPath))
    }

    private companion object {
        const val NULL_DATA = """{"success":true,"data":null}"""
    }
}
