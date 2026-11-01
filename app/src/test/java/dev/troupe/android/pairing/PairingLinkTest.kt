package dev.troupe.android.pairing

import dev.troupe.android.core.net.TransportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table test: the input space of pasted links and typed addresses is large. */
class PairingLinkTest {

    private data class Ok(val raw: String, val baseUrl: String, val token: String)

    @Test fun validLinksParse() {
        val token = "a".repeat(64)
        listOf(
            Ok("https://abc.trycloudflare.com/#token=$token", "https://abc.trycloudflare.com/", token),
            Ok("  https://h.example.com/troupe/#token=abc%2Fd \n", "https://h.example.com/troupe/", "abc/d"),
            Ok("https://h.example.com/troupe#token=t", "https://h.example.com/troupe/", "t"),
            Ok("http://192.168.1.5:8080/#token=t", "http://192.168.1.5:8080/", "t"),
            Ok("http://127.0.0.1:18080/?x=1#token=t", "http://127.0.0.1:18080/", "t"),
            Ok("http://box.tail1234.ts.net:8080/#foo=1&token=t2", "http://box.tail1234.ts.net:8080/", "t2"),
            Ok("\thttps://h.example.com/#token=%20spaced%20\r\n", "https://h.example.com/", "spaced"),
        ).forEach { case ->
            val r = PairingLink.parse(case.raw).getOrThrow()
            assertEquals(case.raw, case.baseUrl, r.address.baseUrl.toString())
            assertEquals(case.raw, case.token, r.token)
        }
    }

    @Test fun wsUrlKeepsProxyPath() {
        val r = PairingLink.parse("https://h.example.com/troupe/#token=x").getOrThrow()
        assertEquals("wss://h.example.com/troupe/terminal/ws", r.address.wsUrl("terminal/ws"))
        assertEquals("https://h.example.com/troupe/projects", r.address.httpUrl("projects").toString())
        val plain = PairingLink.fromManual("10.0.0.2:8080", "x").getOrThrow()
        assertEquals("ws://10.0.0.2:8080/terminal/ws", plain.address.wsUrl("terminal/ws"))
    }

    @Test fun badLinksFail() {
        listOf(
            "https://h.example.com/" to PairingError.MissingToken::class,
            "https://h.example.com/#token=" to PairingError.MissingToken::class,
            "https://h.example.com/#other=1" to PairingError.MissingToken::class,
            "http://8.8.8.8:8080/#token=x" to PairingError.CleartextNotAllowed::class,
            "http://example.com/#token=x" to PairingError.CleartextNotAllowed::class,
            "not a url" to PairingError.InvalidUrl::class,
            "" to PairingError.InvalidUrl::class,
            "ftp://h.example.com/#token=x" to PairingError.InvalidUrl::class,
        ).forEach { (raw, expected) ->
            val error = PairingLink.parse(raw).exceptionOrNull()
            assertTrue("$raw -> $error", expected.isInstance(error))
        }
    }

    @Test fun manualEntry() {
        assertEquals("http://192.168.1.5:8080/", PairingLink.fromManual("192.168.1.5:8080", "t").getOrThrow().address.baseUrl.toString())
        assertEquals("https://h.example.com/", PairingLink.fromManual(" https://h.example.com ", " t ").getOrThrow().address.baseUrl.toString())
        assertEquals("t", PairingLink.fromManual("10.0.0.2", " t\n").getOrThrow().token)
        assertTrue(PairingLink.fromManual("10.0.0.2", "  ").exceptionOrNull() is PairingError.MissingToken)
        assertTrue(PairingLink.fromManual("", "t").exceptionOrNull() is PairingError.InvalidUrl)
        assertTrue(PairingLink.fromManual("h st", "t").exceptionOrNull() is PairingError.InvalidUrl)
        val cleartext = PairingLink.fromManual("example.com", "t").exceptionOrNull()
        assertEquals("example.com", (cleartext as PairingError.CleartextNotAllowed).host)
    }

    @Test fun toStringHidesToken() {
        assertFalse(PairingLink.fromManual("http://10.0.0.2", "secret123").getOrThrow().toString().contains("secret123"))
    }

    @Test fun cleartextAllowedOnlyForPrivateHosts() {
        listOf(
            "127.0.0.1", "127.8.9.1", "localhost", "::1", "[::1]", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.0.10", "100.64.0.1", "100.127.255.255", "box.local", "box.tail1234.ts.net", "BOX.LOCAL",
        ).forEach { assertTrue(it, TransportPolicy.isCleartextAllowed(it)) }
        listOf(
            "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.255.255", "192.169.0.1",
            "example.com", "abc.trycloudflare.com", "local", "evil.local.example.com", "2001:db8::1",
        ).forEach { assertFalse(it, TransportPolicy.isCleartextAllowed(it)) }
    }
}
