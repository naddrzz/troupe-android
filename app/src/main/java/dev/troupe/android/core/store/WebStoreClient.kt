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
    private val pendingWrites = LinkedHashMap<String, PendingWrite>()
    private val failedWrites = LinkedHashMap<String, FailedWrite>()
    private val keyEpochs = HashMap<String, Long>()

    private data class FailedWrite(
        val data: JsonElement,
        val epoch: Long,
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
                val removed = pendingWrites.remove(logicalKey)
                removed?.job?.cancel()
                nextEpoch to removed?.waiters?.toList().orEmpty()
            }
            var outcome: StoreWriteResult? = null
            return try {
                sendWriteAndRecordRetry(logicalKey, data, wrapped, epoch).also { outcome = it }
            } finally {
                val finalOutcome = outcome ?: StoreWriteResult.Error("CANCELLED", "Write cancelled")
                existingWaiters.forEach { it.complete(finalOutcome) }
            }
        }
        val waiter = kotlinx.coroutines.CompletableDeferred<StoreWriteResult>()
        pendingMutex.withLock {
            val nextEpoch = (keyEpochs[logicalKey] ?: 0L) + 1L
            keyEpochs[logicalKey] = nextEpoch
            val prev = pendingWrites.remove(logicalKey)
            prev?.job?.cancel()
            val waiters = prev?.waiters ?: mutableListOf()
            waiters.add(waiter)
            val job = scope.launch {
                delay(debounceMs)
                val ready = pendingMutex.withLock { pendingWrites.remove(logicalKey) }
                if (ready != null) {
                    var outcome: StoreWriteResult? = null
                    try {
                        outcome = sendWriteAndRecordRetry(ready.logicalKey, ready.data, wrap(ready.data), ready.epoch)
                    } catch (t: Throwable) {
                        outcome = StoreWriteResult.Error("CANCELLED", t.message)
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    } finally {
                        val finalOutcome = outcome ?: StoreWriteResult.Error("CANCELLED", "Write cancelled")
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
            failedWrites.remove(logicalKey)
            val removed = pendingWrites.remove(logicalKey)
            removed?.job?.cancel()
            removed?.waiters?.toList().orEmpty()
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

    /** Watches a store key for cross-device live changes (e.g., `settings/app`). Emits `null` on delete. */
    fun watch(logicalKey: String): Flow<JsonElement?> {
        val hostKey = PersistenceKeys.scopedKey(logicalKey, deviceId)
        return watchHub.watch(WatchHub.STORE, hostKey).mapNotNull { update ->
            val rawPayload = (if (update.reset) update.state else update.delta) as? JsonObject ?: return@mapNotNull null
            val rev = rawPayload["revision"]?.jsonPrimitive?.longOrNull
            if (rev != null) revisions[hostKey] = rev
            val value = rawPayload["value"]
            when {
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
        for (item in queued) {
            var outcome: StoreWriteResult? = null
            try {
                outcome = sendWriteAndRecordRetry(item.logicalKey, item.data, wrap(item.data), item.epoch)
            } finally {
                val finalOutcome = outcome ?: StoreWriteResult.Error("CANCELLED", "Flush interrupted")
                item.waiters.forEach { it.complete(finalOutcome) }
            }
        }
        retryFailedWrites()
    }

    private suspend fun retryFailedWrites() {
        val snapshot = pendingMutex.withLock {
            failedWrites.entries.map { it.key to it.value }
        }
        for ((key, failed) in snapshot) {
            sendWriteAndRecordRetry(key, failed.data, wrap(failed.data), failed.epoch)
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
                    failedWrites[logicalKey] = FailedWrite(data, epoch)
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
