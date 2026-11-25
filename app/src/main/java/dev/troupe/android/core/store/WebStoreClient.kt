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
    private val failedWrites = LinkedHashMap<String, JsonElement>()

    private data class PendingWrite(
        val logicalKey: String,
        val data: JsonElement,
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
            val existingWaiters = cancelPending(logicalKey)
            val result = sendWriteAndRecordRetry(logicalKey, data, wrapped)
            existingWaiters.forEach { it.complete(result) }
            return result
        }
        val waiter = kotlinx.coroutines.CompletableDeferred<StoreWriteResult>()
        pendingMutex.withLock {
            val prev = pendingWrites.remove(logicalKey)
            prev?.job?.cancel()
            val waiters = prev?.waiters ?: mutableListOf()
            waiters.add(waiter)
            val job = scope.launch {
                delay(debounceMs)
                val ready = pendingMutex.withLock { pendingWrites.remove(logicalKey) }
                if (ready != null) {
                    val result = sendWriteAndRecordRetry(ready.logicalKey, ready.data, wrap(ready.data))
                    ready.waiters.forEach { it.complete(result) }
                }
            }
            pendingWrites[logicalKey] = PendingWrite(logicalKey, data, job, waiters)
        }
        return waiter.await()
    }

    suspend fun delete(logicalKey: String): Boolean {
        val waiters = cancelPending(logicalKey)
        waiters.forEach { it.complete(StoreWriteResult.Error("CANCELLED", "Deleted before flush")) }
        pendingMutex.withLock { failedWrites.remove(logicalKey) }
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
            val result = sendWriteAndRecordRetry(item.logicalKey, item.data, wrap(item.data))
            item.waiters.forEach { it.complete(result) }
        }
        retryFailedWrites()
    }

    private suspend fun retryFailedWrites() {
        val snapshot = pendingMutex.withLock {
            val entries = failedWrites.entries.map { it.key to it.value }
            failedWrites.clear()
            entries
        }
        for ((key, data) in snapshot) {
            sendWriteAndRecordRetry(key, data, wrap(data))
        }
    }

    private suspend fun sendWriteAndRecordRetry(
        logicalKey: String,
        data: JsonElement,
        wrapped: JsonObject,
    ): StoreWriteResult {
        val result = sendWrite(logicalKey, wrapped)
        pendingMutex.withLock {
            if (result is StoreWriteResult.Error && result.code == "WS_ERROR") {
                if (logicalKey !in pendingWrites) {
                    failedWrites[logicalKey] = data
                }
            } else {
                failedWrites.remove(logicalKey)
            }
        }
        return result
    }

    private suspend fun cancelPending(
        logicalKey: String,
    ): List<kotlinx.coroutines.CompletableDeferred<StoreWriteResult>> = pendingMutex.withLock {
        val removed = pendingWrites.remove(logicalKey)
        removed?.job?.cancel()
        removed?.waiters?.toList().orEmpty()
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
