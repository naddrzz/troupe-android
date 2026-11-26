package dev.troupe.android.automations.state

import dev.troupe.android.automations.protocol.AutomationChanged
import dev.troupe.android.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface QueryError {
    data object Unauthorized : QueryError
    data object Unreachable : QueryError
    /** The server has no such endpoint (an older Troupe Server). */
    data object Unsupported : QueryError
    data object NotApproved : QueryError
    /** The reply did not match the contract. */
    data object Invalid : QueryError
    data class Server(val message: String, val code: String?) : QueryError
}

sealed interface QueryResult<out T> {
    data class Ok<T>(val value: T) : QueryResult<T>
    data class Failed(val error: QueryError) : QueryResult<Nothing>
}

fun <T> ApiResult<T>.toQuery(unsupportedOn404: Boolean = false): QueryResult<T> = when (this) {
    is ApiResult.Ok -> QueryResult.Ok(value)
    ApiResult.Unauthorized -> QueryResult.Failed(QueryError.Unauthorized)
    is ApiResult.Unreachable -> QueryResult.Failed(QueryError.Unreachable)
    is ApiResult.ServerError -> QueryResult.Failed(
        when {
            code == "not_approved" -> QueryError.NotApproved
            status == 404 && unsupportedOn404 -> QueryError.Unsupported
            status in 200..299 -> QueryError.Invalid
            else -> QueryError.Server(message, code)
        },
    )
}

/** [error] sits next to the last good [data]; [loaded] is set by the first reply, [refreshing] while a later load runs. */
data class QueryState<T>(
    val data: T? = null,
    val error: QueryError? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
)

/** Reload every [everyMs] while [active] holds; a tick is skipped while a load is in flight. */
class Poll(val everyMs: Long, val active: () -> Boolean)

/**
 * Port of the web's `useHostQuery`: loads once, then again on every change event that [affects]
 * accepts, on every resync and on [poll] ticks. Only the latest request's reply is kept, and a failed
 * load keeps the last good data. When the watch comes up ([AutomationEvent.Watching]) a load that could
 * not reach the server is tried once more, so a query opened offline recovers. Everything runs in a
 * child of [scope]; [close] (or cancelling [scope]) stops it.
 */
class HostQuery<T>(
    scope: CoroutineScope,
    events: Flow<AutomationEvent>,
    private val load: suspend () -> QueryResult<T>,
    affects: (AutomationChanged) -> Boolean,
    poll: Poll? = null,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val queryScope = CoroutineScope(scope.coroutineContext + job)
    private val _state = MutableStateFlow(QueryState<T>())
    val state: StateFlow<QueryState<T>> = _state.asStateFlow()

    // Guarded by lock; loads, events, ticks and callers may run on different threads.
    private val lock = Any()
    private var request = 0
    /** The request whose reply the state shows (successful or not). */
    private var answered = 0
    /** Loads on the wire, superseded ones included: the poll waits for all of them. */
    private var inFlight = 0
    /** The watch came up while a load was in flight: if that load cannot reach the server, it is tried once more. */
    private var retryIfUnreachable = false
    private var closed = false
    private var eventReloads = 0

    init {
        reload()
        queryScope.launch {
            events.collect { event ->
                val hit = when (event) {
                    is AutomationEvent.Changed -> falseOnThrow { affects(event.change) }
                    AutomationEvent.Resync -> true
                    AutomationEvent.Watching -> synchronized(lock) {
                        if (inFlight > 0) {
                            retryIfUnreachable = true
                            false
                        } else {
                            _state.value.error == QueryError.Unreachable
                        }
                    }
                }
                if (hit) {
                    synchronized(lock) {
                        eventReloads++
                        begin()
                    }?.let(::fetch)
                }
            }
        }
        if (poll != null) {
            queryScope.launch {
                while (true) {
                    delay(poll.everyMs)
                    if (falseOnThrow(poll.active)) synchronized(lock) { if (inFlight > 0) null else begin() }?.let(::fetch)
                }
            }
        }
    }

    fun reload() {
        begin()?.let(::fetch)
    }

    /** Reloads after [windowMs] unless a change event reloads this query first. */
    fun reloadUnlessEventWithin(windowMs: Long) {
        val seen = synchronized(lock) { eventReloads }
        queryScope.launch {
            delay(windowMs)
            synchronized(lock) { if (eventReloads == seen) begin() else null }?.let(::fetch)
        }
    }

    /** A mark for [answeredSince]: the latest request begun so far. */
    fun requestMark(): Int = synchronized(lock) { request }

    /** Whether the state now shows the reply to a request begun after [mark], so it cannot predate that moment. */
    fun answeredSince(mark: Int): Boolean = synchronized(lock) { answered > mark }

    fun close() {
        synchronized(lock) {
            closed = true
            request++
        }
        job.cancel()
    }

    /** Caller predicates run in supervisor children, where a throw would crash the app; a throw counts as false. */
    private inline fun falseOnThrow(predicate: () -> Boolean): Boolean = try {
        predicate()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Claims a new request id, counted in flight until its [fetch] ends, or null once closed. */
    private fun begin(): Int? = synchronized(lock) {
        if (closed) return null
        inFlight++
        _state.update { if (it.loaded) it.copy(refreshing = true) else it }
        ++request
    }

    private fun fetch(id: Int) {
        queryScope.launch {
            try {
                // An exception escaping this supervisor child would crash the app, so an unexpected one is
                // reported as a reply the app could not make sense of.
                val result = try {
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    QueryResult.Failed(QueryError.Invalid)
                }
                val retry = synchronized(lock) {
                    if (closed || id != request) return@launch
                    answered = id
                    val again = retryIfUnreachable && result == QueryResult.Failed(QueryError.Unreachable)
                    retryIfUnreachable = false
                    _state.update { prev ->
                        when (result) {
                            is QueryResult.Ok -> QueryState(data = result.value, loaded = true)
                            is QueryResult.Failed -> prev.copy(error = result.error, loaded = true, refreshing = false)
                        }
                    }
                    again
                }
                if (retry) reload()
            } finally {
                synchronized(lock) { inFlight-- }
            }
        }
    }
}
