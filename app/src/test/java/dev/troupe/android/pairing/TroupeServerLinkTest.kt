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
}
