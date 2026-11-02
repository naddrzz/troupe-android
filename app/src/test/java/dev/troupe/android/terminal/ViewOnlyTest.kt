package dev.troupe.android.terminal

import dev.troupe.android.pairing.PairUiState
import dev.troupe.android.projects.ProjectsUi
import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalKey
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.FakeTroupeServer.Companion.replayFrame
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.RecordingRenderer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadString
import dev.troupe.android.testing.spawnedTerminal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * View-only Paired Servers against the fake Troupe Server, which refuses every write with
 * `FORBIDDEN` while `allowRemoteWrites` is false: the app knows, says so, and sends no write.
 */
class ViewOnlyTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val renderer = RecordingRenderer()

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    /** A Paired Server holding Terminal t1; attaching replays a prompt. */
    private fun pairedServer(): String {
        server.onTerminalRequest = { type, _ ->
            if (type == "attach") server.sendTerminalFrame(replayFrame("t1", "$ ".toByteArray()))
            if (type == "spawn") spawnedTerminal(id = "t2", claim = "claim-t2") else JsonObject(emptyMap())
        }
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        runBlocking { graph.cursors.putClaim(serverId, "t1", "claim-t1") }
        return serverId
    }

    private fun openTerminal(serverId: String): TerminalViewModel {
        val vm = graph.terminalViewModel(serverId, "t1")
        vm.bind(renderer)
        eventually { vm.ui.value == TerminalUi.Live && renderer.text == "$ " }
        return vm
    }

    private fun await(flow: StateFlow<Boolean>, value: Boolean) =
        runBlocking { withTimeout(10_000) { flow.first { it == value } } }

    private fun sent(vararg types: String) =
        server.terminalRequests.map { it.getValue("type").jsonPrimitive.content }.filter { it in types }

    /** Gives in-flight requests time to reach the server, so an assertion that nothing was sent means something. */
    private fun settle() = Thread.sleep(300)

    /**
     * Makes [serverId] writable again and types a marker. Input goes out in order, so once the marker
     * reached the server every write typed before it has too: the writes up to here, then the marker.
     */
    private fun writesThrough(serverId: String, vm: TerminalViewModel): List<String?> {
        server.allowRemoteWrites = true
        graph.viewOnly.set(serverId, false)
        // The ViewModel sees the registry asynchronously; typing before it does would drop the marker.
        await(vm.viewOnly, false)
        renderer.type(MARKER)
        eventually { server.terminalRequests("write").any { it.payloadString("data") == MARKER } }
        return server.terminalRequests("write").map { it.payloadString("data") }.dropLast(1)
    }

    @Test fun pairingWithAServerThatOnlyLetsDevicesWatchMarksItViewOnly() {
        server.allowRemoteWrites = false
        val vm = graph.pairViewModel()

        vm.onManual(server.typedAddress, server.token, "Box")

        val paired = runBlocking { withTimeout(10_000) { vm.ui.first { it is PairUiState.Paired } } } as PairUiState.Paired
        assertTrue(graph.viewOnly.state(paired.serverId).value)
    }

    @Test fun everyConnectReadsViewOnlyFromHealth() {
        server.allowRemoteWrites = false
        val serverId = pairedServer()

        val projects = graph.projectsViewModel(serverId)
        await(projects.viewOnly, true)

        // The server restarts with --allow-remote-writes.
        server.allowRemoteWrites = true
        server.closeTerminalSockets(1011)
        await(projects.viewOnly, false)
        assertTrue(runBlocking { withTimeout(10_000) { projects.ui.first { it !is ProjectsUi.Loading } } } is ProjectsUi.Loaded)
    }

    @Test fun aViewOnlyTerminalShowsOutputButSendsNoWriteOrResize() {
        server.allowRemoteWrites = false
        val serverId = pairedServer()
        val vm = openTerminal(serverId)
        await(vm.viewOnly, true)

        renderer.type("ls\r")
        vm.onKey(TerminalKey.Esc)
        vm.paste("rm -rf build")
        renderer.size.value = TermSize(100, 30)
        // A resize would only go out after the debounce: no request marks it, so wait past it.
        settle()

        assertEquals(emptyList<String>(), sent("resize"))
        assertEquals(TerminalUi.Live, vm.ui.value)
        assertEquals(emptyList<String>(), writesThrough(serverId, vm))
    }

    @Test fun aForbiddenWriteSwitchesToViewOnlyAtOnceAndStopsWriting() {
        val serverId = pairedServer()
        val vm = openTerminal(serverId)
        eventually { server.healthChecks() >= 1 }
        settle()

        // The server's policy changes under the app: it now refuses writes.
        server.allowRemoteWrites = false
        renderer.type("ls\r")
        await(vm.viewOnly, true)
        renderer.type("pwd\r")
        vm.onKey(TerminalKey.Tab)

        assertEquals(listOf("ls\r"), writesThrough(serverId, vm))
    }

    @Test fun aWritableTerminalStillWritesAndResizes() {
        val vm = openTerminal(pairedServer())
        eventually { server.healthChecks() >= 1 }
        assertFalse(vm.viewOnly.value)

        renderer.type("ls\r")
        renderer.size.value = TermSize(100, 30)

        eventually { sent("write", "resize").toSet() == setOf("write", "resize") }
    }

    @Test fun aViewOnlyProjectNeitherListsSpawnsNorKills() {
        server.allowRemoteWrites = false
        val serverId = pairedServer()
        val vm = graph.terminalsViewModel(serverId, projectId = "p1")
        await(vm.viewOnly, true)

        // Each returns before launching anything while View-only, so nothing can be in flight.
        vm.refresh()
        vm.spawn()
        vm.kill("t1")

        assertEquals(emptyList<String>(), sent("list_preserved", "spawn", "attach", "kill"))
        assertFalse(vm.ui.value.loading)
    }

    @Test fun aForbiddenSpawnSwitchesTheProjectToViewOnly() {
        val serverId = pairedServer()
        val vm = graph.terminalsViewModel(serverId, projectId = "p1")
        eventually { server.healthChecks() >= 1 }
        settle()

        server.allowRemoteWrites = false
        vm.spawn()

        await(vm.viewOnly, true)
        assertTrue(graph.viewOnly.state(serverId).value)
    }

    private companion object {
        const val MARKER = "marker"
    }
}
