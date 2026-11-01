package dev.troupe.android.pairing

import dev.troupe.android.R
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Manual pairing, driven like the Pair screen does, against the fake Troupe Server. */
class PairViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun pair(address: String = server.typedAddress, token: String = server.token, name: String = ""): PairUiState {
        val vm = graph.pairViewModel()
        vm.onManual(address, token, name)
        return runBlocking { withTimeout(10_000) { vm.ui.first { it is PairUiState.Error || it is PairUiState.Paired } } }
    }

    private fun savedServers() = runBlocking { graph.repository.servers.first() }

    private fun assertNothingSaved() {
        assertTrue(savedServers().isEmpty())
        assertFalse(graph.prefsFile.exists() && graph.prefsFile.readText(Charsets.ISO_8859_1).contains(server.token))
    }

    @Test fun validAddressAndTokenSavesTheServer() {
        val state = pair(name = "MacBook")

        val paired = state as PairUiState.Paired
        val saved = savedServers().single()
        assertEquals(paired.serverId, saved.id)
        assertEquals("MacBook", saved.name)
        assertEquals(server.address, saved.address)
        assertEquals(server.token, runBlocking { graph.repository.token(saved.id) })
    }

    @Test fun nameDefaultsToTheHost() {
        pair(name = "  ")
        assertEquals(server.address.host, savedServers().single().name)
    }

    @Test fun pairingProvesTheTokenWithTheTerminalHandshake() {
        pair()

        val auth = server.terminalRequests.first()
        assertEquals("authenticate", auth["type"]!!.jsonPrimitive.content)
        assertEquals(7, auth.payloadInt("protocolVersion"))
        assertEquals(server.token, auth.payloadString("token"))
        assertFalse(auth.payloadString("deviceId").isNullOrEmpty())
        assertFalse(auth.payloadString("clientInstanceId").isNullOrEmpty())
        assertNull("no Origin header", server.terminalUpgrades.single().headers["Origin"])
        assertTrue("token never in a URL", server.httpRequests.none { server.token in it.url.toString() })
    }

    @Test fun deviceIdIsStableAcrossPairings() {
        pair()
        pair()
        val ids = server.terminalRequests.map { it.payloadString("deviceId") }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }

    @Test fun unreachableServer() {
        val address = server.typedAddress
        server.goOffline()

        assertEquals(PairUiState.Error(R.string.pair_error_unreachable), pair(address = address))
        assertNothingSaved()
    }

    @Test fun rejectedToken() {
        assertEquals(PairUiState.Error(R.string.pair_error_unauthorized), pair(token = "wrong-token"))
        assertNothingSaved()
        assertEquals("no retry", 1, server.terminalUpgrades.size)
    }

    @Test fun unsupportedProtocolVersion() {
        server.protocolVersion = 8

        assertEquals(PairUiState.Error(R.string.pair_error_protocol), pair())
        assertNothingSaved()
        assertEquals("no retry", 1, server.terminalUpgrades.size)
    }

    @Test fun publicCleartextAddressIsRefusedBeforeAnyRequest() {
        assertEquals(PairUiState.Error(R.string.pair_error_cleartext, "8.8.8.8"), pair(address = "http://8.8.8.8:8080"))
        assertNothingSaved()
        assertTrue(server.httpRequests.isEmpty())
    }

    @Test fun malformedAddressAndMissingToken() {
        assertEquals(PairUiState.Error(R.string.pair_error_invalid_url), pair(address = "not an address"))
        assertEquals(PairUiState.Error(R.string.pair_error_missing_token), pair(token = " "))
        assertNothingSaved()
    }
}
