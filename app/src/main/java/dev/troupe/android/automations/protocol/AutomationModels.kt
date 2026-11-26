package dev.troupe.android.automations.protocol

import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

@Serializable
enum class RunStatus {
    @SerialName("running") Running,
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("cancelled") Cancelled,
    @SerialName("skipped") Skipped,
}

@Serializable
enum class StepStatus {
    @SerialName("running") Running,
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("skipped") Skipped,
    @SerialName("waiting") Waiting,
}

@Serializable
enum class RunTrigger {
    @SerialName("scheduled") Scheduled,
    @SerialName("catch_up") CatchUp,
    @SerialName("manual") Manual,
}

@Serializable
enum class ApprovalDecision {
    @SerialName("approved") Approved,
    @SerialName("rejected") Rejected,
    @SerialName("timed_out") TimedOut,
}

@Serializable
data class LastRun(
    @SerialName("run_id") val runId: RunId,
    val status: RunStatus,
    @SerialName("finished_at") val finishedAt: Long,
)

@Serializable
data class Automation(
    val id: AutomationId,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val revision: Long,
    @SerialName("approved_revision") val approvedRevision: Long?,
    val enabled: Boolean,
    val trigger: Trigger,
    val graph: Graph,
    /** Keys are node ids plus `"trigger"`. Not part of the revision. */
    val layout: Map<String, NodePosition>,
    val isolation: Isolation,
    @SerialName("next_run_at") val nextRunAt: Long?,
    @SerialName("consecutive_errors") val consecutiveErrors: Int,
    @SerialName("schedule_error_count") val scheduleErrorCount: Int,
    @SerialName("unseen_failures") val unseenFailures: Int,
    @SerialName("last_run") val lastRun: LastRun?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long?,
)

/** The step a `Running` run is parked on; [until] is when the park ends (epoch ms). */
@Serializable
data class Parked(
    @SerialName("node_id") val nodeId: NodeId,
    val iteration: Int?,
    val until: Long,
)

@Serializable
data class AutomationRun(
    val id: RunId,
    @SerialName("automation_id") val automationId: AutomationId,
    val revision: Long,
    /** Snapshot of the graph at [revision]. */
    val graph: Graph,
    val trigger: RunTrigger,
    @SerialName("scheduled_for") val scheduledFor: Long,
    val status: RunStatus,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("finished_at") val finishedAt: Long?,
    @SerialName("stop_reason") val stopReason: String?,
    val summary: String?,
    @SerialName("conversation_id") val conversationId: String?,
    @SerialName("worktree_id") val worktreeId: String?,
    /** Read from the run's `Waiting` step; a parked run's status stays `Running`. */
    val parked: Parked?,
)

@Serializable
data class AutomationStep(
    @SerialName("run_id") val runId: RunId,
    @SerialName("node_id") val nodeId: NodeId,
    /** 0-based iteration for a node inside a `for_each` body, else null. */
    val iteration: Int?,
    val status: StepStatus,
    val output: JsonElement?,
    val error: String?,
    @SerialName("started_at") val startedAt: Long?,
    @SerialName("finished_at") val finishedAt: Long?,
    /** When a `Waiting` step's park ends. */
    @SerialName("wait_until") val waitUntil: Long?,
)

/** [code] stays a string: a newer server may send a code this app does not know. */
@Serializable
data class Issue(
    @SerialName("node_id") val nodeId: NodeId?,
    val code: String,
    val message: String,
)

@Serializable
data class AgentMcpServer(
    @SerialName("server_id") val serverId: String,
    val name: String,
)

@Serializable
data class AgentPolicy(
    @SerialName("node_id") val nodeId: NodeId,
    @SerialName("agent_id") val agentId: String,
    val model: String?,
    @SerialName("timeout_ms") val timeoutMs: Long,
    val tools: List<AgentTool>,
    @SerialName("mcp_servers") val mcpServers: List<AgentMcpServer>,
)

/** An `mcp_tool` node; [serverName] is null when the server is not configured. */
@Serializable
data class McpToolPolicy(
    @SerialName("node_id") val nodeId: NodeId,
    @SerialName("server_id") val serverId: String,
    @SerialName("server_name") val serverName: String?,
    val tool: String,
)

@Serializable
data class NodeNames(
    @SerialName("node_id") val nodeId: NodeId,
    val names: List<String>,
)

@Serializable
data class NodeHosts(
    @SerialName("node_id") val nodeId: NodeId,
    val hosts: List<String>,
)

@Serializable
data class RunPolicy(
    @SerialName("trigger_summary") val triggerSummary: String,
    @SerialName("next_runs") val nextRuns: List<Long>,
    val agents: List<AgentPolicy>,
    val secrets: List<NodeNames>,
    @SerialName("outbound_hosts") val outboundHosts: List<NodeHosts>,
    @SerialName("mcp_tools") val mcpTools: List<McpToolPolicy>,
    @SerialName("untrusted_flow") val untrustedFlow: Boolean,
    @SerialName("failure_policy") val failurePolicy: String,
)

/** An automation with what its editor and approval card render. */
@Serializable
data class AutomationDetail(val automation: Automation, val issues: List<Issue>, val policy: RunPolicy)

/** Wire event `automation_changed`; [automationId] is null for project-wide changes. */
@Serializable
data class AutomationChanged(
    @SerialName("project_id") val projectId: String,
    @SerialName("automation_id") val automationId: String?,
    val proposed: Boolean = false,
    @SerialName("proposed_by") val proposedBy: String? = null,
)

/** An approval step's output: the rendered message, plus the decision once made. A cancelled approval keeps only the message. */
data class ApprovalStepOutput(val message: String, val decision: ApprovalDecision?, val decidedAt: Long?)

/** An approval step's output, or null when it has another shape: step output is free-form per node kind. */
fun parseApprovalOutput(output: JsonElement?): ApprovalStepOutput? {
    val obj = output as? JsonObject ?: return null
    val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val rawDecision = obj["decision"] ?: return ApprovalStepOutput(message, null, null)
    val decision = try {
        TroupeJson.decodeFromJsonElement<ApprovalDecision>(rawDecision)
    } catch (_: SerializationException) {
        return null
    }
    val decidedAt = (obj["decided_at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    return ApprovalStepOutput(message, decision, decidedAt)
}
