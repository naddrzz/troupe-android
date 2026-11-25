package dev.troupe.android.setup

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSetupApiTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun statusAndApplySucceedWithSetupToken() = runBlocking {
        FakeTroupeServer().use { server ->
            server.setupRequired = true
            TestGraph(temp.newFolder()).use { graph ->
                val unauth = graph.setupApi.getStatus(server.address, "bad-token")
                assertTrue(unauth is ApiResult.Unauthorized)

                val status = graph.setupApi.getStatus(server.address, server.setupToken) as ApiResult.Ok
                assertTrue(status.value.setupRequired)
                assertEquals("0.0.0.0", status.value.defaults.host)

                val apply = graph.setupApi.apply(server.address, server.setupToken, status.value.defaults) as ApiResult.Ok
                assertTrue(apply.value.ok)
                assertEquals(server.token, apply.value.webAuthToken)
            }
        }
    }

    @Test
    fun applySerializesExplicitNullUpdateChannelAndNonNullChannel() = runBlocking {
        FakeTroupeServer().use { server ->
            server.setupRequired = true
            TestGraph(temp.newFolder()).use { graph ->
                val offAnswers = ServerSetupAnswers(updateChannel = null)
                val offRes = graph.setupApi.apply(server.address, server.setupToken, offAnswers)
                assertTrue(offRes is ApiResult.Ok)
                val offBody = server.httpRequests.last { it.url.encodedPath == "/setup/apply" }.body?.utf8().orEmpty()
                assertTrue("Expected explicit null updateChannel in: $offBody", offBody.contains("\"updateChannel\":null"))

                val stableAnswers = ServerSetupAnswers(updateChannel = "stable")
                val stableRes = graph.setupApi.validate(server.address, server.setupToken, stableAnswers)
                assertTrue(stableRes is ApiResult.Ok)
                val stableBody = server.httpRequests.last { it.url.encodedPath == "/setup/validate" }.body?.utf8().orEmpty()
                assertTrue("Expected string updateChannel in: $stableBody", stableBody.contains("\"updateChannel\":\"stable\""))
            }
        }
    }
}
