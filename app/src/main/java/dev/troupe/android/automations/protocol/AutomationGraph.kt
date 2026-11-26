package dev.troupe.android.automations.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/** Mirrors `src/shared/types/automation.types.ts` of Troupe web; `app/src/test/resources/fixtures/automations/` pins the wire shape. */

@JvmInline
@Serializable
value class AutomationId(val value: String)

@JvmInline
@Serializable
value class RunId(val value: String)

/** `^[a-z][a-z0-9_]{0,31}$`, never `trigger`. */
@JvmInline
@Serializable
value class NodeId(val value: String)

@Serializable
enum class Isolation {
    @SerialName("shared_in_root") SharedInRoot,
    @SerialName("worktree_per_run") WorktreePerRun,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface Schedule {
    @Serializable
    @SerialName("cron")
    data class Cron(val expr: String, val timezone: String) : Schedule

    @Serializable
    @SerialName("every")
    data class Every(@SerialName("every_ms") val everyMs: Long, @SerialName("anchor_ms") val anchorMs: Long) : Schedule

    @Serializable
    @SerialName("at")
    data class At(val at: Long) : Schedule
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface Trigger {
    @Serializable
    @SerialName("manual")
    data object Manual : Trigger

    @Serializable
    @SerialName("schedule")
    data class ScheduleTrigger(val schedule: Schedule) : Trigger
}

@Serializable
enum class HttpMethod {
    @SerialName("GET") Get,
    @SerialName("POST") Post,
    @SerialName("PUT") Put,
    @SerialName("PATCH") Patch,
    @SerialName("DELETE") Delete,
}

@Serializable
enum class ConditionOp {
    @SerialName("eq") Eq,
    @SerialName("ne") Ne,
    @SerialName("contains") Contains,
    @SerialName("gt") Gt,
    @SerialName("lt") Lt,
    @SerialName("exists") Exists,
}

/** A class of tool an agent node may use; the host refuses the rest. */
@Serializable
enum class AgentTool {
    @SerialName("read") Read,
    @SerialName("search") Search,
    @SerialName("edit") Edit,
    @SerialName("execute") Execute,
    @SerialName("fetch") Fetch,
    @SerialName("other") Other,
}

@Serializable
enum class MergeMode {
    @SerialName("any") Any,
    @SerialName("all") All,
}

/** A string with `{{ path }}` holes. */
@Serializable
data class NamedTemplate(val name: String, val value: String)

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface NodeSpec {
    @Serializable
    @SerialName("http")
    data class Http(
        val method: HttpMethod,
        val url: String,
        val headers: List<NamedTemplate>,
        val body: String?,
        @SerialName("allowed_hosts") val allowedHosts: List<String>,
    ) : NodeSpec

    @Serializable
    @SerialName("agent")
    data class Agent(
        @SerialName("agent_id") val agentId: String,
        val model: String?,
        val prompt: String,
        @SerialName("output_schema") val outputSchema: JsonObject,
        @SerialName("timeout_ms") val timeoutMs: Long,
        @SerialName("max_attempts") val maxAttempts: Int,
        val tools: List<AgentTool>,
        /** Server ids its session starts with when [tools] has `Other`: null for every enabled server, empty for none. */
        @SerialName("mcp_servers") val mcpServers: List<String>?,
    ) : NodeSpec

    @Serializable
    @SerialName("transform")
    data class Transform(val fields: List<NamedTemplate>) : NodeSpec

    @Serializable
    @SerialName("condition")
    data class Condition(val left: String, val op: ConditionOp, val right: String?) : NodeSpec

    @Serializable
    @SerialName("fail")
    data class Fail(val message: String) : NodeSpec

    @Serializable
    @SerialName("mcp_tool")
    data class McpTool(
        @SerialName("server_id") val serverId: String,
        val tool: String,
        val arguments: List<NamedTemplate>,
        @SerialName("timeout_ms") val timeoutMs: Long,
    ) : NodeSpec

    /** Runs the nodes behind its `item` edges once per element of [items]. */
    @Serializable
    @SerialName("for_each")
    data class ForEach(val items: String, @SerialName("max_iterations") val maxIterations: Int) : NodeSpec

    @Serializable
    @SerialName("merge")
    data class Merge(val mode: MergeMode) : NodeSpec

    /** Parks the run durably for [durationMs]. */
    @Serializable
    @SerialName("wait")
    data class Wait(@SerialName("duration_ms") val durationMs: Long) : NodeSpec

    /** Parks the run until a person approves it; a rejection or [timeoutMs] passing first fails the step. */
    @Serializable
    @SerialName("approval")
    data class Approval(val message: String, @SerialName("timeout_ms") val timeoutMs: Long) : NodeSpec
}

@Serializable
data class AutomationNode(val id: NodeId, val label: String, val spec: NodeSpec)

@Serializable
enum class Port {
    @SerialName("out") Out,
    @SerialName("error") Error,
    @SerialName("true") True,
    @SerialName("false") False,
    @SerialName("item") Item,
}

/** [from] is `"trigger"` or a [NodeId]. */
@Serializable
data class Edge(val from: String, val port: Port, val to: NodeId)

@Serializable
data class Graph(val nodes: List<AutomationNode>, val edges: List<Edge>)

@Serializable
data class NodePosition(val x: Double, val y: Double)
