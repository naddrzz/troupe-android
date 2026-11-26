package dev.troupe.android.automations.net

import dev.troupe.android.automations.protocol.Automation
import dev.troupe.android.automations.protocol.AutomationDetail
import dev.troupe.android.automations.protocol.AutomationId
import dev.troupe.android.automations.protocol.AutomationRun
import dev.troupe.android.automations.protocol.AutomationStep
import dev.troupe.android.automations.protocol.NodeId
import dev.troupe.android.automations.protocol.RunId
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server's `/api/automation*` routes (`automation_api.rs`). The five mutating routes opt out of OkHttp's
 * retry after a connection failure, so each is written to the wire at most once per call: a lost reply is
 * [ApiResult.Unreachable] and the caller decides whether to resend. The GET routes keep the shared client's retry.
 */
@Singleton
class AutomationsApi @Inject constructor(private val api: ServerApi) {

    suspend fun list(address: ServerAddress, token: String?, projectId: String): ApiResult<List<Automation>> =
        api.getEnveloped(address, "api/automations?project_id=${encode(projectId)}", token, ListSerializer(Automation.serializer()))

    suspend fun get(address: ServerAddress, token: String?, id: AutomationId): ApiResult<AutomationDetail> =
        api.getEnveloped(address, "api/automations/${encode(id.value)}", token, AutomationDetail.serializer())

    suspend fun setEnabled(address: ServerAddress, token: String?, id: AutomationId, enabled: Boolean): ApiResult<Unit> =
        api.postUnit(address, "api/automations/${encode(id.value)}/enabled", token, buildJsonObject { put("enabled", enabled) }.toString(), retryOnConnectionFailure = false)

    suspend fun runNow(address: ServerAddress, token: String?, id: AutomationId): ApiResult<AutomationRun> =
        api.postEnveloped(address, "api/automations/${encode(id.value)}/run", token, serializer = AutomationRun.serializer(), retryOnConnectionFailure = false)

    suspend fun runs(address: ServerAddress, token: String?, id: AutomationId, limit: Int = 25): ApiResult<List<AutomationRun>> =
        api.getEnveloped(address, "api/automations/${encode(id.value)}/runs?limit=$limit", token, ListSerializer(AutomationRun.serializer()))

    suspend fun steps(address: ServerAddress, token: String?, runId: RunId): ApiResult<List<AutomationStep>> =
        api.getEnveloped(address, "api/automation-runs/${encode(runId.value)}/steps", token, ListSerializer(AutomationStep.serializer()))

    suspend fun cancelRun(address: ServerAddress, token: String?, runId: RunId): ApiResult<Unit> =
        api.postUnit(address, "api/automation-runs/${encode(runId.value)}/cancel", token, retryOnConnectionFailure = false)

    /** `Ok(false)`: the run had already moved past that approval. */
    suspend fun decideApproval(
        address: ServerAddress,
        token: String?,
        runId: RunId,
        nodeId: NodeId,
        approved: Boolean,
    ): ApiResult<Boolean> = api.postEnveloped(
        address,
        "api/automation-runs/${encode(runId.value)}/approval",
        token,
        buildJsonObject {
            put("node_id", nodeId.value)
            put("approved", approved)
        }.toString(),
        Boolean.serializer(),
        retryOnConnectionFailure = false,
    )

    suspend fun markSeen(address: ServerAddress, token: String?, id: AutomationId): ApiResult<Unit> =
        api.postUnit(address, "api/automations/${encode(id.value)}/seen", token, retryOnConnectionFailure = false)

    /** A path segment or query value: everything but unreserved characters is percent-encoded, a space as `%20`. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
