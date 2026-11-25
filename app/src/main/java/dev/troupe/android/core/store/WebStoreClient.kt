package dev.troupe.android.core.store

import dev.troupe.android.chat.net.ChatConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface StoreReadResult {
    data class Found(val data: JsonElement, val revision: Long) : StoreReadResult
    data object NotFound : StoreReadResult
    data class Error(val code: String?, val message: String?) : StoreReadResult
}

sealed interface StoreWriteResult {
    data class Ok(val revision: Long, val mergedData: JsonElement? = null) : StoreWriteResult
    data class Error(val code: String?, val message: String?) : StoreWriteResult
}

/** One store watch payload along with its host revision and whether it came from a reset snapshot. */
data class StoreWatchValue(
    val value: JsonElement,
    val revision: Long?,
    val reset: Boolean,
)

/**
 * Full Troupe `WebStore` client over `/ws` (`store_read`, `store_write`, `store_delete`,
 * `watch("store")`), matching `src/renderer/lib/web-persistence-api.ts`:
 * - Wraps values in `{ "_version": 1, "data": ... }` and enforces the 256 KiB value limit.
 * - Routes `sharedLww` (`baseRevision`), `sharedCas` (`expected` + `commandId`), and `device` keys.
 * - Debounces frequent writes by 500 ms per key and supports [flushAll] on backgrounding.
 */
