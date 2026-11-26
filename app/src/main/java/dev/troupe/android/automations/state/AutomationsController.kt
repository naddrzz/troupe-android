package dev.troupe.android.automations.state

import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationDetail
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.automations.protocol.RunStatus
import dev.troupe.android.automations.ui.canRunNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

enum class ControlAction { Enable, Run, Cancel, Approve, Reject }

/** A mutating control; [target] is the automation id for [ControlAction.Enable] and [ControlAction.Run], else the run id. */
data class Control(val target: String, val action: ControlAction)

sealed interface Route {
    data object Overview : Route

    data class Detail(val id: AutomationId) : Route

    data class Run(val id: AutomationId, val runId: RunId) : Route
}

enum class Notice { ApprovalAlreadyHandled }

/**
 * What the Automations screens show. [detail] and [runs] are set from the detail route on, [steps] on the run route.
 * [enabledOverride] is a switch's new value until the server's reload shows it; it wins over the loaded `enabled`.
 */
data class AutomationsUi(
    val route: Route,
    val list: QueryState<List<Automation>>,
    val detail: QueryState<AutomationDetail>?,
    val runs: QueryState<List<AutomationRun>>?,
    val steps: QueryState<List<AutomationStep>>?,
    val pending: Set<Control>,
    val failures: Map<Control, QueryError>,
    val enabledOverride: Map<AutomationId, Boolean>,
    val notices: List<Notice>,
)

/**
 * The Automations of one project: the list, plus the detail and run the user opened, and the controls. Each route's
 * queries are opened on entering it and closed on leaving it. A control is disabled while its request is in flight
 * and keeps its failure until it is used again. After a write the screen waits for the server's change event, or
 * re-reads once if none arrives within [REREAD_AFTER_MS]; after a failed write it re-reads at once, since a lost reply
 * may hide a write that was applied. Everything runs in a child of [scope]; [close] stops it.
 */
