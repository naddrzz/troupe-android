package dev.troupe.android.terminal

import dev.troupe.android.R
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.FakeTroupeServer.Companion.dataFrame
import dev.troupe.android.testing.FakeTroupeServer.Companion.replayFrame
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.RecordingRenderer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What the screens do when the Paired Server's connection drops and comes back, against the fake
 * Troupe Server, with the connection lifecycle on virtual time.
 */
class TerminalReconnectTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root, StandardTestDispatcher(scheduler)) }
    private val renderer = RecordingRenderer()

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun until(condition: () -> Boolean) = eventually {
        scheduler.runCurrent()
        condition()
    }

    private fun advance(by: Duration) {
        scheduler.advanceTimeBy(by)
        scheduler.runCurrent()
    }

    private fun attaches() = server.terminalRequests("attach")

    private var serverId = ""

    /** Terminal t1: attach #1 replays `$ ` (seq 1), later attaches replay what [later] returns. */
    private fun openTerminal(later: (attach: JsonObject) -> JsonObject = { replayFrame("t1", firstSeq = 2) }): TerminalViewModel {
        server.onTerminalRequest = { type, payload ->
            if (type == "attach") {
                server.sendTerminalFrame(if (attaches().size == 1) replayFrame("t1", "$ ".toByteArray()) else later(payload))
            }
            JsonObject(emptyMap())
        }
        serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        runBlocking { graph.cursors.putClaim(serverId, "t1", "claim-t1") }
        val vm = graph.terminalViewModel(serverId, "t1")
        vm.bind(renderer)
        until { vm.ui.value == TerminalUi.Live && renderer.text == "$ " }
        return vm
    }

    @Test fun aReconnectReattachesFromTheCursorAndDrawsNothingTwice() {
        val vm = openTerminal { replayFrame("t1", "c".toByteArray(), firstSeq = 3) }
        server.sendTerminalFrame(dataFrame("t1", 2, "b".toByteArray()))
        until { renderer.text == "$ b" }

        server.closeTerminalSockets(1011)
        until { vm.connection.value == ConnectionState.Reconnecting(attempt = 1, inMillis = 500) }
        advance(500.milliseconds)

        until { attaches().size == 2 }
        assertEquals("claim-t1", attaches()[1].payloadString("claim"))
        assertEquals(2, attaches()[1].payloadInt("lastSeq"))
        assertEquals("ep-1", attaches()[1].payloadString("logEpoch"))
        until { renderer.text == "$ bc" && vm.connection.value == ConnectionState.Connected }
        server.sendTerminalFrame(dataFrame("t1", 4, "d".toByteArray()))
        until { renderer.text == "$ bcd" }
        Thread.sleep(200)
        assertEquals("$ bcd", renderer.text)
        assertFalse(renderer.calls.contains(RecordingRenderer.RESET))
        assertEquals(TerminalUi.Live, vm.ui.value)
    }

    @Test fun theHeaderFollowsAServerThatStopsAndComesBack() {
        val vm = openTerminal()
        assertEquals(ConnectionState.Connected, vm.connection.value)

        server.closeTerminalSockets(1001)
        until { vm.connection.value == ConnectionState.ServerOffline }
        // What is on screen stays, and nothing is re-attached until the server is back.
        assertEquals(TerminalUi.Live, vm.ui.value)
        assertEquals("$ ", renderer.text)

        vm.retryConnection()

        until { vm.connection.value == ConnectionState.Connected && attaches().size == 2 }
    }

    @Test fun aTokenRejectedOnReconnectShowsThePairingMessageAndStopsReconnecting() {
        val vm = openTerminal()
        server.token = "rotated"

        server.closeTerminalSockets(1011)
        until { vm.connection.value is ConnectionState.Reconnecting }
        advance(500.milliseconds)

        until { vm.ui.value == TerminalUi.Failed(R.string.pair_error_unauthorized) }
        advance(10.minutes)
        assertEquals(2, server.terminalUpgrades.size)
        assertEquals(1, attaches().size)
    }

    @Test fun aReconnectDoesNotListTheTerminalsAgainSoTheClaimIsNotReissued() {
        val vm = openTerminal()
        val terminals = graph.terminalsViewModel(serverId, "p1")
        until { terminals.connection.value == ConnectionState.Connected }

        server.closeTerminalSockets(1011)
        until { vm.connection.value is ConnectionState.Reconnecting }
        advance(500.milliseconds)

        until { attaches().size == 2 && vm.connection.value == ConnectionState.Connected }
        Thread.sleep(200)
        scheduler.runCurrent()
        assertEquals(0, server.terminalRequests("list_preserved").size)
        assertEquals(ConnectionState.Connected, terminals.connection.value)
    }
}