class WebStoreClient(
    private val connection: ChatConnection,
    private val watchHub: WatchHub,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val revisions = ConcurrentHashMap<String, Long>()
    private val casValues = ConcurrentHashMap<String, JsonElement>()
    private val pendingMutex = Mutex()
    private val retryMutex = Mutex()
    private val pendingWrites = LinkedHashMap<String, PendingWrite>()
    private val failedWrites = LinkedHashMap<String, FailedWrite>()
    private val keyEpochs = HashMap<String, Long>()

    /** Unsent debounced or failed writes drained when replacing a closed connection for the same server. */
    internal data class UnsentWrites(
        val entries: Map<String, Pair<JsonElement, List<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>>>,
        val revisions: Map<String, Long>,
        val casValues: Map<String, JsonElement>,
    ) {
        fun isEmpty(): Boolean = entries.isEmpty()

        fun mergeWith(newer: UnsentWrites): UnsentWrites {
            if (isEmpty()) return newer
            if (newer.isEmpty()) return this
            val merged = LinkedHashMap<String, Pair<JsonElement, MutableList<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>>>()
            for ((k, v) in entries) merged[k] = v.first to v.second.toMutableList()
            for ((k, v) in newer.entries) {
                val waiters = merged[k]?.second ?: mutableListOf()
                waiters.addAll(v.second)
                merged[k] = v.first to waiters
            }
            return UnsentWrites(
                entries = merged.mapValues { (_, pair) -> pair.first to pair.second.toList() },
                revisions = revisions + newer.revisions,
                casValues = casValues + newer.casValues,
            )
        }

        fun abandon(reason: String) {
            val outcome = StoreWriteResult.Error("CANCELLED", reason)
            entries.values.forEach { (_, waiters) -> waiters.forEach { it.complete(outcome) } }
        }
    }

    private data class FailedWrite(
        val data: JsonElement,
        val epoch: Long,
        val waiters: MutableList<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>> = mutableListOf(),
    )

    private data class PendingWrite(
        val logicalKey: String,
        val data: JsonElement,
        val epoch: Long,
        val job: Job,
        val waiters: MutableList<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>,
    )

    init {
        scope.launch {
            connection.generation.collect { gen ->
                if (gen > 0) retryFailedWrites()
            }
        }
    }

    suspend fun read(logicalKey: String): StoreReadResult {
        val hostKey = PersistenceKeys.scopedKey(logicalKey, deviceId)
        val reply = try {
            connection.socket.request("store_read", buildJsonObject { put("key", hostKey) })
        } catch (e: IOException) {
            return StoreReadResult.Error("WS_ERROR", e.message)
        }
        if (!reply.ok) {
            return StoreReadResult.Error(reply.code, reply.message)
        }
        val obj = reply.payload as? JsonObject ?: return StoreReadResult.NotFound
        val rev = obj["revision"]?.jsonPrimitive?.longOrNull ?: 0L
        val raw = obj["value"]
        if (raw == null || raw is JsonNull) {
            revisions[hostKey] = 0L
            casValues.remove(hostKey)
            return StoreReadResult.NotFound
        }
        revisions[hostKey] = rev
        if (PersistenceKeys.scopeOf(logicalKey) == PersistenceKeys.KeyScope.SharedCas) {
            casValues[hostKey] = raw
        }
        return StoreReadResult.Found(unwrap(raw), rev)
    }

    /** Schedules a debounced write (500 ms) or writes immediately when [immediate] is true, returning the real server outcome. */
    suspend fun write(logicalKey: String, data: JsonElement, immediate: Boolean = false): StoreWriteResult {
        val wrapped = wrap(data)
        val bytes = wrapped.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > STORE_VALUE_MAX_BYTES) {
            return StoreWriteResult.Error(
                "STORE_VALUE_TOO_LARGE",
                "Value for $logicalKey is $bytes bytes (max $STORE_VALUE_MAX_BYTES)",
            )
        }
        if (immediate || debounceMs <= 0L) {
            val (epoch, existingWaiters) = pendingMutex.withLock {
                val nextEpoch = (keyEpochs[logicalKey] ?: 0L) + 1L
                keyEpochs[logicalKey] = nextEpoch
                val removedPending = pendingWrites.remove(logicalKey)
                removedPending?.job?.cancel()
                val removedFailed = failedWrites.remove(logicalKey)
                val allWaiters = buildList {
                    if (removedFailed != null) addAll(removedFailed.waiters)
                    if (removedPending != null) addAll(removedPending.waiters)
                }
                nextEpoch to allWaiters
            }
            var outcome: StoreWriteResult? = null
            return try {
                sendWriteAndRecordRetry(logicalKey, data, wrapped, epoch).also { outcome = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    pendingMutex.withLock {
                        if (keyEpochs[logicalKey] == epoch && logicalKey !in pendingWrites) {
                            failedWrites[logicalKey] = FailedWrite(data, epoch)
                        }
                    }
                }
                outcome = StoreWriteResult.Error("CANCELLED", e.message ?: "Write cancelled")
                throw e
            } finally {
                val finalOutcome = outcome ?: StoreWriteResult.Error("WRITE_FAILED", "Write aborted")
                existingWaiters.forEach { it.complete(finalOutcome) }
            }
        }
        val waiter = kotlinx.coroutines.CompletableDeferred<StoreWriteResult>()
        pendingMutex.withLock {
            val nextEpoch = (keyEpochs[logicalKey] ?: 0L) + 1L
            keyEpochs[logicalKey] = nextEpoch
            val prev = pendingWrites.remove(logicalKey)
            prev?.job?.cancel()
            val prevFailed = failedWrites.remove(logicalKey)
            val waiters = mutableListOf<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>()
            if (prevFailed != null) waiters.addAll(prevFailed.waiters)
            if (prev != null) waiters.addAll(prev.waiters)
            waiters.add(waiter)
            val job = scope.launch {
                delay(debounceMs)
                val ready = pendingMutex.withLock { pendingWrites.remove(logicalKey) }
                if (ready != null) {
                    var outcome: StoreWriteResult? = null
                    try {
                        outcome = sendWriteAndRecordRetry(ready.logicalKey, ready.data, wrap(ready.data), ready.epoch)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            pendingMutex.withLock {
                                if (keyEpochs[ready.logicalKey] == ready.epoch && ready.logicalKey !in pendingWrites) {
                                    failedWrites[ready.logicalKey] = FailedWrite(ready.data, ready.epoch)
                                }
                            }
                        }
                        outcome = StoreWriteResult.Error("CANCELLED", e.message ?: "Write cancelled")
                        throw e
                    } catch (e: Exception) {
                        outcome = StoreWriteResult.Error("WRITE_FAILED", e.message)
                    } finally {
                        val finalOutcome = outcome ?: StoreWriteResult.Error("WRITE_FAILED", "Write aborted")
                        ready.waiters.forEach { it.complete(finalOutcome) }
                    }
                }
            }
            pendingWrites[logicalKey] = PendingWrite(logicalKey, data, nextEpoch, job, waiters)
        }
        return waiter.await()
    }

    suspend fun delete(logicalKey: String): Boolean {
        val waiters = pendingMutex.withLock {
            keyEpochs[logicalKey] = (keyEpochs[logicalKey] ?: 0L) + 1L
            val removedFailed = failedWrites.remove(logicalKey)
            val removed = pendingWrites.remove(logicalKey)
            removed?.job?.cancel()
            buildList {
                if (removedFailed != null) addAll(removedFailed.waiters)
                if (removed != null) addAll(removed.waiters)
            }
        }
        waiters.forEach { it.complete(StoreWriteResult.Error("CANCELLED", "Deleted before flush")) }
        val hostKey = PersistenceKeys.scopedKey(logicalKey, deviceId)
        val reply = try {
            connection.socket.request("store_delete", buildJsonObject { put("key", hostKey) })
        } catch (_: IOException) {
            return false
        }
        if (reply.ok) {
            revisions[hostKey] = 0L
            casValues.remove(hostKey)
        }
        return reply.ok
    }

    /** Watches a store key for cross-device live changes (e.g., `settings/app`). Emits `JsonNull` on delete. */
    fun watch(logicalKey: String): Flow<JsonElement?> =
        watchWithRevision(logicalKey).mapNotNull { it.value }

    /** Watches a store key along with its host revision and reset flag so callers can ignore stale reads. */
    fun watchWithRevision(logicalKey: String): Flow<StoreWatchValue> {
        val hostKey = PersistenceKeys.scopedKey(logicalKey, deviceId)
        return watchHub.watch(WatchHub.STORE, hostKey).mapNotNull { update ->
            val rawPayload = (if (update.reset) update.state else update.delta) as? JsonObject ?: return@mapNotNull null
            val rev = rawPayload["revision"]?.jsonPrimitive?.longOrNull
            if (rev != null) revisions[hostKey] = rev
            val value = rawPayload["value"]
            val resolved = when {
                value == null || value is JsonNull -> {
                    casValues.remove(hostKey)
                    JsonNull
                }
                else -> {
                    if (PersistenceKeys.scopeOf(logicalKey) == PersistenceKeys.KeyScope.SharedCas) {
                        casValues[hostKey] = value
                    }
                    unwrap(value)
                }
            }
            StoreWatchValue(value = resolved, revision = rev, reset = update.reset)
        }
    }

    /** Immediately flushes all debounced and failed writes (e.g., when moving to background or switching servers). */
    suspend fun flushAll() {
        val queued = pendingMutex.withLock {
            val all = pendingWrites.values.toList()
            pendingWrites.clear()
            all.forEach { it.job.cancel() }
            all
        }
        var completedCount = 0
        var abortResult: StoreWriteResult? = null
        try {
            for (item in queued) {
                var outcome: StoreWriteResult? = null
                try {
                    outcome = sendWriteAndRecordRetry(item.logicalKey, item.data, wrap(item.data), item.epoch)
                    completedCount++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    val cancelled = StoreWriteResult.Error("CANCELLED", e.message ?: "Flush interrupted")
                    outcome = cancelled
                    abortResult = cancelled
                    throw e
                } finally {
                    if (outcome != null && abortResult == null) {
                        item.waiters.forEach { it.complete(outcome) }
                    }
                }
            }
        } finally {
            if (completedCount < queued.size) {
                val remaining = queued.subList(completedCount, queued.size)
                val fallback = abortResult ?: StoreWriteResult.Error("WRITE_FAILED", "Flush aborted")
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        pendingMutex.withLock {
                            for (item in remaining) {
                                if (keyEpochs[item.logicalKey] == item.epoch && item.logicalKey !in pendingWrites) {
                                    failedWrites[item.logicalKey] = FailedWrite(item.data, item.epoch)
                                }
                            }
                        }
                    }
                } finally {
                    for (item in remaining) {
                        item.waiters.forEach { it.complete(fallback) }
                    }
                }
            }
        }
        retryFailedWrites()
    }

    /**
     * Cancels local debounce timers and drains all unsent (`pendingWrites` + `failedWrites`) entries
     * without attempting I/O on a dead socket, so a replacement [WebStoreClient] for the same server
     * can flush them once reconnected.
     */
    internal suspend fun drainUnsentWrites(): UnsentWrites = pendingMutex.withLock {
        val merged = LinkedHashMap<String, Pair<JsonElement, MutableList<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>>>()
        for ((key, failed) in failedWrites) {
            merged[key] = failed.data to failed.waiters.toMutableList()
            failed.waiters.clear()
        }
        failedWrites.clear()
        for ((key, pending) in pendingWrites) {
            pending.job.cancel()
            val waiters = merged[key]?.second ?: mutableListOf()
            waiters.addAll(pending.waiters)
            merged[key] = pending.data to waiters
        }
        pendingWrites.clear()
        UnsentWrites(
            entries = merged.mapValues { (_, pair) -> pair.first to pair.second.toList() },
            revisions = HashMap(revisions),
            casValues = HashMap(casValues),
        )
    }

    /**
     * Adopts [unsent] writes from a previous [WebStoreClient] for the same server and flushes them
     * immediately if already connected, or automatically on the next connection generation.
     */
    internal suspend fun adoptUnsentWrites(unsent: UnsentWrites, retryImmediately: Boolean = true) {
        if (unsent.isEmpty()) return
        unsent.revisions.forEach { (k, v) -> revisions.putIfAbsent(k, v) }
        unsent.casValues.forEach { (k, v) -> casValues.putIfAbsent(k, v) }
        val shouldRetryNow = pendingMutex.withLock {
            for ((key, pair) in unsent.entries) {
                val (data, waiters) = pair
                val existingPending = pendingWrites[key]
                if (existingPending != null) {
                    existingPending.waiters.addAll(waiters)
                } else {
                    val epoch = (keyEpochs[key] ?: 0L) + 1L
                    keyEpochs[key] = epoch
                    val existingFailed = failedWrites[key]
                    val combined = mutableListOf<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>>()
                    if (existingFailed != null) combined.addAll(existingFailed.waiters)
                    combined.addAll(waiters)
                    failedWrites[key] = FailedWrite(data, epoch, combined)
                }
            }
            retryImmediately && connection.generation.value > 0
        }
        if (shouldRetryNow) {
            retryFailedWrites()
        }
    }

    /** Flushes any queued failed or adopted writes once the socket is connected. */
    internal suspend fun flushPendingRetries() = retryFailedWrites()

    private suspend fun retryFailedWrites() = retryMutex.withLock {
        val snapshot = pendingMutex.withLock {
            failedWrites.entries.map { it.key to it.value }
        }
        for ((key, failed) in snapshot) {
            val result = sendWriteAndRecordRetry(key, failed.data, wrap(failed.data), failed.epoch)
            if (result !is StoreWriteResult.Error || result.code != "WS_ERROR") {
                val waiters = pendingMutex.withLock {
                    failed.waiters.toList().also { failed.waiters.clear() }
                }
                waiters.forEach { it.complete(result) }
            }
        }
    }

    private suspend fun sendWriteAndRecordRetry(
        logicalKey: String,
        data: JsonElement,
        wrapped: JsonObject,
        epoch: Long,
    ): StoreWriteResult {
        val result = sendWrite(logicalKey, wrapped)
        pendingMutex.withLock {
            val isLatestEpoch = keyEpochs[logicalKey] == epoch
            if (result is StoreWriteResult.Error && result.code == "WS_ERROR") {
                if (isLatestEpoch && logicalKey !in pendingWrites) {
                    val existing = failedWrites[logicalKey]
                    val waiters = if (existing != null && existing.epoch == epoch) existing.waiters else mutableListOf()
                    failedWrites[logicalKey] = FailedWrite(data, epoch, waiters)
                }
            } else {
                val existing = failedWrites[logicalKey]
                if (existing != null && existing.epoch <= epoch) {
                    failedWrites.remove(logicalKey)
                }
            }
        }
        return result
    }

    private suspend fun sendWrite(logicalKey: String, wrapped: JsonObject): StoreWriteResult {
        val hostKey = PersistenceKeys.scopedKey(logicalKey, deviceId)
        val keyScope = PersistenceKeys.scopeOf(logicalKey)
        val cmdId = if (keyScope == PersistenceKeys.KeyScope.SharedCas) UUID.randomUUID().toString() else null
        val payload = buildJsonObject {
            put("key", hostKey)
            put("value", wrapped)
            when (keyScope) {
                PersistenceKeys.KeyScope.SharedLww -> put("baseRevision", revisions[hostKey] ?: 0L)
                PersistenceKeys.KeyScope.SharedCas -> {
                    put("expected", casValues[hostKey] ?: JsonNull)
                    if (cmdId != null) put("commandId", cmdId)
                }
                PersistenceKeys.KeyScope.Device -> Unit
            }
        }
        val reply = try {
            connection.socket.request("store_write", payload, commandId = cmdId)
        } catch (e: IOException) {
            return StoreWriteResult.Error("WS_ERROR", e.message)
        }
        if (!reply.ok) {
            return StoreWriteResult.Error(reply.code, reply.message)
        }
        val obj = reply.payload as? JsonObject
        val rev = obj?.get("revision")?.jsonPrimitive?.longOrNull ?: ((revisions[hostKey] ?: 0L) + 1L)
        revisions[hostKey] = rev
        val returnedVal = obj?.get("value")
        if (keyScope == PersistenceKeys.KeyScope.SharedCas) {
            casValues[hostKey] = returnedVal ?: wrapped
        }
        val merged = obj?.get("merged")?.jsonPrimitive?.booleanOrNull == true
        val mergedData = if (merged && returnedVal != null && returnedVal !is JsonNull) unwrap(returnedVal) else null
        return StoreWriteResult.Ok(rev, mergedData)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val STORE_VALUE_MAX_BYTES = 256 * 1024
        const val DEFAULT_DEBOUNCE_MS = 500L

        fun wrap(data: JsonElement): JsonObject = buildJsonObject {
            put("_version", JsonPrimitive(CURRENT_VERSION))
            put("data", data)
        }

        fun unwrap(raw: JsonElement): JsonElement {
            val obj = raw as? JsonObject ?: return raw
            if ("_version" in obj && "data" in obj) {
                return obj.getValue("data")
            }
            return raw
        }
    }
}
