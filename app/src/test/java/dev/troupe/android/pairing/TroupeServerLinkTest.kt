package dev.troupe.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TroupeServerLinkTest {

    @Test
    fun parsesGatedHttpsLinkWithTokenAndPrefix() {
        val parsed = TroupeServerLink.parse("https://demo.trycloudflare.com/app/#token=abc%2B123").getOrThrow()
        assertEquals("https://demo.trycloudflare.com/app/", parsed.address.baseUrl.toString())
        assertEquals("abc+123", parsed.token)
        assertNull(parsed.setupToken)
        assertNull(parsed.chatSessionId)
        assertFalse(parsed.isWildcardHost)
    }

    @Test
    fun parsesUngatedLocalLinkWithoutToken() {
        val parsed = TroupeServerLink.parse("http://192.168.1.5:8080").getOrThrow()
        assertEquals("http://192.168.1.5:8080/", parsed.address.baseUrl.toString())
        assertNull(parsed.token)
        assertFalse(parsed.isWildcardHost)
    }

    @Test
    fun extractsUrlFromMultiLineTerminalOutput() {
        val consolePaste = """
            troupe-server listening on http://192.168.1.42:8080/#token=secret-xyz
            Press Ctrl+C to stop.
        """.trimIndent()
        val parsed = TroupeServerLink.parse(consolePaste).getOrThrow()
        assertEquals("http://192.168.1.42:8080/", parsed.address.baseUrl.toString())
        assertEquals("secret-xyz", parsed.token)
    }

    @Test
    fun detectsWildcardHostAndRewritesWhenReplacementProvided() {
        val raw = "http://0.0.0.0:8080/?setup=once-token"
        val wildcard = TroupeServerLink.parse(raw).getOrThrow()
        assertTrue(wildcard.isWildcardHost)
        assertEquals("once-token", wildcard.setupToken)

        val rewritten = TroupeServerLink.parse(raw, replacementHost = "100.64.0.10").getOrThrow()
        assertFalse(rewritten.isWildcardHost)
        assertEquals("http://100.64.0.10:8080/", rewritten.address.baseUrl.toString())
        assertEquals("once-token", rewritten.setupToken)
    }

    @Test
    fun extractsChatSessionDeepLink() {
        val parsed = TroupeServerLink.parse("https://box.ts.net/#/c/sess_42&token=tok99").getOrThrow()
        assertEquals("sess_42", parsed.chatSessionId)
        assertEquals("tok99", parsed.token)
    }

    @Test
    fun refusesCleartextPublicHost() {
        val result = TroupeServerLink.parse("http://example.com:8080/#token=abc")
        assertTrue(result.exceptionOrNull() is PairingError.CleartextNotAllowed)
    }

    @Test
    fun bareHostPortDefaultsToHttpOnLan() {
        val parsed = TroupeServerLink.parse("192.168.1.12:8080").getOrThrow()
        assertEquals("http://192.168.1.12:8080/", parsed.address.baseUrl.toString())
        assertNull(parsed.token)
    }

    @Test
    fun tableCoversNoiseUrlEncodedTokensAndProxyPathsIntoWsUrl() {
        data class Case(val raw: String, val baseUrl: String, val wsTerminalUrl: String, val token: String)
        val cases = listOf(
            Case(
                raw = "  https://h.example.com/troupe/#token=abc%2Fd \n",
                baseUrl = "https://h.example.com/troupe/",
                wsTerminalUrl = "wss://h.example.com/troupe/terminal/ws",
                token = "abc/d",
            ),
            Case(
                raw = "\n\nhttps://h.example.com/a/b#token=a+b%3D%3D\r\n",
                baseUrl = "https://h.example.com/a/b/",
                wsTerminalUrl = "wss://h.example.com/a/b/terminal/ws",
                token = "a+b==",
            ),
            Case(
                raw = "\thttp://box.tail1234.ts.net:8080/proxy/#foo=1&token=%20spaced%20\n",
                baseUrl = "http://box.tail1234.ts.net:8080/proxy/",
                wsTerminalUrl = "ws://box.tail1234.ts.net:8080/proxy/terminal/ws",
                token = "spaced",
            ),
            Case(
                raw = "http://100.64.0.7:8080/#token=tok123",
                baseUrl = "http://100.64.0.7:8080/",
                wsTerminalUrl = "ws://100.64.0.7:8080/terminal/ws",
                token = "tok123",
            ),
            Case(
                raw = "http://box.local:8080/#token=tok456",
                baseUrl = "http://box.local:8080/",
                wsTerminalUrl = "ws://box.local:8080/terminal/ws",
                token = "tok456",
            ),
        )
        for (c in cases) {
            val parsed = TroupeServerLink.parse(c.raw).getOrThrow()
            assertEquals(c.raw, c.baseUrl, parsed.address.baseUrl.toString())
            assertEquals(c.raw, c.wsTerminalUrl, parsed.address.wsUrl("terminal/ws"))
            assertEquals(c.raw, c.token, parsed.token)
            assertFalse("toString hides token", parsed.toString().contains(c.token))
        }
    }
}
