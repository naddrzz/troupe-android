package dev.troupe.android.testing

import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 * `/projects` and `/terminal/ws` the way termul-server does (shapes from termul's
 * `host_routes.rs`, `projects_api.rs`, `router/gate.rs` and `web-terminal-protocol.types.ts`).
 *
 * Tests set the knobs, drive the app, then assert on what the app showed or on the
 * recorded traffic ([httpRequests], [terminalRequests]).
 */
class FakeTroupeServer : Closeable {
    /** The bearer token the server accepts. */
    var token: String = "fake-token-0123456789abcdef"
    var allowRemoteWrites: Boolean = true
    /** The protocol version the server speaks; `authenticate` with any other is refused. */
    var protocolVersion: Int = 7
    /** The `/projects` reply body when the token is accepted. */
    var projectsBody: String = fixture("projects.json")
    /**
     * Answers authenticated `/terminal/ws` requests other than `authenticate`: return the reply's
     * `data` object. Later slices script `list_preserved`, `spawn`, `attach` and friends here.
     */
    var onTerminalRequest: (type: String, payload: JsonObject) -> JsonObject = { type, _ ->
        error("FakeTroupeServer: no script for terminal request '$type'")
    }

    val httpRequests: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    val terminalUpgrades: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    /** Every request frame the app sent on `/terminal/ws`, parsed. */
    val terminalRequests: MutableList<JsonObject> = CopyOnWriteArrayList()

    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start()
    }

    val address: ServerAddress get() = ServerAddress(server.url("/"))

    /** The address as a user would type it, e.g. `127.0.0.1:54321`. */
    val typedAddress: String get() = "${server.hostName}:${server.port}"

    fun pairingInput(token: String = this.token) = PairingInput(address, token)

    /** Stops answering: connections are refused from now on. */
    fun goOffline() = server.close()

    override fun close() = server.close()

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path == "/terminal/ws") {
            terminalUpgrades += request
            return MockResponse.Builder().webSocketUpgrade(TerminalSocketScript()).build()
        }
        httpRequests += request
        return when (path) {
            "/health" -> json(200, healthBody())
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

        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
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
                else -> runCatching { onTerminalRequest(type, payload) }
                    .getOrElse { Refusal("INTERNAL", it.message ?: "error") }
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
        fun fixture(name: String): String =
            checkNotNull(FakeTroupeServer::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
    }
}

/** Reads an int field of a recorded request payload. */
fun JsonObject.payloadInt(field: String): Int = getValue("payload").jsonObject.getValue(field).jsonPrimitive.int

fun JsonObject.payloadString(field: String): String? = getValue("payload").jsonObject[field]?.jsonPrimitive?.content
