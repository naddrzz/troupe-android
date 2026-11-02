package dev.troupe.android.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A socket [ConnectionLifecycle] opens, watches and reopens, one connection at a time. */
interface LifecycleSocket {
    /** Opens a new connection and runs its handshake: [ConnectionState.Connected] or [ConnectionState.Failed]. */
    suspend fun open(): ConnectionState

    /** Suspends until the connection [open] made ends; its close code, or null when it failed without one. */
    suspend fun awaitClose(): Int?

    /** Drops the connection now, as for a dead peer; [awaitClose] then returns. */
    fun abort()

    /** Emits on every frame the server sends: proof the connection is alive. */
    val activity: Flow<Unit>
}

/**
 * Keeps one [LifecycleSocket] connected (spec "Connection lifecycle"):
 * - an abnormal close or a failed attempt reconnects after a [Backoff] delay;
 * - with a [heartbeat], 2.5 intervals without any server frame drop the connection, which then reconnects;
 * - close code 1001 is [ConnectionState.ServerOffline]: no reconnect until `/health` ([healthy]) answers,
 *   asked every 15 s and at once on [retry];
 * - a rejected token or protocol mismatch is final.
 *
 * Every successful handshake bumps [generation]: the server forgets a connection's attachments and
 * subscriptions, so users re-attach or resubscribe when it changes.
 */
class ConnectionLifecycle(
    private val socket: LifecycleSocket,
    private val healthy: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val heartbeat: Duration? = null,
    private val backoff: Backoff = Backoff(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** How many times the socket has connected; 0 until the first handshake succeeds. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Starts connecting; does nothing once started. */
    @Synchronized
    fun start() {
        if (job == null) job = scope.launch { run() }
    }

    /** Reconnects or checks `/health` now instead of waiting: the user's Retry, or a network coming back. */
    fun retry() {
        wake.trySend(Unit)
    }

    /** Stops for good and drops the connection. */
    @Synchronized
    fun stop() {
        job?.cancel()
        socket.abort()
        _state.value = ConnectionState.Closed
    }

    private suspend fun run() {
        var attempt = 0
        _state.value = ConnectionState.Connecting
        while (true) {
            when (val opened = socket.open()) {
                ConnectionState.Connected -> {
                    attempt = 0
                    backoff.reset()
                    wake.tryReceive()
                    _state.value = ConnectionState.Connected
                    _generation.value++
                    if (watch() == GOING_AWAY) {
                        awaitHealthy()
                        _state.value = ConnectionState.Connecting
                        continue
                    }
                }
                is ConnectionState.Failed -> if (opened.reason != ConnectionFailure.Unreachable) {
                    _state.value = opened
                    return
                }
                else -> Unit
            }
            attempt++
            val wait = backoff.next()
            _state.value = ConnectionState.Reconnecting(attempt, wait.inWholeMilliseconds)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Waits for the connection to end, dropping it when the server falls silent; returns the close code. */
    private suspend fun watch(): Int? = coroutineScope {
        val watchdog = heartbeat?.let { interval ->
            launch {
                socket.activity.onStart { emit(Unit) }.collectLatest {
                    delay(interval * 2.5)
                    socket.abort()
                }
            }
        }
        try {
            socket.awaitClose()
        } finally {
            watchdog?.cancel()
        }
    }

    private suspend fun awaitHealthy() {
        _state.value = ConnectionState.ServerOffline
        while (true) {
            withTimeoutOrNull(HEALTH_POLL) { wake.receive() }
            if (withTimeoutOrNull(HEALTH_BUDGET) { healthy() } == true) return
        }
    }

    companion object {
        /** The close code of a server that stops on purpose. */
        const val GOING_AWAY = 1001
        val HEALTH_POLL = 15.seconds
        val HEALTH_BUDGET = 5.seconds
    }
}