class AutomationsController(
    private val repo: AutomationsRepository,
    projectId: String,
    scope: CoroutineScope,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(scope.coroutineContext + job)

    /** The open route and its queries; [seen] marks failures seen once per opening of a detail. */
    private data class Open(
        val route: Route,
        val detail: HostQuery<AutomationDetail>? = null,
        val runs: HostQuery<List<AutomationRun>>? = null,
        val steps: HostQuery<List<AutomationStep>>? = null,
        val seen: Job? = null,
    ) {
        val automationId: AutomationId?
            get() = when (route) {
                Route.Overview -> null
                is Route.Detail -> route.id
                is Route.Run -> route.id
            }

        fun closeAll() {
            seen?.cancel()
            detail?.close()
            runs?.close()
            steps?.close()
        }
    }

    private data class Controls(
        val pending: Set<Control> = emptySet(),
        val failures: Map<Control, QueryError> = emptyMap(),
        val enabledOverride: Map<AutomationId, Boolean> = emptyMap(),
        val notices: List<Notice> = emptyList(),
    )

    // Guarded by lock: callers, replies and events may run on different threads.
    private val lock = Any()
    private var closed = false
    private var overrideSeq = 0L
    /** The toggle that set each override, so an older toggle's settling never drops a newer override. */
    private val overrideOwner = HashMap<AutomationId, Long>()

    private val list = repo.list(projectId)
    private val current = MutableStateFlow(Open(Route.Overview))
    private val controls = MutableStateFlow(Controls())

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<AutomationsUi> = current
        .flatMapLatest { o ->
            combine(list.state, o.detail.stateOrNull(), o.runs.stateOrNull(), o.steps.stateOrNull(), controls) { l, d, r, s, c ->
                AutomationsUi(o.route, l, d, r, s, c.pending, c.failures, c.enabledOverride, c.notices)
            }
        }
        .stateIn(
            controllerScope,
            SharingStarted.Eagerly,
            AutomationsUi(Route.Overview, list.state.value, null, null, null, emptySet(), emptyMap(), emptyMap(), emptyList()),
        )

    fun open(id: AutomationId) {
        synchronized(lock) {
            if (closed) return
            val cur = current.value
            when {
                cur.route == Route.Detail(id) -> Unit
                cur.automationId == id -> {
                    cur.steps?.close()
                    current.value = cur.copy(route = Route.Detail(id), steps = null)
                }
                else -> {
                    cur.closeAll()
                    val detail = repo.detail(id)
                    current.value = Open(Route.Detail(id), detail, repo.runs(id), seen = markSeenOnce(id, detail))
                }
            }
        }
    }

    /** Opens a run of the automation on screen; ignored on the list. */
    fun openRun(runId: RunId) {
        synchronized(lock) {
            if (closed) return
            val cur = current.value
            val id = cur.automationId ?: return
            val runs = cur.runs ?: return
            if (cur.route == Route.Run(id, runId)) return
            cur.steps?.close()
            // A parked run changes only through events; a running one is polled.
            val steps = repo.steps(id, runId) {
                runs.state.value.data?.firstOrNull { it.id == runId }?.let { it.status == RunStatus.Running && it.parked == null } ?: false
            }
            current.value = cur.copy(route = Route.Run(id, runId), steps = steps)
        }
    }

    /** Goes one route up; false on the list, where there is nothing to go back to. */
    fun back(): Boolean {
        synchronized(lock) {
            if (closed) return false
            val cur = current.value
            return when (val route = cur.route) {
                Route.Overview -> false
                is Route.Detail -> {
                    cur.closeAll()
                    current.value = Open(Route.Overview)
                    true
                }
                is Route.Run -> {
                    cur.steps?.close()
                    current.value = cur.copy(route = Route.Detail(route.id), steps = null)
                    true
                }
            }
        }
    }

    fun refresh() {
        val cur = current.value
        list.reload()
        cur.detail?.reload()
        cur.runs?.reload()
        cur.steps?.reload()
    }

    /** The switch flips at once and rolls back if the write fails. */
    fun toggleEnabled(id: AutomationId, enabled: Boolean) {
        val control = Control(id.value, ControlAction.Enable)
        val seq = synchronized(lock) {
            if (!begin(control)) return
            val seq = ++overrideSeq
            overrideOwner[id] = seq
            controls.update { it.copy(enabledOverride = it.enabledOverride + (id to enabled)) }
            seq
        }
        act(
            control,
            affected = { listOfNotNull(list, detailOf(id)) },
            call = { repo.setEnabled(id, enabled) },
            onFailed = { dropOverride(id, seq) },
            onOk = {
                settleOverride(id, seq, enabled)
                false
            },
        )
    }

    /** Refused without a request while the automation needs approval; on success the new run opens if its automation is on screen. */
    fun runNow(id: AutomationId) {
        val control = Control(id.value, ControlAction.Run)
        synchronized(lock) {
            if (closed || control in controls.value.pending) return
            // View-only sessions never show the control, so only the approval gate applies here.
            val automation = known(id)
            if (automation != null && !canRunNow(automation, viewOnly = false)) {
                controls.update { it.copy(failures = it.failures + (control to QueryError.NotApproved)) }
                return
            }
            begin(control)
        }
        act(
            control,
            affected = { listOfNotNull(list, detailOf(id), runsOf(id)) },
            call = { repo.runNow(id) },
            onOk = { run ->
                synchronized(lock) { if (current.value.route == Route.Detail(id)) openRun(run.id) }
                false
            },
        )
    }

    fun cancelRun(runId: RunId) {
        val control = Control(runId.value, ControlAction.Cancel)
        if (!begin(control)) return
        act(control, affected = { runQueries() }, call = { repo.cancelRun(runId) })
    }

    /** Approve and Reject of one run share their in-flight state: one decision at a time. */
    fun decide(runId: RunId, nodeId: NodeId, approved: Boolean) {
        val control = Control(runId.value, if (approved) ControlAction.Approve else ControlAction.Reject)
        val both = setOf(Control(runId.value, ControlAction.Approve), Control(runId.value, ControlAction.Reject))
        if (!begin(control, busyIf = both)) return
        act(
            control,
            affected = { runQueries() },
            call = { repo.decideApproval(runId, nodeId, approved) },
            onOk = { decided ->
                if (!decided) {
                    controls.update { if (Notice.ApprovalAlreadyHandled in it.notices) it else it.copy(notices = it.notices + Notice.ApprovalAlreadyHandled) }
                    runQueries().forEach { it.reload() }
                }
                !decided
            },
        )
    }

    fun dismissNotice(n: Notice) {
        controls.update { it.copy(notices = it.notices - n) }
    }

    /** Closes every query and stops what is in flight; calling it again does nothing. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            list.close()
            current.value.closeAll()
        }
        job.cancel()
    }

    /** Claims [control] as in flight and clears its last failure; false when closed or anything in [busyIf] is in flight. */
    private fun begin(control: Control, busyIf: Set<Control> = setOf(control)): Boolean {
        synchronized(lock) {
            if (closed || controls.value.pending.any { it in busyIf }) return false
            controls.update { it.copy(pending = it.pending + control, failures = it.failures - control) }
            return true
        }
    }

    /**
     * Sends [call] once. A failure is recorded on [control] and [affected] re-read at once, as the write may have
     * been applied. On success [onOk] runs and, unless it returns true (it re-read itself), [affected] re-read once
     * after [REREAD_AFTER_MS] if no change event reloaded them first. An unexpected exception counts as
     * [QueryError.Invalid]: one escaping this coroutine would crash the app.
     */
    private fun <T> act(
        control: Control,
        affected: () -> List<HostQuery<*>>,
        call: suspend () -> Mutation<T>,
        onFailed: () -> Unit = {},
        onOk: (T) -> Boolean = { false },
    ) {
        controllerScope.launch {
            try {
                when (val result = call()) {
                    is Mutation.Ok -> {
                        controls.update { it.copy(pending = it.pending - control) }
                        if (!onOk(result.value)) affected().forEach { it.reloadUnlessEventWithin(REREAD_AFTER_MS) }
                    }
                    is Mutation.Failed -> fail(control, result.error, onFailed, affected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(control, QueryError.Invalid, onFailed, affected)
            }
        }
    }

    private fun fail(control: Control, error: QueryError, onFailed: () -> Unit, affected: () -> List<HostQuery<*>>) {
        onFailed()
        controls.update { it.copy(pending = it.pending - control, failures = it.failures + (control to error)) }
        affected().forEach { it.reload() }
    }

    /** Marks the automation's failures seen the first time this opening of its detail shows any. */
    private fun markSeenOnce(id: AutomationId, detail: HostQuery<AutomationDetail>): Job = controllerScope.launch {
        detail.state.first { (it.data?.automation?.unseenFailures ?: 0) > 0 }
        try {
            repo.markSeen(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not a control: nothing shows a failure, and the next opening tries again.
        }
    }

    /** Keeps the override until a list reply that began after the write shows the server's value. */
    private fun settleOverride(id: AutomationId, seq: Long, enabled: Boolean) {
        val before = list.state.value
        if (before.data?.firstOrNull { it.id == id }?.enabled == enabled) {
            dropOverride(id, seq)
            return
        }
        controllerScope.launch {
            list.state.first { it !== before && it.loaded && !it.refreshing }
            dropOverride(id, seq)
        }
    }

    private fun dropOverride(id: AutomationId, seq: Long) {
        synchronized(lock) {
            if (overrideOwner[id] != seq) return
            overrideOwner.remove(id)
            controls.update { it.copy(enabledOverride = it.enabledOverride - id) }
        }
    }

    /** The automation as last loaded: its detail when open, else its list row. */
    private fun known(id: AutomationId): Automation? =
        detailOf(id)?.state?.value?.data?.automation ?: list.state.value.data?.firstOrNull { it.id == id }

    private fun detailOf(id: AutomationId) = current.value.takeIf { it.automationId == id }?.detail

    private fun runsOf(id: AutomationId) = current.value.takeIf { it.automationId == id }?.runs

    private fun runQueries(): List<HostQuery<*>> = current.value.let { listOfNotNull(it.runs, it.steps) }

    private fun <T> HostQuery<T>?.stateOrNull(): Flow<QueryState<T>?> = this?.state ?: flowOf(null)

    private companion object {
        const val REREAD_AFTER_MS = 1_500L
    }
}
