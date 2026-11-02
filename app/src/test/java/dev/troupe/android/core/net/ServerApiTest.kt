package dev.troupe.android.core.net

import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The shared client's transport policy and how [ServerApi] reads replies. */
class ServerApiTest {
    private val server = MockWebServer().apply { start() }

    @After fun tearDown() = server.close()

    @Test fun cleartextToAPublicHostIsRefusedBeforeConnecting() {
        val result = runBlocking { ServerApi(troupeHttpClient()).health(ServerAddress("http://8.8.8.8:9/".toHttpUrl())) }
        assertEquals(ApiResult.Unreachable("CleartextRefused"), result)
    }

    @Test fun aRedirectToACleartextPublicHostIsNotFollowed() {
        // troupe.example resolves to this machine, so following the redirect would reach the mock server again.
        val client = troupeHttpClient().newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "troupe.example") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
            })
            .build()
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://troupe.example:${server.port}/health").build())
        server.enqueue(MockResponse.Builder().body("""{"status":"ok","allowRemoteWrites":true}""").build())

        val result = runBlocking { ServerApi(client).health(ServerAddress(server.url("/"))) }

        assertEquals(ApiResult.Unreachable("CleartextRefused"), result)
        assertEquals(1, server.requestCount)
    }

    @Test fun anErrorStatusIsNeverASuccess() {
        server.enqueue(MockResponse.Builder().code(502).body("""{"status":"ok","allowRemoteWrites":true}""").build())

        val result = runBlocking { ServerApi(troupeHttpClient()).health(ServerAddress(server.url("/"))) }

        assertTrue("$result", result is ApiResult.ServerError)
    }
}
