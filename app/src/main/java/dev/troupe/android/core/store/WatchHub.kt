package dev.troupe.android.core.store

import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.chat.net.ChatSocket
import dev.troupe.android.chat.protocol.ChatFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** One delivered watch snapshot (`reset == true`) or incremental delta (`reset == false`). */
data class WatchUpdate(
    val watch: String,
    val scope: String,
    val hostEpoch: String,
    val n: Long,
    val reset: Boolean,
    val state: JsonElement?,
    val delta: JsonElement?,
)

/**
 * Multiplexes all 7 Troupe `/ws` watches (`projects`, `chat_history`, `agents`, `chat_activity`,
 * `fs`, `terminals`, `store`) over one [ChatConnection].
 * - Ref-counts collectors per `(watch, scope)` so duplicate subscribers share one host watch.
 * - Re-registers every active watch automatically on each new connection `generation`.
 * - Enforces `(hostEpoch, n)` continuity; any gap triggers an automatic `unwatch` + `watch` resync.
 */
class WatchHub(
    private val socket: ChatSocket,
    private val generation: StateFlow<Int>,
    private val scope: CoroutineScope,
) {
    constructor(connection: ChatConnection, scope: CoroutineScope) :
        this(connection.socket, connection.generation, scope)

    private data class Key(val watch: String, val scope: String)

    private class ChannelEntry(
        val key: Key,
        val flow: MutableSharedFlow<WatchUpdate> = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        ),
        var refCount: Int = 0,
        var hostEpoch: String? = null,
        var lastN: Long = 0,
        var lastReset: WatchUpdate? = null,
    )

    private val mutex = Mutex()
    private val channels = LinkedHashMap<Key, ChannelEntry>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            socket.frames.collect { frame ->
                if (frame is ChatFrame.Watch) {
                    handleWatchFrame(frame)
                }
            }
        }
        scope.launch {
            generation.collect { gen ->
                if (gen > 0) resubscribeAll()
            }
        }
    }

    /** Forces an `unwatch` + `watch` resync for an active channel (e.g. on a `{resync: true}` delta). */
    fun resync(watch: String, watchScope: String = "") {
        scope.launch { triggerResync(Key(watch, watchScope)) }
    }

    /** Subscribes to `(watch, watchScope)` and emits the initial reset state plus every delta. */
    fun watch(watch: String, watchScope: String = ""): Flow<WatchUpdate> {
        val key = Key(watch, watchScope)
        return flow {
            val (entry, shouldSubscribe) = mutex.withLock {
                val e = channels.getOrPut(key) { ChannelEntry(key) }
                e.refCount++
                e to (e.refCount == 1)
            }
            try {
                var lastDeliveredEpoch: String? = null
                var lastDeliveredN: Long = 0
                var awaitingReset = false
                // onSubscription runs AFTER the collector is registered on entry.flow, guaranteeing
                // zero dropped frames between emitting lastReset / sending watch and collecting deltas.
                entry.flow.onSubscription {
                    val snapshot = mutex.withLock { entry.lastReset }
                    if (snapshot != null) {
                        lastDeliveredEpoch = snapshot.hostEpoch
                        lastDeliveredN = snapshot.n
                        emit(snapshot)
                    }
                    if (shouldSubscribe) {
                        scope.launch { sendWatch(key) }
                    }
                }.collect { update ->
                    when {
                        update.reset -> {
                            lastDeliveredEpoch = update.hostEpoch
                            lastDeliveredN = update.n
                            awaitingReset = false
                            emit(update)
                        }
                        awaitingReset -> Unit
                        lastDeliveredEpoch != null && update.hostEpoch == lastDeliveredEpoch && update.n <= lastDeliveredN -> Unit
                        lastDeliveredEpoch != null && (update.hostEpoch != lastDeliveredEpoch || update.n != lastDeliveredN + 1) -> {
                            // Subscriber fell behind (e.g., DROP_OLDEST overflow while suspended on emit(snapshot)):
                            // trigger a clean resync so this subscriber receives a fresh reset snapshot.
                            awaitingReset = true
                            scope.launch { triggerResync(key) }
                        }
                        else -> {
                            lastDeliveredEpoch = update.hostEpoch
                            lastDeliveredN = update.n
                            emit(update)
                        }
                    }
                }
            } finally {
                val shouldUnsubscribe = withContext(NonCancellable) {
                    mutex.withLock {
                        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
                        if (entry.refCount == 0 && channels[key] === entry) {
                            channels.remove(key)
                            true
                        } else {
                            false
                        }
                    }
                }
                if (shouldUnsubscribe) {
                    scope.launch { sendUnwatch(key) }
                }
            }
        }
    }

    private suspend fun triggerResync(key: Key) {
        val active = mutex.withLock {
            val entry = channels[key] ?: return@withLock false
            if (entry.hostEpoch == null) return@withLock false
            entry.hostEpoch = null
            entry.lastN = 0
            entry.lastReset = null
            true
        }
        if (active) {
            sendUnwatch(key)
            sendWatch(key)
        }
    }

    private suspend fun handleWatchFrame(frame: ChatFrame.Watch) {
        val key = Key(frame.watch, frame.scope)
        var needsResync = false
        val update = WatchUpdate(
            watch = frame.watch,
            scope = frame.scope,
            hostEpoch = frame.hostEpoch,
            n = frame.n,
            reset = frame.reset,
            state = frame.state,
            delta = frame.delta,
        )
        mutex.withLock {
            val entry = channels[key] ?: return
            if (frame.reset) {
                entry.hostEpoch = frame.hostEpoch
                entry.lastN = frame.n
                entry.lastReset = update
                entry.flow.tryEmit(update)
            } else {
                val contiguous = entry.hostEpoch == frame.hostEpoch && frame.n == entry.lastN + 1
                if (contiguous) {
                    entry.lastN = frame.n
                    entry.flow.tryEmit(update)
                } else {
                    // Gap or host epoch mismatch: trigger clean resync.
                    entry.hostEpoch = null
                    entry.lastN = 0
                    entry.lastReset = null
                    needsResync = true
                }
            }
        }
        if (needsResync) {
            sendUnwatch(key)
            sendWatch(key)
        }
    }

    private suspend fun resubscribeAll() {
        val keys = mutex.withLock {
            channels.values.forEach {
                it.hostEpoch = null
                it.lastN = 0
                it.lastReset = null
            }
            channels.keys.toList()
        }
        for (key in keys) {
            sendWatch(key)
        }
    }

    private suspend fun sendWatch(key: Key) {
        try {
            socket.request(
                type = "watch",
                payload = buildJsonObject {
                    put("watch", key.watch)
                    if (key.scope.isNotEmpty()) put("scope", key.scope)
                },
            )
        } catch (_: IOException) {
            // Reconnect generation collector will re-subscribe automatically.
        }
    }

    private suspend fun sendUnwatch(key: Key) {
        try {
            socket.request(
                type = "unwatch",
                payload = buildJsonObject {
                    put("watch", key.watch)
                    if (key.scope.isNotEmpty()) put("scope", key.scope)
                },
            )
        } catch (_: IOException) {
            // Connection already dropped; host forgot watches.
        }
    }

    companion object {
        const val PROJECTS = "projects"
        const val CHAT_HISTORY = "chat_history"
        const val AGENTS = "agents"
        const val CHAT_ACTIVITY = "chat_activity"
        const val FS = "fs"
        const val TERMINALS = "terminals"
        const val STORE = "store"
    }
}
