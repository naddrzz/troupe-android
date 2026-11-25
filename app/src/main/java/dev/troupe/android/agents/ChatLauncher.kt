package dev.troupe.android.agents

import dev.troupe.android.chat.protocol.ChatFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface LaunchResult {
    data class Started(val sessionId: String, val agentId: String) : LaunchResult

    /** [message] is the host's own wording when it gave one. */
    data class Failed(val message: String) : LaunchResult
}

/** The two things a launch needs from `/ws`: a request that waits for its reply, and one that does not. */
interface LaunchTransport {
    suspend fun request(type: String, payload: JsonObject, commandId: String?, timeout: Duration): ChatFrame.Reply
    fun dispatch(type: String, payload: JsonObject, commandId: String?)
}

/**
 * Each step of a launch: the host starts an agent process (a first `npx` run also downloads its
 * package), and `create_session` and `authenticate_agent` wait on that same process.
 */
val LAUNCH_STEP_TIMEOUT = 120.seconds

const val AGENT_AUTH_REQUIRED = "agent_auth_required"

/**
 * Starts a chat the way Troupe's web client does: `spawn_agent` with the full config,
 * `create_session` (in [cwd] of [projectId], or a quick chat when there is no Project), then the
 * first `send_prompt`, which is not waited for because its reply comes when the turn ends.
 *
 * An agent that needs sign-in and offers exactly one method is signed in once and the session is
 * tried again; with several methods the user has to choose one in Troupe on a computer.
 */
suspend fun launchChat(
    transport: LaunchTransport,
    config: AgentConfig,
    projectId: String?,
    cwd: String?,
    prompt: String,
): LaunchResult {
    val spawn = transport.request("spawn_agent", buildJsonObject {
        putJsonObject("config") {
            put("configId", config.configId)
            put("name", config.name)
            put("command", config.command)
            putJsonArray("args") { config.args.forEach { add(it) } }
            putJsonObject("env") { config.env.forEach { (k, v) -> put(k, v) } }
            put("allowTerminal", config.allowTerminal)
        }
        put("projectId", projectId)
        put("sessionId", null as String?)
    }, newCommandId(), LAUNCH_STEP_TIMEOUT)
    val spawned = spawn.payload as? JsonObject
    val agentId = spawned?.string("agentId")
    if (!spawn.ok || agentId == null) return LaunchResult.Failed(spawn.message ?: "Could not start ${config.name}.")

    val sessionPayload = buildJsonObject {
        put("agentId", agentId)
        if (projectId != null && cwd != null) {
            put("cwd", cwd)
            put("projectId", projectId)
        } else {
            put("kind", "quick")
        }
        putJsonArray("mcpServers") {}
        put("ephemeral", false)
    }
    var created = transport.request("create_session", sessionPayload, newCommandId(), LAUNCH_STEP_TIMEOUT)
    if (!created.ok && created.needsAgentAuth()) {
        val methods = (spawned["authMethods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
        val method = methods.singleOrNull()
            ?: return LaunchResult.Failed("Sign in to ${config.name} in Troupe on your computer, then try again.")
        val auth = transport.request("authenticate_agent", buildJsonObject {
            put("agentId", agentId)
            put("methodId", method)
        }, newCommandId(), LAUNCH_STEP_TIMEOUT)
        if (!auth.ok) return LaunchResult.Failed(auth.message ?: "Could not sign in to ${config.name}.")
        created = transport.request("create_session", sessionPayload, newCommandId(), LAUNCH_STEP_TIMEOUT)
    }
    val sessionId = (created.payload as? JsonObject)?.string("sessionId")
    if (!created.ok || sessionId == null) return LaunchResult.Failed(created.message ?: "Could not start the chat.")

    val turnId = newCommandId()
    transport.dispatch("send_prompt", buildJsonObject {
        put("agentId", agentId)
        put("sessionId", sessionId)
        put("turnId", turnId)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", prompt)
            }
        }
    }, turnId)
    return LaunchResult.Started(sessionId, agentId)
}

private fun ChatFrame.Reply.needsAgentAuth(): Boolean =
    code == AGENT_AUTH_REQUIRED || message.orEmpty().startsWith("ACP_AUTH_REQUIRED")

private fun newCommandId(): String = UUID.randomUUID().toString()
