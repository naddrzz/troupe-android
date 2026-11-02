package dev.troupe.android.terminal.net

import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.LifecycleSocket
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.terminal.protocol.PROTOCOL_VERSION
import dev.troupe.android.terminal.protocol.TerminalErrorCodes
import dev.troupe.android.terminal.protocol.TerminalFrame
import dev.troupe.android.terminal.protocol.TerminalRequest
import dev.troupe.android.terminal.protocol.parseTerminalFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** The socket dropped while a request was waiting for its reply. */
class TerminalDisconnected : IOException("terminal socket disconnected")

class TerminalRequestTimeout(type: String) : IOException("no reply to '$type' within 15 s")

class TerminalSocketFactory @Inject constructor(
    client: OkHttpClient,
    private val identity: ClientIdentityStore,
) {
    /**
     * `/terminal/ws` sends no heartbeat frames, so WebSocket pings prove the server is alive: a
     * ping without a pong by the next one fails the connection, and the lifecycle reconnects.
     */
    private val client = client.newBuilder().pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS).build()

    fun create(address: ServerAddress, token: String) = TerminalSocket(client, address, token, identity)

    private companion object {
        const val PING_INTERVAL_S = 10L
    }
}

/**
 * A Paired Server's `/terminal/ws`, one connection at a time. [connect] opens a connection and runs
 * the `authenticate` handshake (protocol 7, device and instance ids, token); a version mismatch or
 * rejected token is final. [request] correlates replies by `id`; every other server frame (output,
 * replay) goes to [frames], which outlives each connection. Reconnecting is
 * [dev.troupe.android.core.net.ConnectionLifecycle]'s job.
 */
class TerminalSocket internal constructor(
    private val client: OkHttpClient,
    private val address: ServerAddress,
    private val token: String,
    private val identity: ClientIdentityStore,
) : LifecycleSocket {
    // Unbounded: output must never be dropped because a collector is slow.
    private val _frames = MutableSharedFlow<TerminalFrame>(extraBufferCapacity = Int.MAX_VALUE)
    /** Server frames other than replies, in arrival order. Only collectors subscribed at arrival see a frame. */
    val frames: SharedFlow<TerminalFrame> = _frames.asSharedFlow()

    private val _activity = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val activity: Flow<Unit> = _activity.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<TerminalFrame.Reply>>()
    @Volatile private var connection: Connection? = null

    /** One WebSocket: [closed] completes with its close code (null for a failure) when it ends. */
    private class Connection {
        lateinit var webSocket: WebSocket
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Int?>()
        @Volatile var authenticated = false
    }

    /** Opens a new connection (dropping any previous one) and authenticates it. */
    suspend fun connect(): ConnectionState {
        connection?.webSocket?.cancel()
        val current = Connection()
        current.webSocket = client.newWebSocket(Request.Builder().url(address.wsUrl("terminal/ws")).build(), Listener(current))
        connection = current
        val result = try {
            current.opened.await()
            authenticate(identity.get())
        } catch (e: IOException) {
            ConnectionState.Failed(ConnectionFailure.Unreachable)
        }
        if (result == ConnectionState.Connected) current.authenticated = true else current.webSocket.cancel()
        return result
    }

    override suspend fun open(): ConnectionState = connect()

    override suspend fun awaitClose(): Int? = connection?.closed?.await()

    override fun abort() {
        connection?.webSocket?.cancel()
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

    /**
     * Sends a request and waits for its reply. Ledgered requests (`spawn`, `write`, `kill`) carry a `commandId`.
     * Throws [TerminalDisconnected] when the socket is not connected or drops, [TerminalRequestTimeout] after 15 s.
     */
    suspend fun request(type: String, payload: JsonObject, ledgered: Boolean = false): TerminalFrame.Reply {
        if (connection?.authenticated != true) throw TerminalDisconnected()
        return send(type, payload, commandId = if (ledgered) UUID.randomUUID().toString() else null)
    }

    fun close() {
        connection?.webSocket?.close(1000, null)
        failPending()
    }

    private suspend fun send(type: String, payload: JsonObject, commandId: String?): TerminalFrame.Reply {
        val socket = connection?.webSocket ?: throw TerminalDisconnected()
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

    private inner class Listener(private val connection: Connection) : WebSocketListener() {
        private val current get() = this@TerminalSocket.connection === connection

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connection.opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _activity.tryEmit(Unit)
            val frame = parseTerminalFrame(text)
            if (frame is TerminalFrame.Reply) pending[frame.id]?.complete(frame) else _frames.tryEmit(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connection.opened.completeExceptionally(TerminalDisconnected())
            ended(null)
        }

        /** Only the current connection's end fails the requests in flight: they were all sent on it. */
        private fun ended(code: Int?) {
            connection.authenticated = false
            if (connection.closed.complete(code) && current) failPending()
        }
    }
}
