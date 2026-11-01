package dev.troupe.android.terminal

import dev.troupe.android.R
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.preservedList
import dev.troupe.android.testing.preservedTerminal
import dev.troupe.android.testing.spawnedTerminal
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Terminals tab (listing, "New terminal", kill) against the fake Troupe Server. */
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
        val stored = runBlocking { graph.cursors.get(serverId, "t1") }!!
        assertEquals("claim-t1", stored.claim)
        assertEquals(TerminalCursor("", 0), stored.cursor)
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

    private fun listed(serverId: String = pairedServerId()): Pair<TerminalsViewModel, TerminalsUi> {
        val vm = graph.terminalsViewModel(serverId, projectId = "p1")
        vm.refresh()
        return vm to runBlocking { withTimeout(10_000) { vm.ui.first { !it.loading } } }
    }

    @Test fun listsTheProjectsRunningTerminalsAndStoresTheirClaims() {
        server.onTerminalRequest = { type, payload ->
            check(type == "list_preserved")
            check(payload["projectId"]?.jsonPrimitive?.content == "p1")
            preservedList(
                "p1",
                preservedTerminal("t1", claim = "claim-t1", cwd = "/workspace/api"),
                preservedTerminal("t2", claim = "shared-t2", openElsewhere = true),
            )
        }
        val serverId = pairedServerId()

        val ui = listed(serverId).second

        assertEquals(
            listOf(
                TerminalRow("t1", "/bin/sh", "/workspace/api", openElsewhere = false),
                TerminalRow("t2", "/bin/sh", "/workspace", openElsewhere = true),
            ),
            ui.terminals,
        )
        assertNull(ui.error)
        assertEquals("claim-t1", runBlocking { graph.cursors.get(serverId, "t1") }!!.claim)
        assertEquals("shared-t2", runBlocking { graph.cursors.get(serverId, "t2") }!!.claim)
    }

    @Test fun aReissuedClaimReplacesTheStoredOneAndKeepsItsCursor() {
        server.onTerminalRequest = { _, _ -> preservedList("p1", preservedTerminal("t1", claim = "new")) }
        val serverId = pairedServerId()
        runBlocking {
            graph.cursors.putClaim(serverId, "t1", "old")
            graph.cursors.putCursor(serverId, "t1", TerminalCursor("e1", 9))
        }

        listed(serverId)

        val stored = runBlocking { graph.cursors.get(serverId, "t1") }!!
        assertEquals("new", stored.claim)
        assertEquals(TerminalCursor("e1", 9), stored.cursor)
    }

    @Test fun killAttachesWithTheClaimThenSendsALedgeredKillAndForgetsTheTerminal() {
        var killed = false
        server.onTerminalRequest = { type, _ ->
            when (type) {
                "list_preserved" -> if (killed) preservedList("p1") else preservedList("p1", preservedTerminal("t1", claim = "claim-t1"))
                "kill" -> JsonObject(emptyMap()).also { killed = true }
                else -> JsonObject(emptyMap())
            }
        }
        val serverId = pairedServerId()
        val (vm, _) = listed(serverId)

        vm.kill("t1")

        runBlocking { withTimeout(10_000) { vm.ui.first { it.killing == null && it.terminals.isEmpty() } } }
        val types = server.terminalRequests.map { it.getValue("type").jsonPrimitive.content }.filter { it == "attach" || it == "kill" }
        assertEquals(listOf("attach", "kill"), types)
        assertEquals("claim-t1", server.terminalRequests("attach").single().payloadString("claim"))
        val kill = server.terminalRequests("kill").single()
        assertEquals("t1", kill.payloadString("terminalId"))
        assertTrue(Regex("[A-Za-z0-9_-]{1,128}").matches(kill.getValue("commandId").jsonPrimitive.content))
        assertNull(runBlocking { graph.cursors.get(serverId, "t1") })
    }

    @Test fun refusedKillShowsTheServerReason() {
        server.onTerminalRequest = { type, _ ->
            when (type) {
                "list_preserved" -> preservedList("p1", preservedTerminal("t1", claim = "claim-t1"))
                "kill" -> throw FakeTroupeServer.Refused("KILL_FAILED", "no such process")
                else -> JsonObject(emptyMap())
            }
        }
        val (vm, _) = listed()

        vm.kill("t1")

        val ui = runBlocking { withTimeout(10_000) { vm.ui.first { it.error != null } } }
        assertEquals(R.string.terminals_error_kill, ui.error?.text)
        assertEquals("no such process", ui.error?.arg)
    }
}
