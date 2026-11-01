package dev.troupe.android.terminal.net

import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.terminal.protocol.PROTOCOL_VERSION
import dev.troupe.android.terminal.protocol.TerminalErrorCodes
import dev.troupe.android.terminal.protocol.TerminalFrame
import dev.troupe.android.terminal.protocol.TerminalRequest
import dev.troupe.android.terminal.protocol.parseTerminalFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

enum class ConnectionFailure { Unreachable, Unauthorized, ProtocolMismatch, Refused }

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: ConnectionFailure, val message: String? = null) : ConnectionState
    data object Closed : ConnectionState
}

/** The socket dropped while a request was waiting for its reply. */
class TerminalDisconnected : IOException("terminal socket disconnected")

class TerminalRequestTimeout(type: String) : IOException("no reply to '$type' within 15 s")

class TerminalSocketFactory @Inject constructor(
    private val client: OkHttpClient,
    private val identity: ClientIdentityStore,
) {
    fun create(address: ServerAddress, token: String) = TerminalSocket(client, address, token, identity)
}

/**
 * One `/terminal/ws` connection. [connect] opens it and runs the `authenticate` handshake
 * (protocol 7, device and instance ids, token); a version mismatch or rejected token is final.
 * [request] correlates replies by `id`. Reconnect, output frames and claims arrive with the
 * Terminal slice.
 */
class TerminalSocket internal constructor(
    private val client: OkHttpClient,
    private val address: ServerAddress,
    private val token: String,
    private val identity: ClientIdentityStore,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<TerminalFrame.Reply>>()
    @Volatile private var webSocket: WebSocket? = null

    suspend fun connect(): ConnectionState {
        _state.value = ConnectionState.Connecting
        val opened = CompletableDeferred<Unit>()
        webSocket = client.newWebSocket(Request.Builder().url(address.wsUrl("terminal/ws")).build(), Listener(opened))
        val result = try {
            opened.await()
            authenticate(identity.get())
        } catch (e: IOException) {
            ConnectionState.Failed(ConnectionFailure.Unreachable)
        }
        if (result != ConnectionState.Connected) webSocket?.cancel()
        _state.value = result
        return result
    }

    private suspend fun authenticate(id: ClientIdentity): ConnectionState {
        val reply = send("authenticate", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("deviceId", id.deviceId)
            put("clientInstanceId", id.clientInstanceId)
            put("token", token)
        }, commandId = null)
        if (reply.success) return ConnectionState.Connected
        val reason = when (reply.code) {
            TerminalErrorCodes.UNAUTHORIZED -> ConnectionFailure.Unauthorized
            TerminalErrorCodes.PROTOCOL_VERSION_MISMATCH -> ConnectionFailure.ProtocolMismatch
            else -> ConnectionFailure.Refused
        }
        return ConnectionState.Failed(reason, reply.error)
    }

    /** Sends a request and waits for its reply. Ledgered requests (`spawn`, `write`, `kill`) carry a `commandId`. */
    suspend fun request(type: String, payload: JsonObject, ledgered: Boolean = false): TerminalFrame.Reply {
        check(_state.value == ConnectionState.Connected) { "not connected" }
        return send(type, payload, commandId = if (ledgered) UUID.randomUUID().toString() else null)
    }

    fun close() {
        webSocket?.close(1000, null)
        failPending()
        _state.value = ConnectionState.Closed
    }

    private suspend fun send(type: String, payload: JsonObject, commandId: String?): TerminalFrame.Reply {
        val socket = webSocket ?: throw TerminalDisconnected()
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<TerminalFrame.Reply>()
        pending[id] = reply
        val frame = TroupeJson.encodeToString(TerminalRequest.serializer(), TerminalRequest(id, type, payload, commandId))
        if (!socket.send(frame)) {
            pending.remove(id)
            throw TerminalDisconnected()
        }
        return try {
            withTimeout(15.seconds) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw TerminalRequestTimeout(type)
        } finally {
            pending.remove(id)
        }
    }

    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(TerminalDisconnected()) }
        pending.clear()
    }

    private inner class Listener(private val opened: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = parseTerminalFrame(text)
            if (frame is TerminalFrame.Reply) pending[frame.id]?.complete(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(TerminalDisconnected())
            dropped()
        }

        private fun dropped() {
            failPending()
            if (_state.value == ConnectionState.Connected) _state.value = ConnectionState.Closed
        }
    }
}
