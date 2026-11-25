package dev.troupe.android.agents

import dev.troupe.android.chat.protocol.ChatFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

/** The request sequence of a new chat, against a scripted `/ws`. */
class ChatLauncherTest {
    private val config = AgentConfig(
        id = "acp-registry:opencode",
        templateId = "opencode",
        name = "OpenCode",
        command = "/bin/opencode",
        args = listOf("acp"),
    )

    private class Sent(val type: String, val payload: JsonObject, val commandId: String?)

    /** Answers each request type with the next scripted reply; the last one repeats. */
    private class Script(private val replies: Map<String, List<ChatFrame.Reply>>) : LaunchTransport {
        val sent = mutableListOf<Sent>()
        private val counts = HashMap<String, Int>()

        override suspend fun request(type: String, payload: JsonObject, commandId: String?, timeout: Duration): ChatFrame.Reply {
            sent += Sent(type, payload, commandId)
            val n = counts.merge(type, 1, Int::plus)!!
            val scripted = replies.getValue(type)
            return scripted[minOf(n, scripted.size) - 1]
        }

        override fun dispatch(type: String, payload: JsonObject, commandId: String?) {
            sent += Sent(type, payload, commandId)
        }

        fun payloads(type: String) = sent.filter { it.type == type }.map { it.payload }
    }

    private fun ok(payload: JsonObject) = ChatFrame.Reply("r", true, payload, null, null)
    private fun err(code: String, message: String) = ChatFrame.Reply("r", false, null, code, message)
    private fun spawned(vararg authMethods: String) = ok(buildJsonObject {
        put("agentId", "agent-9")
        putJsonArray("authMethods") { authMethods.forEach { add(buildJsonObject { put("id", it) }) } }
    })
    private val session = ok(buildJsonObject { put("sessionId", "sess-1") })

    @Test fun projectChatSpawnsCreatesAndPrompts() = runTest {
        val ws = Script(mapOf("spawn_agent" to listOf(spawned()), "create_session" to listOf(session)))

        val result = launchChat(ws, config, projectId = "p1", cwd = "/work/p1", prompt = "hello")

        assertEquals(LaunchResult.Started("sess-1", "agent-9"), result)
        assertEquals(listOf("spawn_agent", "create_session", "send_prompt"), ws.sent.map { it.type })
        // Every one of them is a ledgered command.
        assertTrue(ws.sent.all { !it.commandId.isNullOrEmpty() })

        val spawn = ws.payloads("spawn_agent").single()
        // The server refuses unknown fields in `config`: exactly these six.
        assertEquals(
            setOf("configId", "name", "command", "args", "env", "allowTerminal"),
            (spawn.getValue("config") as JsonObject).keys,
        )
        assertEquals(JsonPrimitive("acp-registry:opencode"), (spawn.getValue("config") as JsonObject)["configId"])
        assertEquals(JsonPrimitive("p1"), spawn["projectId"])

        val create = ws.payloads("create_session").single()
        assertEquals(JsonPrimitive("agent-9"), create["agentId"])
        assertEquals(JsonPrimitive("/work/p1"), create["cwd"])
        assertEquals(JsonPrimitive("p1"), create["projectId"])
        assertEquals(JsonPrimitive(false), create["ephemeral"])
        assertEquals(JsonArray(emptyList()), create["mcpServers"])
        assertFalse("kind" in create)

        val prompt = ws.sent.last()
        assertEquals(JsonPrimitive("sess-1"), prompt.payload["sessionId"])
        // A `send_prompt` command id must equal its turn id.
        assertEquals(JsonPrimitive(prompt.commandId), prompt.payload["turnId"])
    }

    @Test fun withoutAProjectItIsAQuickChat() = runTest {
        val ws = Script(mapOf("spawn_agent" to listOf(spawned()), "create_session" to listOf(session)))

        launchChat(ws, config, projectId = null, cwd = null, prompt = "hello")

        val create = ws.payloads("create_session").single()
        assertEquals(JsonPrimitive("quick"), create["kind"])
        assertFalse("cwd" in create)
        assertFalse("projectId" in create)
    }

    @Test fun oneSignInMethodIsUsedAndTheSessionRetriedOnce() = runTest {
        val ws = Script(
            mapOf(
                "spawn_agent" to listOf(spawned("oauth")),
                "create_session" to listOf(err("agent_auth_required", "sign in"), session),
                "authenticate_agent" to listOf(ok(JsonObject(emptyMap()))),
            ),
        )

        val result = launchChat(ws, config, "p1", "/work/p1", "hello")

        assertEquals(LaunchResult.Started("sess-1", "agent-9"), result)
        assertEquals(
            listOf("spawn_agent", "create_session", "authenticate_agent", "create_session", "send_prompt"),
            ws.sent.map { it.type },
        )
        assertEquals(JsonPrimitive("oauth"), ws.payloads("authenticate_agent").single()["methodId"])
    }

    @Test fun anAuthRequiredMessageWithoutACodeAlsoSignsIn() = runTest {
        val ws = Script(
            mapOf(
                "spawn_agent" to listOf(spawned("oauth")),
                "create_session" to listOf(ChatFrame.Reply("r", false, null, null, "ACP_AUTH_REQUIRED: sign in first"), session),
                "authenticate_agent" to listOf(ok(JsonObject(emptyMap()))),
            ),
        )

        val result = launchChat(ws, config, "p1", "/work/p1", "hello")

        assertEquals(LaunchResult.Started("sess-1", "agent-9"), result)
        assertEquals(1, ws.payloads("authenticate_agent").size)
    }

    @Test fun severalSignInMethodsAreLeftToTheUser() = runTest {
        val ws = Script(
            mapOf(
                "spawn_agent" to listOf(spawned("oauth", "api-key")),
                "create_session" to listOf(err("agent_auth_required", "sign in")),
            ),
        )

        val result = launchChat(ws, config, "p1", "/work/p1", "hello")

        assertTrue(result is LaunchResult.Failed)
        assertTrue(ws.payloads("authenticate_agent").isEmpty())
        assertTrue(ws.payloads("send_prompt").isEmpty())
    }

    @Test fun aFailedSpawnReportsTheHostsMessageAndSendsNothingElse() = runTest {
        val ws = Script(mapOf("spawn_agent" to listOf(err("agent_unavailable", "opencode: not found"))))

        val result = launchChat(ws, config, "p1", "/work/p1", "hello")

        assertEquals(LaunchResult.Failed("opencode: not found"), result)
        assertEquals(1, ws.sent.size)
        assertNotNull(ws.sent.single().commandId)
    }
}
