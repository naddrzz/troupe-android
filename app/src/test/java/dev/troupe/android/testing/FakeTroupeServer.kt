package dev.troupe.android.testing

import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The primary test seam: a scripted Troupe Server on MockWebServer speaking `/health`,
 * `/projects`, `/terminal/ws` and (through [chat]) `/ws` the way termul-server does (shapes from termul's
 * `host_routes.rs`, `projects_api.rs`, `router/gate.rs` and `web-terminal-protocol.types.ts`).
 *
 * Tests set the knobs, drive the app, then assert on what the app showed or on the
 * recorded traffic ([httpRequests], [terminalRequests]).
 */
class FakeTroupeServer : Closeable {
    /** The bearer token the server accepts. */
    var token: String = "fake-token-0123456789abcdef"
    /**
     * `/health.allowRemoteWrites`. While false, `/terminal/ws` refuses every request but the view
     * ones ([VIEW_REQUESTS]) with `FORBIDDEN`, and `/ws` refuses prompts and answers with
     * `forbidden`, as termul refuses a view-only connection. Checked per
     * request, so flipping it mid-test is a server policy change the app learns from a refusal.
     */
    @Volatile var allowRemoteWrites: Boolean = true
    /** The protocol version the server speaks; `authenticate` with any other is refused. */
    var protocolVersion: Int = 7
    /** The `/projects` reply body when the token is accepted. */
    var projectsBody: String = fixture("projects.json")
    /**
     * Answers authenticated `/terminal/ws` requests other than `authenticate`: return the reply's
     * `data` object, or throw [Refused] for an error reply. Frames pushed with [sendTerminalFrame]
     * from here go out before the reply, as termul sends `replay` before the `attach` reply.
     */
    var onTerminalRequest: (type: String, payload: JsonObject) -> JsonObject = { type, _ ->
        error("FakeTroupeServer: no script for terminal request '$type'")
    }

    /** The chat socket `/ws`: session logs, the `chat_history` index and its traffic. */
    val chat = FakeChatServer(token = { token }, protocolVersion = { protocolVersion }, allowRemoteWrites = { allowRemoteWrites })

    val httpRequests: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    val terminalUpgrades: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    /** Every request frame the app sent on `/terminal/ws`, parsed. */
    val terminalRequests: MutableList<JsonObject> = CopyOnWriteArrayList()
    private val terminalSockets: MutableList<WebSocket> = CopyOnWriteArrayList()
    /** Every open `/terminal/ws` connection, authenticated or not. */
    private val openSockets: MutableList<WebSocket> = CopyOnWriteArrayList()

    /** `false`: `/health` answers 503, like a server that is stopping or not ready yet. */
    @Volatile var healthy: Boolean = true

    /** Pushes a server frame (`data`, `replay`, …) to every authenticated `/terminal/ws` connection. */
    fun sendTerminalFrame(frame: JsonObject) = terminalSockets.forEach { it.send(frame.toString()) }

    /** Closes every authenticated `/terminal/ws` connection with [code]: 1001 is a deliberate stop, 1011 a crash. */
    fun closeTerminalSockets(code: Int) = terminalSockets.toList().forEach {
        terminalSockets -= it
        it.close(code, null)
    }

    /** `/health` requests answered so far. */
    fun healthChecks(): Int = httpRequests.count { it.url.encodedPath == "/health" }

    /** The recorded requests of one type, in order. */
    fun terminalRequests(type: String): List<JsonObject> =
        terminalRequests.filter { it["type"]?.jsonPrimitive?.content == type }

    /** A scripted error reply `{success: false, code, error}`. */
    class Refused(val code: String, message: String = code) : Exception(message)

    private var server = started(port = 0)

