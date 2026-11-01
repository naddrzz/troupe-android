package dev.troupe.android.terminal

import dev.troupe.android.R
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.spawnedTerminal
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Terminals tab's "New terminal", against the fake Troupe Server. */
class TerminalsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun pairedServerId(token: String = server.token): String =
        runBlocking { graph.repository.add("Box", server.pairingInput(token)).id }

    private fun spawn(serverId: String = pairedServerId()): TerminalsUi {
        val vm = graph.terminalsViewModel(serverId, projectId = "p1")
        vm.spawn()
        return runBlocking { withTimeout(10_000) { vm.ui.first { !it.spawning && (it.openTerminal != null || it.error != null) } } }
    }

    private fun spawnsTerminal() {
        server.onTerminalRequest = { type, _ ->
            check(type == "spawn")
            spawnedTerminal(id = "t1", claim = "claim-t1")
        }
    }

    @Test fun newTerminalSendsALedgeredSpawnForTheProjectAndOpensIt() {
        spawnsTerminal()
        val serverId = pairedServerId()

        val ui = spawn(serverId)

        assertEquals("t1", ui.openTerminal)
        val request = server.terminalRequests("spawn").single()
        assertEquals("p1", request.payloadString("projectId"))
        assertEquals(80, request.payloadInt("cols"))
        assertEquals(24, request.payloadInt("rows"))
        assertTrue(Regex("[A-Za-z0-9_-]{1,128}").matches(request.getValue("commandId").jsonPrimitive.content))
        assertEquals("claim-t1", graph.claims.claim(serverId, "t1"))
    }

    @Test fun refusedSpawnShowsTheServerReason() {
        server.onTerminalRequest = { _, _ -> throw FakeTroupeServer.Refused("TERMINAL_LIMIT", "terminal limit reached (8 per project)") }

        val ui = spawn()

        assertNull(ui.openTerminal)
        assertEquals(R.string.terminals_error_spawn, ui.error?.text)
        assertEquals("terminal limit reached (8 per project)", ui.error?.arg)
    }

    @Test fun rejectedTokenSaysSo() {
        val ui = spawn(pairedServerId(token = "stale"))
        assertEquals(R.string.pair_error_unauthorized, ui.error?.text)
        assertTrue(server.terminalRequests("spawn").isEmpty())
    }

    @Test fun unreachableServerSaysSo() {
        val id = pairedServerId()
        server.goOffline()
        assertEquals(R.string.pair_error_unreachable, spawn(id).error?.text)
    }
}
