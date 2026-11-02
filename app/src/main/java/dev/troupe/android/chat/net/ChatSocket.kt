package dev.troupe.android.chat.net

import dev.troupe.android.chat.protocol.ChatErrorCodes
import dev.troupe.android.chat.protocol.ChatFrame
import dev.troupe.android.chat.protocol.ChatRequest
import dev.troupe.android.chat.protocol.parseChatFrame
import dev.troupe.android.core.net.ConnectionFailure
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.LifecycleSocket
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.terminal.net.ClientIdentity
import dev.troupe.android.terminal.net.ClientIdentityStore
import dev.troupe.android.terminal.protocol.PROTOCOL_VERSION
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
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** The chat socket dropped while a request was waiting for its reply. */
class ChatDisconnected : IOException("chat socket disconnected")

class ChatRequestTimeout(type: String) : IOException("no reply to '$type' within 15 s")

class ChatSocketFactory @Inject constructor(
    private val client: OkHttpClient,
    private val identity: ClientIdentityStore,
) {
    fun create(address: ServerAddress, token: String) = ChatSocket(client, address, token, identity)
}

/**
 * A Paired Server's `/ws`, one connection at a time. [open] connects and runs the `authenticate`
 * handshake (protocol 7, device and instance ids, token); a version mismatch or rejected token is
 * final. [request] correlates replies by `id`; session events, `gap` and `watch` frames go to
 * [frames], which outlives each connection. The host's `heartbeat` frames only feed [activity].
 */
class ChatSocket internal constructor(
    private val client: OkHttpClient,
    private val address: ServerAddress,
    private val token: String,
    private val identity: ClientIdentityStore,
) : LifecycleSocket {
    // Unbounded: an event must never be dropped because a collector is slow.
    private val _frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = Int.MAX_VALUE)
    /** Session events, `gap` and `watch` frames, in arrival order. Only collectors subscribed at arrival see a frame. */
    val frames: SharedFlow<ChatFrame> = _frames.asSharedFlow()

    private val _activity = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val activity: Flow<Unit> = _activity.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ChatFrame.Reply>>()
    @Volatile private var connection: Connection? = null

    private class Connection {
        lateinit var webSocket: WebSocket
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Int?>()
        @Volatile var authenticated = false
    }

    override suspend fun open(): ConnectionState {
        connection?.webSocket?.cancel()
        val current = Connection()
        current.webSocket = client.newWebSocket(Request.Builder().url(address.wsUrl("ws")).build(), Listener(current))
        connection = current
        val result = try {
            current.opened.await()
            authenticate(current, identity.get())
        } catch (e: IOException) {
            ConnectionState.Failed(ConnectionFailure.Unreachable)
        }
        if (result == ConnectionState.Connected) current.authenticated = true else current.webSocket.cancel()
        return result
    }

    override suspend fun awaitClose(): Int? = connection?.closed?.await()

    override fun abort() {
        connection?.webSocket?.cancel()
    }

    private suspend fun authenticate(connection: Connection, id: ClientIdentity): ConnectionState {
        val reply = send(connection, "authenticate", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("deviceId", id.deviceId)
            put("clientInstanceId", id.clientInstanceId)
            put("token", token)
        }, commandId = null)
        if (reply.ok) return ConnectionState.Connected
        val reason = when (reply.code) {
            ChatErrorCodes.UNAUTHORIZED -> ConnectionFailure.Unauthorized
            ChatErrorCodes.PROTOCOL_VERSION_MISMATCH -> ConnectionFailure.ProtocolMismatch
            else -> ConnectionFailure.Refused
        }
        return ConnectionState.Failed(reason, reply.message)
    }

    /**
     * Sends a request and waits for its reply. A mutating request carries a client-minted [commandId],
     * so a resend never runs twice. Throws [ChatDisconnected] when the socket is not connected or drops,
     * [ChatRequestTimeout] after 15 s.
     */
    suspend fun request(type: String, payload: JsonObject, commandId: String? = null): ChatFrame.Reply {
        // The connection that was authenticated is the one the request goes out on.
        val current = connection?.takeIf { it.authenticated } ?: throw ChatDisconnected()
        return send(current, type, payload, commandId)
    }

    private suspend fun send(connection: Connection, type: String, payload: JsonObject, commandId: String?): ChatFrame.Reply {
        val socket = connection.webSocket
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<ChatFrame.Reply>()
        pending[id] = reply
        val frame = TroupeJson.encodeToString(ChatRequest.serializer(), ChatRequest(id, type, payload, commandId))
        if (!socket.send(frame)) {
            pending.remove(id)
            throw ChatDisconnected()
        }
        return try {
            withTimeout(15.seconds) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw ChatRequestTimeout(type)
        } finally {
            pending.remove(id)
        }
    }

    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(ChatDisconnected()) }
        pending.clear()
    }

    private inner class Listener(private val connection: Connection) : WebSocketListener() {
        private val current get() = this@ChatSocket.connection === connection

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connection.opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _activity.tryEmit(Unit)
            when (val frame = parseChatFrame(text)) {
                is ChatFrame.Reply -> pending[frame.id]?.complete(frame)
                is ChatFrame.Event, is ChatFrame.Gap, is ChatFrame.Watch -> if (current) _frames.tryEmit(frame)
                ChatFrame.Heartbeat, is ChatFrame.Unknown -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connection.opened.completeExceptionally(ChatDisconnected())
            ended(null)
        }

        /** Only the current connection's end fails the requests in flight: they were all sent on it. */
        private fun ended(code: Int?) {
            connection.authenticated = false
            if (connection.closed.complete(code) && current) failPending()
        }
    }
}