    private fun started(port: Int) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        start(port)
    }

    val address: ServerAddress = ServerAddress(server.url("/"))

    /** The address as a user would type it, e.g. `127.0.0.1:54321`. */
    val typedAddress: String = "${server.hostName}:${server.port}"

    fun pairingInput(token: String = this.token) = PairingInput(address, token)

    /** Stops answering: open connections drop and new ones are refused. */
    fun goOffline() {
        terminalSockets.clear()
        // A server-side WebSocket has no cancel(); 1011 is the abnormal end a crash or a dropped link looks like.
        openSockets.forEach { it.close(1011, null) }
        chat.closeAll(1011)
        server.close()
    }

    /** Answers again on the same address, like a restarted server. */
    fun comeBack() {
        server = started(port = server.port)
    }

    override fun close() = server.close()

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path == "/terminal/ws") {
            terminalUpgrades += request
            return MockResponse.Builder().webSocketUpgrade(TerminalSocketScript()).build()
        }
        if (path == "/ws") return MockResponse.Builder().webSocketUpgrade(chat.listener()).build()
        httpRequests += request
        return when (path) {
            "/health" -> if (healthy) json(200, healthBody()) else json(503, """{"success":false,"error":"Service unavailable","code":"UNAVAILABLE"}""")
            "/projects" ->
                if (request.headers["Authorization"] == "Bearer $token") json(200, projectsBody)
                else json(401, """{"success":false,"error":"Unauthorized","code":"UNAUTHORIZED"}""")
            else -> json(404, """{"success":false,"error":"Not found","code":"NOT_FOUND"}""")
        }
    }

    private fun healthBody() =
        """{"status":"ok","allowRemoteWrites":$allowRemoteWrites,"caseInsensitivePaths":false,"setupRequired":false}"""

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    /** One `/terminal/ws` connection: refuses everything until a valid `authenticate`. */
    private inner class TerminalSocketScript : WebSocketListener() {
        private var authed = false
        private lateinit var socket: WebSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            openSockets += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            terminalSockets -= webSocket
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            openSockets -= webSocket
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            terminalSockets -= webSocket
            openSockets -= webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            terminalRequests += request
            val id = request.getValue("id").jsonPrimitive.content
            val type = request.getValue("type").jsonPrimitive.content
            val payload = request["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val reply = when {
                type == "authenticate" -> authenticate(payload)
                !authed -> Refusal("UNAUTHORIZED", "Unauthorized")
                !allowRemoteWrites && type !in VIEW_REQUESTS ->
                    Refusal("FORBIDDEN", "`$type` requires write capability; this connection is view-only")
                else -> runCatching { onTerminalRequest(type, payload) }.getOrElse {
                    if (it is Refused) Refusal(it.code, it.message ?: it.code) else Refusal("INTERNAL", it.message ?: "error")
                }
            }
            webSocket.send(replyFrame(id, reply))
        }

        private fun authenticate(payload: JsonObject): Any {
            if (authed) return JsonObject(emptyMap())
            if (payload["protocolVersion"]?.jsonPrimitive?.intOrNull != protocolVersion) {
                return Refusal(
                    "PROTOCOL_VERSION_MISMATCH",
                    "protocol version mismatch: this host speaks version $protocolVersion; reload the client",
                )
            }
            for (field in listOf("deviceId", "clientInstanceId")) {
                val value = payload[field]?.jsonPrimitive?.content
                if (value.isNullOrEmpty() || value.length > 128) {
                    return Refusal("INVALID_CLIENT_IDENTITY", "$field must be a non-empty string")
                }
            }
            if (payload["token"]?.jsonPrimitive?.content != token) return Refusal("UNAUTHORIZED", "Unauthorized")
            authed = true
            terminalSockets += socket
            return JsonObject(emptyMap())
        }

        private fun replyFrame(id: String, reply: Any): String = buildJsonObject {
            put("id", id)
            when (reply) {
                is Refusal -> {
                    put("success", false)
                    put("error", reply.error)
                    put("code", reply.code)
                }
                else -> {
                    put("success", true)
                    put("data", reply as JsonElement)
                }
            }
        }.toString()
    }

    private data class Refusal(val code: String, val error: String)

    companion object {
        /** termul's `terminal_request_is_view`: what a view-only connection may still send. */
        val VIEW_REQUESTS = setOf("authenticate", "attach", "detach", "get_cwd", "get_git_branch", "get_git_status", "get_exit_code")

        fun fixture(name: String): String =
            checkNotNull(FakeTroupeServer::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

        /** A live output frame, shaped like termul's `WebTerminalDataFrame`. */
        fun dataFrame(terminalId: String, seq: Long, bytes: ByteArray): JsonObject = buildJsonObject {
            put("type", "data")
            put("terminalId", terminalId)
            put("seq", seq)
            put("data", bytes.toJsonInts())
        }

        /** The frame termul sends on `attach`: unseen chunks numbered from [firstSeq]. */
        fun replayFrame(
            terminalId: String,
            vararg chunks: ByteArray,
            reset: Boolean = false,
            firstSeq: Long = 1,
            logEpoch: String = "ep-1",
            exitCode: Int? = null,
        ): JsonObject = buildJsonObject {
            put("type", "replay")
            put("terminalId", terminalId)
            put("chunks", JsonArray(chunks.mapIndexed { i, bytes ->
                buildJsonObject {
                    put("seq", firstSeq + i)
                    put("data", bytes.toJsonInts())
                }
            }))
            put("gap", false)
            put("reset", reset)
            put("logEpoch", logEpoch)
            put("latestSeq", firstSeq + chunks.size - 1)
            put("snapshot", buildJsonObject {
                put("exited", exitCode != null)
                put("exitCode", exitCode)
            })
        }

        /** Live output after [lastSeq] was lost (termul's `WebTerminalGapFrame`). */
        fun gapFrame(terminalId: String, lastSeq: Long): JsonObject = buildJsonObject {
            put("type", "gap")
            put("terminalId", terminalId)
            put("lastSeq", lastSeq)
        }

        /** The `exit` event; its payload keys are snake_case. */
        fun exitEvent(terminalId: String, exitCode: Int?): JsonObject = buildJsonObject {
            put("type", "event")
            put("payload", buildJsonObject {
                put("type", "exit")
                put("terminal_id", terminalId)
                put("exit_code", exitCode)
                put("signal", null as Int?)
            })
        }

        private fun ByteArray.toJsonInts() = JsonArray(map { JsonPrimitive(it.toInt() and 0xFF) })
    }
}

