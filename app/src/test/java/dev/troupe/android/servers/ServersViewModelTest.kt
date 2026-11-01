package dev.troupe.android.servers

import dev.troupe.android.pairing.PairUiState
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
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

/** The servers list, driven like the Servers screen does, against fake Troupe Servers. */
class ServersViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private var graph: TestGraph? = null
    private fun graph(): TestGraph = graph ?: TestGraph(tmp.root).also { graph = it }

    @After fun tearDown() {
        graph?.close()
        server.close()
    }

    private fun paired(name: String = "Box", token: String = server.token, on: FakeTroupeServer = server): PairedServer =
        runBlocking { graph().repository.add(name, on.pairingInput(token)) }

    /** Shows the list (which probes every server) and waits until no badge is still checking. */
    private fun shownRows(vm: ServersViewModel = graph().serversViewModel()): List<ServerRow> {
        vm.probe()
        return runBlocking {
            withTimeout(10_000) { vm.rows.first { rows -> rows != null && rows.none { it.status == ServerStatus.Checking } }!! }
        }
    }

    @Test fun rowShowsNameHostAndConnectedBadge() {
        val s = paired(name = "MacBook")

        val row = shownRows().single()
        assertEquals(s.id, row.server.id)
        assertEquals("MacBook", row.server.name)
        assertEquals(server.address.host, row.server.address.host)
        assertEquals(ServerStatus.Connected, row.status)
        assertEquals("authenticate", server.terminalRequests.single()["type"]!!.jsonPrimitive.content)
    }

    @Test fun eachServerGetsItsOwnBadge() {
        FakeTroupeServer().use { vps ->
            val offline = FakeTroupeServer()
            val ok = paired(name = "Laptop")
            val stale = paired(name = "VPS", token = "stale", on = vps)
            val gone = paired(name = "Old", on = offline)
            offline.goOffline()

            val byId = shownRows().associate { it.server.id to it.status }
            assertEquals(3, byId.size)
            assertEquals(ServerStatus.Connected, byId[ok.id])
            assertEquals(ServerStatus.TokenRejected, byId[stale.id])
            assertEquals(ServerStatus.Unreachable, byId[gone.id])
        }
    }

    @Test fun renameUpdatesTheRowAndSurvivesRestart() {
        val s = paired(name = "Box")
        val vm = graph().serversViewModel()
        shownRows(vm)

        vm.rename(s.id, "  Laptop  ")
        assertEquals("Laptop", runBlocking { withTimeout(10_000) { vm.rows.first { it?.single()?.server?.name == "Laptop" } } }!!.single().server.name)

        graph().close()
        graph = null
        val afterRestart = shownRows().single()
        assertEquals(s.id, afterRestart.server.id)
        assertEquals("Laptop", afterRestart.server.name)
        assertEquals(server.token, runBlocking { graph().repository.token(s.id) })
    }

    @Test fun stayConnectedInBackgroundIsAPerServerToggleOffByDefault() {
        val s = paired(name = "Box")
        val vm = graph().serversViewModel()
        assertFalse(shownRows(vm).single().server.stayConnected)

        vm.setStayConnected(s.id, true)
        runBlocking { withTimeout(10_000) { vm.rows.first { it?.single()?.server?.stayConnected == true } } }

        vm.setStayConnected(s.id, false)
        runBlocking { withTimeout(10_000) { vm.rows.first { it?.single()?.server?.stayConnected == false } } }
    }

    @Test fun blankRenameIsIgnored() {
        val s = paired(name = "Box")
        val vm = graph().serversViewModel()
        vm.rename(s.id, "   ")
        assertEquals("Box", shownRows(vm).single().server.name)
    }

    @Test fun removeDeletesTheServerAndItsToken() {
        val other = FakeTroupeServer()
        val keep = paired(name = "Keep", on = other)
        other.goOffline()
        val s = paired(name = "Box")
        val vm = graph().serversViewModel()
        shownRows(vm)

        vm.remove(s.id)

        val rows = runBlocking { withTimeout(10_000) { vm.rows.first { it?.size == 1 } } }!!
        assertEquals(keep.id, rows.single().server.id)
        assertNull(runBlocking { graph().repository.token(s.id) })
        assertFalse("token gone from disk", graph().prefsFile.readText(Charsets.ISO_8859_1).contains("token_${s.id}"))
        assertTrue("other token kept", graph().prefsFile.readText(Charsets.ISO_8859_1).contains("token_${keep.id}"))
    }

    @Test fun rePairingTheSameAddressKeepsOneRowAndTheSameId() {
        val first = pairThroughThePairScreen()
        server.token = "rotated-token-9876543210"
        val second = pairThroughThePairScreen()

        assertEquals(first, second)
        val row = shownRows().single()
        assertEquals(first, row.server.id)
        assertEquals(ServerStatus.Connected, row.status)
        assertEquals("rotated-token-9876543210", runBlocking { graph().repository.token(first) })
    }

    @Test fun emptyListIsEmptyNotLoading() {
        assertEquals(emptyList<ServerRow>(), shownRows())
    }

    private fun pairThroughThePairScreen(): String {
        val vm = graph().pairViewModel()
        vm.onManual(server.typedAddress, server.token, "Box")
        val state = runBlocking { withTimeout(10_000) { vm.ui.first { it is PairUiState.Paired || it is PairUiState.Error } } }
        return (state as PairUiState.Paired).serverId
    }
}
