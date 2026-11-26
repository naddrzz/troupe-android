package dev.troupe.android.automations.state

import dev.troupe.android.automations.net.AutomationsApi
import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationChanged
import dev.troupe.android.automations.protocol.AutomationDetail
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.CoroutineScope

/** The outcome of a write; a [Failed] write was not necessarily left unapplied when [error] is [QueryError.Unreachable]. */
sealed interface Mutation<out T> {
    data class Ok<T>(val value: T) : Mutation<T>
    data class Failed(val error: QueryError) : Mutation<Nothing>
}

/**
 * The Automations of one Paired Server: live queries ([HostQuery]) that reload on the server's change events,
 * and the writes. [token] is read on every call, so a token changed in settings applies to the next request.
 */
class AutomationsRepository(
    private val api: AutomationsApi,
    private val address: ServerAddress,
    private val token: suspend () -> String?,
    private val events: AutomationEvents,
    private val scope: CoroutineScope,
) {
    fun list(projectId: String): HostQuery<List<Automation>> = HostQuery(
        scope, events.stream(),
        load = { api.list(address, token(), projectId).toQuery(unsupportedOn404 = true) },
        affects = { it.projectId == projectId },
    )

    /** [projectId] is the automation's project: the watch carries every project's project-wide changes. */
    fun detail(projectId: String, id: AutomationId): HostQuery<AutomationDetail> = HostQuery(
        scope, events.stream(),
        load = { api.get(address, token(), id).toQuery() },
        affects = { it.affects(projectId, id) },
    )

    fun runs(projectId: String, id: AutomationId): HostQuery<List<AutomationRun>> = HostQuery(
        scope, events.stream(),
        load = { api.runs(address, token(), id).toQuery() },
        affects = { it.affects(projectId, id) },
    )

    /** Reloads every 2 s while [running]: a step changes without any change event. */
    fun steps(projectId: String, automationId: AutomationId, runId: RunId, running: () -> Boolean): HostQuery<List<AutomationStep>> = HostQuery(
        scope, events.stream(),
        load = { api.steps(address, token(), runId).toQuery() },
        affects = { it.affects(projectId, automationId) },
        poll = Poll(STEPS_POLL_MS, running),
    )

    suspend fun setEnabled(id: AutomationId, enabled: Boolean): Mutation<Unit> =
        api.setEnabled(address, token(), id, enabled).toMutation()

    suspend fun runNow(id: AutomationId): Mutation<AutomationRun> = api.runNow(address, token(), id).toMutation()

    suspend fun cancelRun(runId: RunId): Mutation<Unit> = api.cancelRun(address, token(), runId).toMutation()

    /** `Ok(false)`: the run had already moved past that approval. */
    suspend fun decideApproval(runId: RunId, nodeId: NodeId, approved: Boolean): Mutation<Boolean> =
        api.decideApproval(address, token(), runId, nodeId, approved).toMutation()

    suspend fun markSeen(id: AutomationId): Mutation<Unit> = api.markSeen(address, token(), id).toMutation()

    /** A project-wide change (no automation id) carries only its project, so it counts for every automation of that project. */
    private fun AutomationChanged.affects(projectId: String, id: AutomationId) =
        this.projectId == projectId && (automationId == null || automationId == id.value)

    private fun <T> ApiResult<T>.toMutation(): Mutation<T> = when (val result = toQuery()) {
        is QueryResult.Ok -> Mutation.Ok(result.value)
        is QueryResult.Failed -> Mutation.Failed(result.error)
    }

    private companion object {
        const val STEPS_POLL_MS = 2_000L
    }
}