/** Reads an int field of a recorded request payload. */
fun JsonObject.payloadInt(field: String): Int = getValue("payload").jsonObject.getValue(field).jsonPrimitive.int

fun JsonObject.payloadString(field: String): String? = getValue("payload").jsonObject[field]?.jsonPrimitive?.content

/** The `data` of a `spawn` reply, shaped like termul's `SpawnedTerminal`. */
fun spawnedTerminal(id: String, claim: String): JsonObject = buildJsonObject {
    put("id", id)
    put("shell", "/bin/bash")
    put("cwd", "/workspace")
    put("pid", 42)
    put("cols", 80)
    put("rows", 24)
    put("claim", claim)
}

/** One entry of a `list_preserved` reply, shaped like termul's `PreservedTerminal`. */
fun preservedTerminal(id: String, claim: String?, openElsewhere: Boolean = false, cwd: String = "/workspace"): JsonObject = buildJsonObject {
    put("id", id)
    put("shell", "/bin/sh")
    put("cwd", cwd)
    put("pid", 42)
    put("cols", 80)
    put("rows", 24)
    if (claim != null) put("claim", claim)
    put("hasLiveAttachment", openElsewhere)
}

/** The `data` of a `list_preserved` reply. */
fun preservedList(projectId: String, vararg terminals: JsonObject): JsonObject = buildJsonObject {
    put("projectId", projectId)
    put("terminals", JsonArray(terminals.toList()))
}
