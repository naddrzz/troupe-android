package dev.troupe.android.automations.net

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Automations routes (`automation_api.rs`) as the app calls them, against the contract fixtures. */
class AutomationsApiTest {
    @get:Rule val temp = TemporaryFolder()

    private val a1 = AutomationId("a1")
    private val r1 = RunId("r1")

    private fun reply(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body?.utf8().orEmpty()).jsonObject

    /** Runs [block] against a fake server answering with [route]; requests to other paths get the default 404. */
    private fun withApi(
        route: (RecordedRequest) -> MockResponse?,
        block: suspend (AutomationsApi, FakeTroupeServer) -> Unit,
    ) = runBlocking {
        FakeTroupeServer().use { server ->
            server.onHttpRoute = route
            TestGraph(temp.newFolder()).use { graph ->
                block(AutomationsApi(graph.api), server)
            }
        }
    }

    @Test
    fun listUsesProjectIdQueryAndDecodes() {
        val body = """{"success":true,"data":[${AutomationFixtures.load("automation.json")}]}"""
        withApi({ req -> if (req.url.encodedPath == "/api/automations") reply(200, body) else null }) { api, server ->
            val result = api.list(server.address, server.token, "p1") as ApiResult.Ok
            assertEquals(listOf("Morning digest"), result.value.map { it.name })
            val request = server.httpRequests.single { it.url.encodedPath == "/api/automations" }
            assertEquals("GET", request.method)
            assertEquals("p1", request.url.queryParameter("project_id"))
        }
    }

    @Test
    fun getRunsStepsDecode() {
        withApi({ req ->
            when (req.url.encodedPath) {
                "/api/automations/a1" -> reply(200, AutomationFixtures.load("automation-detail.json"))
                "/api/automations/a1/runs" -> reply(200, AutomationFixtures.load("runs.json"))
                "/api/automation-runs/r1/steps" -> reply(200, AutomationFixtures.load("steps.json"))
                else -> null
            }
        }) { api, server ->
            val detail = api.get(server.address, server.token, a1) as ApiResult.Ok
            assertEquals("Morning digest", detail.value.automation.name)

            val runs = api.runs(server.address, server.token, a1) as ApiResult.Ok
            assertTrue(runs.value.isNotEmpty())
            assertEquals("25", server.httpRequests.single { it.url.encodedPath == "/api/automations/a1/runs" }.url.queryParameter("limit"))

            val steps = api.steps(server.address, server.token, r1) as ApiResult.Ok
            assertTrue(steps.value.isNotEmpty())
            assertTrue(server.httpRequests.all { it.headers["Authorization"] == "Bearer ${server.token}" || server.token.isEmpty() })
        }
    }

    @Test
    fun setEnabledBody() {
        withApi({ req -> if (req.url.encodedPath == "/api/automations/a1/enabled") reply(200, """{"success":true,"data":null}""") else null }) { api, server ->
            assertEquals(ApiResult.Ok(Unit), api.setEnabled(server.address, server.token, a1, enabled = false))
            val request = server.httpRequests.single { it.url.encodedPath == "/api/automations/a1/enabled" }
            assertEquals("POST", request.method)
            assertEquals("""{"enabled":false}""", request.body?.utf8())
        }
    }

    @Test
    fun runNowReturnsRun() {
        val first = Json.parseToJsonElement(AutomationFixtures.load("runs.json")).jsonObject.getValue("data").jsonArray.first()
        withApi({ req -> if (req.url.encodedPath == "/api/automations/a1/run") reply(200, """{"success":true,"data":$first}""") else null }) { api, server ->
            val result = api.runNow(server.address, server.token, a1) as ApiResult.Ok
            assertEquals("run-1", result.value.id.value)
            val request = server.httpRequests.single { it.url.encodedPath == "/api/automations/a1/run" }
            assertEquals("POST", request.method)
            assertEquals("{}", request.body?.utf8())
        }
    }

    @Test
    fun decideSendsNodeIdAndApproved() {
        withApi({ req -> if (req.url.encodedPath == "/api/automation-runs/r1/approval") reply(200, """{"success":true,"data":false}""") else null }) { api, server ->
            val result = api.decideApproval(server.address, server.token, r1, NodeId("n1"), approved = true)
            assertEquals(ApiResult.Ok(false), result)
            val request = server.httpRequests.single { it.url.encodedPath == "/api/automation-runs/r1/approval" }
            assertEquals("POST", request.method)
            val body = request.bodyJson()
            assertEquals("n1", body["node_id"]?.jsonPrimitive?.content)
            assertTrue(body["approved"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Test
    fun cancelAndMarkSeen() {
        withApi({ req ->
            when (req.url.encodedPath) {
                "/api/automation-runs/r1/cancel", "/api/automations/a1/seen" -> reply(200, """{"success":true,"data":null}""")
                else -> null
            }
        }) { api, server ->
            assertEquals(ApiResult.Ok(Unit), api.cancelRun(server.address, server.token, r1))
            assertEquals(ApiResult.Ok(Unit), api.markSeen(server.address, server.token, a1))
            for (path in listOf("/api/automation-runs/r1/cancel", "/api/automations/a1/seen")) {
                val request = server.httpRequests.single { it.url.encodedPath == path }
                assertEquals("POST", request.method)
                assertEquals("{}", request.body?.utf8())
            }
        }
    }

    @Test
    fun pathSegmentsArePercentEncoded() {
        withApi({ req -> reply(200, """{"success":true,"data":null}""") }) { api, server ->
            api.markSeen(server.address, server.token, AutomationId("a b/c"))
            assertEquals("/api/automations/a%20b%2Fc/seen", server.httpRequests.single().url.encodedPath)
        }
    }

    @Test
    fun notApprovedKeepsCodeAndStatus() {
        val body = """{"success":false,"error":"approve the current revision before running it","code":"not_approved"}"""
        withApi({ req -> if (req.url.encodedPath == "/api/automations/a1/run") reply(409, body) else null }) { api, server ->
            val result = api.runNow(server.address, server.token, a1)
            assertEquals(
                ApiResult.ServerError("approve the current revision before running it", "not_approved", status = 409),
                result,
            )
        }
    }

    @Test
    fun notFoundKeepsStatus404() {
        withApi({ req -> if (req.url.encodedPath == "/api/automations/a1") reply(404, "not found") else null }) { api, server ->
            val result = api.get(server.address, server.token, a1) as ApiResult.ServerError
            assertEquals(404, result.status)
        }
    }

    @Test
    fun contractMismatchHasSuccessStatus() {
        val unknownStatus = AutomationFixtures.load("runs.json").replaceFirst("\"status\": \"failed\"", "\"status\": \"exploded\"")
        assertTrue("fixture must contain a failed run to corrupt", unknownStatus != AutomationFixtures.load("runs.json"))
        withApi({ req -> if (req.url.encodedPath == "/api/automations/a1/runs") reply(200, unknownStatus) else null }) { api, server ->
            val result = api.runs(server.address, server.token, a1) as ApiResult.ServerError
            assertEquals(200, result.status)
        }
    }

    @Test
    fun requestsAreSentOnce() {
        withApi({ req ->
            if (req.url.encodedPath == "/api/automations/a1/run") MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else null
        }) { api, server ->
            val result = api.runNow(server.address, server.token, a1)
            assertTrue("expected Unreachable, got $result", result is ApiResult.Unreachable)
            assertEquals(1, server.httpRequests.count { it.url.encodedPath == "/api/automations/a1/run" })
            assertNotNull(server.httpRequests.first().headers["X-Command-Id"])
        }
    }
}
