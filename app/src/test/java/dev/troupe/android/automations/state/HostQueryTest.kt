package dev.troupe.android.automations.state

import dev.troupe.android.automations.protocol.AutomationChanged
import dev.troupe.android.core.net.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [HostQuery] against scripted loads and a hand-fed event stream, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostQueryTest {
    private val events = MutableSharedFlow<AutomationEvent>()

    /** Replies with the next scripted result at once, or, when none is left, waits for [gates]. */
    private class FakeLoad<T>(vararg scripted: QueryResult<T>) {
        private val replies = ArrayDeque(scripted.toList())
        val gates = mutableListOf<CompletableDeferred<QueryResult<T>>>()
        var calls = 0
            private set

        suspend fun load(): QueryResult<T> {
            calls++
            replies.removeFirstOrNull()?.let { return it }
            return CompletableDeferred<QueryResult<T>>().also { gates += it }.await()
        }
    }

    /** Counts its calls and answers each with the call number. */
    private class CountingLoad {
        var calls = 0
            private set

        suspend fun load(): QueryResult<Int> = QueryResult.Ok(++calls)
    }

    private fun <T> TestScope.query(load: suspend () -> QueryResult<T>, poll: Poll? = null) =
        HostQuery(backgroundScope, events, load, { it.projectId == "p1" }, poll)
            .also { runCurrent() }

    private fun changed(projectId: String) = AutomationEvent.Changed(AutomationChanged(projectId, "a1"))

    @Test fun loadsOnceOnStart() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()

        val query = HostQuery(backgroundScope, events, load::load, { true })
        assertEquals(QueryState<Int>(), query.state.value)
        runCurrent()

        assertEquals(1, load.calls)
        assertEquals(QueryState(data = 1, loaded = true), query.state.value)
    }

    @Test fun matchingChangeReloads() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        val query = query(load::load)

        events.emit(changed("p1"))
        runCurrent()

        assertEquals(2, load.calls)
        assertEquals(QueryState(data = 2, loaded = true), query.state.value)
    }

    @Test fun otherProjectChangeDoesNotReload() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        query(load::load)

        events.emit(changed("p2"))
        runCurrent()

        assertEquals(1, load.calls)
    }

    @Test fun resyncReloads() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        val query = query(load::load)

        events.emit(AutomationEvent.Resync)
        runCurrent()

        assertEquals(2, load.calls)
        assertEquals(2, query.state.value.data)
    }

    @Test fun slowOlderReplyIsDropped() = runTest(StandardTestDispatcher()) {
        val load = FakeLoad(QueryResult.Ok("first"))
        val query = query(load::load)

        query.reload()
        query.reload()
        runCurrent()
        assertEquals(QueryState(data = "first", loaded = true, refreshing = true), query.state.value)

        load.gates[1].complete(QueryResult.Ok("newer"))
        runCurrent()
        load.gates[0].complete(QueryResult.Ok("older"))
        runCurrent()

        assertEquals(3, load.calls)
        assertEquals(QueryState(data = "newer", loaded = true), query.state.value)
    }

    @Test fun failedRefreshKeepsLastGoodData() = runTest(StandardTestDispatcher()) {
        val load = FakeLoad(
            QueryResult.Ok("good"),
            QueryResult.Failed(QueryError.Unreachable),
            QueryResult.Ok("better"),
        )
        val query = query(load::load)

        query.reload()
        runCurrent()
        assertEquals(QueryState(data = "good", error = QueryError.Unreachable, loaded = true), query.state.value)

        query.reload()
        runCurrent()
        assertEquals(QueryState(data = "better", loaded = true), query.state.value)
    }

    @Test fun pollTicksOnlyWhileActive() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        var active = false
        query(load::load, Poll(everyMs = 1_000) { active })

        advanceTimeBy(3_500)
        assertEquals(1, load.calls)

        active = true
        advanceTimeBy(3_000)
        assertEquals(4, load.calls)
    }

    @Test fun pollSkipsWhileLoadInFlight() = runTest(StandardTestDispatcher()) {
        val load = FakeLoad<String>()
        query(load::load, Poll(everyMs = 1_000) { true })

        advanceTimeBy(5_000)
        assertEquals(1, load.calls)

        load.gates[0].complete(QueryResult.Ok("done"))
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(2, load.calls)
    }

    @Test fun reloadUnlessEventWithinSkipsWhenEventArrives() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        val query = query(load::load)

        query.reloadUnlessEventWithin(5_000)
        advanceTimeBy(1_000)
        events.emit(changed("p1"))
        runCurrent()
        assertEquals(2, load.calls)

        advanceTimeBy(10_000)
        assertEquals(2, load.calls)
    }

    @Test fun reloadUnlessEventWithinReloadsWhenQuiet() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        val query = query(load::load)

        query.reloadUnlessEventWithin(5_000)
        advanceTimeBy(1_000)
        // An event that does not reload this query does not count.
        events.emit(changed("p2"))
        advanceTimeBy(3_999)
        assertEquals(1, load.calls)

        advanceTimeBy(2)
        assertEquals(2, load.calls)
        assertEquals(2, query.state.value.data)
    }

    @Test fun closeStopsEverything() = runTest(StandardTestDispatcher()) {
        val load = CountingLoad()
        val query = query(load::load, Poll(everyMs = 1_000) { true })

        query.close()
        events.emit(changed("p1"))
        events.emit(AutomationEvent.Resync)
        query.reload()
        query.reloadUnlessEventWithin(1_000)
        advanceTimeBy(10_000)

        assertEquals(1, load.calls)
        assertEquals(QueryState(data = 1, loaded = true), query.state.value)
    }

    @Test fun replyAfterCloseChangesNothing() = runTest(StandardTestDispatcher()) {
        val load = FakeLoad<String>()
        val query = query(load::load)

        query.close()
        load.gates[0].complete(QueryResult.Ok("late"))
        runCurrent()

        assertEquals(QueryState<String>(), query.state.value)
    }

    @Test fun toQueryMapping() {
        assertEquals(QueryResult.Ok(7), ApiResult.Ok(7).toQuery())
        assertEquals(QueryResult.Failed(QueryError.Unauthorized), ApiResult.Unauthorized.toQuery())
        assertEquals(QueryResult.Failed(QueryError.Unreachable), ApiResult.Unreachable("timeout").toQuery())
        assertEquals(
            QueryResult.Failed(QueryError.NotApproved),
            ApiResult.ServerError("needs approval", "not_approved").toQuery(),
        )
        assertEquals(
            QueryResult.Failed(QueryError.NotApproved),
            ApiResult.ServerError("needs approval", "not_approved", status = 403).toQuery(),
        )
        assertEquals(
            QueryResult.Failed(QueryError.Server("missing", null)),
            ApiResult.ServerError("missing", null, status = 404).toQuery(),
        )
        assertEquals(
            QueryResult.Failed(QueryError.Unsupported),
            ApiResult.ServerError("missing", null, status = 404).toQuery(unsupportedOn404 = true),
        )
        assertEquals(
            QueryResult.Failed(QueryError.Invalid),
            ApiResult.ServerError("bad reply", null, status = 200).toQuery(),
        )
        assertEquals(
            QueryResult.Failed(QueryError.Server("boom", "internal")),
            ApiResult.ServerError("boom", "internal", status = 500).toQuery(),
        )
        assertEquals(
            QueryResult.Failed(QueryError.Server("refused", "conflict")),
            ApiResult.ServerError("refused", "conflict").toQuery(),
        )
    }
}
