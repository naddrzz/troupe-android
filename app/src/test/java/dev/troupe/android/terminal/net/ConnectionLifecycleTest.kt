package dev.troupe.android.terminal.net

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.Backoff
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionLifecycle
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The connection lifecycle of a Paired Server's `/terminal/ws`, against the fake Troupe Server.
 * Backoff, heartbeat and `/health` polling run on virtual time; the sockets are real, so each step
 * waits in real time for the network while running whatever coroutines are due.
 */
class ConnectionLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root, dispatcher) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After fun tearDown() {
        scope.cancel()
        graph.close()
        server.close()
    }

    /** Runs due coroutines until [condition] holds, waiting in real time for the network in between. */
    private fun until(condition: () -> Boolean) = eventually(message = { "state: ${state()}" }) {
        scheduler.runCurrent()
        condition()
    }

    private fun advance(by: Duration) {
        scheduler.advanceTimeBy(by)
        scheduler.runCurrent()
    }

    private lateinit var connection: TerminalConnection
    private fun state() = if (::connection.isInitialized) connection.state.value else null

    private fun connect(): TerminalConnection {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        connection = runBlocking { graph.connections.connection(serverId) }
        until { state() == ConnectionState.Connected }
        return connection
    }

    private fun authentications() = server.terminalRequests("authenticate").size

    /**
     * Waits until the server has answered `/health` [times] times, then a moment more for the
     * answer to reach the lifecycle, so the next 15 s wait starts before virtual time moves on.
     */
    private fun checkedHealth(times: Int) {
        until { server.healthChecks() == times }
        repeat(10) {
            Thread.sleep(20)
            scheduler.runCurrent()
        }
    }

    @Test fun connectsAndAuthenticates() {
        connect()
        assertEquals(1, authentications())
        assertEquals(1, connection.generation.value)
    }

    @Test fun anAbnormalCloseReconnectsAfterTheBackoffAndAuthenticatesAgain() {
        connect()

        server.closeTerminalSockets(1011)

        until { state() == ConnectionState.Reconnecting(attempt = 1, inMillis = 500) }
        advance(499.milliseconds)
        assertEquals(1, server.terminalUpgrades.size)
        advance(1.milliseconds)
        until { state() == ConnectionState.Connected }
        assertEquals(2, authentications())
        assertEquals(2, connection.generation.value)
    }

    @Test fun failedAttemptsBackOffFurtherUpToThirtySecondsThenResetOnceConnected() {
        connect()
        server.goOffline()

        val waits = mutableListOf<Long>()
        for (attempt in 1..8) {
            until { (state() as? ConnectionState.Reconnecting)?.attempt == attempt }
            val wait = (state() as ConnectionState.Reconnecting).inMillis
            waits += wait
            advance(wait.milliseconds)
        }
        assertEquals(listOf(500L, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), waits)

        until { (state() as? ConnectionState.Reconnecting)?.attempt == 9 }
        server.comeBack()
        advance(30.seconds)
        until { state() == ConnectionState.Connected }

        server.closeTerminalSockets(1011)
        until { state() == ConnectionState.Reconnecting(attempt = 1, inMillis = 500) }
    }

    @Test fun retryWhileReconnectingSkipsTheWait() {
        connect()
        server.closeTerminalSockets(1011)
        server.goOffline()
        for (attempt in 1..4) {
            until { (state() as? ConnectionState.Reconnecting)?.attempt == attempt }
            advance((state() as ConnectionState.Reconnecting).inMillis.milliseconds)
        }
        until { (state() as? ConnectionState.Reconnecting)?.attempt == 5 }
        server.comeBack()

        connection.retry()

        until { state() == ConnectionState.Connected }
    }

    @Test fun close1001IsServerOfflineAndPollsHealthEveryFifteenSecondsWithoutReconnecting() {
        connect()
        server.healthy = false

        server.closeTerminalSockets(1001)

        until { state() == ConnectionState.ServerOffline }
        advance(14.seconds)
        assertEquals(0, server.healthChecks())
        advance(1.seconds)
        checkedHealth(times = 1)
        // No backoff loop while offline: only /health, every 15 s, and the socket is not reopened.
        for (n in 2..20) {
            advance(14.seconds)
            assertEquals(n - 1, server.healthChecks())
            advance(1.seconds)
            checkedHealth(times = n)
        }
        assertEquals(ConnectionState.ServerOffline, state())
        assertEquals(1, server.terminalUpgrades.size)

        server.healthy = true
        advance(15.seconds)

        until { state() == ConnectionState.Connected }
        assertEquals(2, authentications())
    }

    @Test fun retryWhileOfflineChecksHealthAtOnce() {
        connect()
        server.closeTerminalSockets(1001)
        until { state() == ConnectionState.ServerOffline }

        connection.retry()

        until { state() == ConnectionState.Connected }
        assertEquals(1, server.healthChecks())
    }

    @Test fun aNetworkComingBackChecksHealthAtOnce() {
        connect()
        server.closeTerminalSockets(1001)
        until { state() == ConnectionState.ServerOffline }

        graph.network.becameAvailable()

        until { state() == ConnectionState.Connected }
        assertEquals(1, server.healthChecks())
    }

    @Test fun aServerThatStopsThenRestartsIsReconnectedOnceHealthAnswers() {
        connect()
        server.closeTerminalSockets(1001)
        until { state() == ConnectionState.ServerOffline }
        server.goOffline()
        advance(15.seconds)
        Thread.sleep(200)
        scheduler.runCurrent()
        assertEquals(ConnectionState.ServerOffline, state())

        server.comeBack()
        advance(15.seconds)

        until { state() == ConnectionState.Connected }
    }

    @Test fun aRejectedTokenStopsReconnecting() {
        connect()
        server.token = "rotated"

        server.closeTerminalSockets(1011)
        until { state() is ConnectionState.Reconnecting }
        advance(500.milliseconds)

        until { state() == ConnectionState.Failed(ConnectionFailure.Unauthorized, "Unauthorized") }
        advance(10.minutes)
        connection.retry()
        advance(1.minutes)
        assertEquals(2, server.terminalUpgrades.size)
        assertEquals(ConnectionFailure.Unauthorized, (state() as ConnectionState.Failed).reason)
    }

    @Test fun aProtocolMismatchStopsReconnecting() {
        connect()
        server.protocolVersion = 8

        server.closeTerminalSockets(1011)
        until { state() is ConnectionState.Reconnecting }
        advance(500.milliseconds)

        until { (state() as? ConnectionState.Failed)?.reason == ConnectionFailure.ProtocolMismatch }
        advance(10.minutes)
        assertEquals(2, server.terminalUpgrades.size)
    }

    @Test fun aSocketThatStaysSilentPastTwoAndAHalfHeartbeatsIsReopened() {
        val socket = graph.sockets.create(server.address, server.token)
        val frames = CopyOnWriteArrayList<Any>()
        scope.launch { socket.frames.collect { frames += it } }
        val lifecycle = ConnectionLifecycle(
            socket,
            healthy = { graph.api.health(server.address) is ApiResult.Ok },
            scope = scope,
            heartbeat = 10.seconds,
            backoff = Backoff(jitter = 0.0),
        )
        lifecycle.start()
        until { lifecycle.state.value == ConnectionState.Connected }

        // Any frame proves the connection is alive and restarts the clock.
        advance(20.seconds)
        server.sendTerminalFrame(buildJsonObject { put("type", "heartbeat") })
        until { frames.size == 1 }
        advance(24.seconds)
        assertEquals(ConnectionState.Connected, lifecycle.state.value)
        assertEquals(1, server.terminalUpgrades.size)

        advance(1.seconds)

        until { lifecycle.state.value == ConnectionState.Reconnecting(attempt = 1, inMillis = 500) }
        advance(500.milliseconds)
        until { lifecycle.state.value == ConnectionState.Connected }
        assertEquals(2, server.terminalUpgrades.size)
    }
}
