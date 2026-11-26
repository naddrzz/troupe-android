package dev.troupe.android.core.net

import dev.troupe.android.core.protocol.Health
import dev.troupe.android.core.protocol.HttpEnvelope
import dev.troupe.android.core.protocol.ProjectsState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class Unreachable(val cause: String) : ApiResult<Nothing>
    /** [status] is the HTTP status; a 2xx here means the reply did not match the contract. Null for a `success: false` envelope in a 2xx reply. */
    data class ServerError(val message: String, val code: String?, val status: Int? = null) : ApiResult<Nothing>
}

/**
 * The OkHttp client every server call and socket shares. No logging interceptor: headers carry the token.
 * [CleartextGuard] runs before the first request (so nothing connects) and again on every redirect.
 */
fun troupeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .addInterceptor(CleartextGuard)
    .addNetworkInterceptor(CleartextGuard)
    .build()

/** A plain `http://` (or `ws://`) request to a host [TransportPolicy] keeps on TLS, refused before it was sent. */
class CleartextRefused(host: String) : IOException("cleartext to $host is not allowed")

/** Enforces [TransportPolicy] per request: the network security config permits cleartext everywhere. */
private object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !TransportPolicy.isCleartextAllowed(url.host)) throw CleartextRefused(url.host)
        return chain.proceed(chain.request())
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * The Troupe Server's HTTP routes. The token travels only in `Authorization: Bearer` when non-blank;
 * no `Origin` header is sent (the server checks Origin only when present).
 */
class ServerApi @Inject constructor(private val client: OkHttpClient) {

    /** Shares [client]'s pool, interceptors and timeouts; only the silent retry after a connection failure is off. */
    private val noRetryClient by lazy { client.newBuilder().retryOnConnectionFailure(false).build() }

    suspend fun health(address: ServerAddress, token: String? = null): ApiResult<Health> =
        getRaw(address, "health", token = token, Health.serializer())

    suspend fun projects(address: ServerAddress, token: String?): ApiResult<ProjectsState> =
        getEnveloped(address, "projects", token, ProjectsState.serializer())

    suspend fun <T> getRaw(
        address: ServerAddress,
        path: String,
        token: String?,
        serializer: KSerializer<T>,
    ): ApiResult<T> = execute(address, "GET", path, token, bodyJson = null) { body ->
        ApiResult.Ok(TroupeJson.decodeFromString(serializer, body))
    }

    suspend fun <T> getEnveloped(
        address: ServerAddress,
        path: String,
        token: String?,
        serializer: KSerializer<T>,
    ): ApiResult<T> = execute(address, "GET", path, token, bodyJson = null) { body ->
        enveloped(body, serializer)
    }

    suspend fun getUnit(
        address: ServerAddress,
        path: String,
        token: String?,
    ): ApiResult<Unit> = execute(address, "GET", path, token, bodyJson = null) { body ->
        envelopedUnit(body)
    }

    suspend fun <T> postEnveloped(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String = "{}",
        serializer: KSerializer<T>,
        retryOnConnectionFailure: Boolean = true,
    ): ApiResult<T> = execute(address, "POST", path, token, bodyJson = bodyJson, retryOnConnectionFailure = retryOnConnectionFailure) { body ->
        enveloped(body, serializer)
    }

    /**
     * [retryOnConnectionFailure] `false`: OkHttp sends the request at most once, neither after a connection failure nor
     * as a follow-up such as a `503` with `Retry-After: 0` (for a write that must not be repeated).
     */
    suspend fun postUnit(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String = "{}",
        retryOnConnectionFailure: Boolean = true,
    ): ApiResult<Unit> = execute(address, "POST", path, token, bodyJson = bodyJson, retryOnConnectionFailure = retryOnConnectionFailure) { body ->
        envelopedUnit(body)
    }

    suspend fun <T> putEnveloped(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String = "{}",
        serializer: KSerializer<T>,
    ): ApiResult<T> = execute(address, "PUT", path, token, bodyJson = bodyJson) { body ->
        enveloped(body, serializer)
    }

    suspend fun putUnit(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String = "{}",
    ): ApiResult<Unit> = execute(address, "PUT", path, token, bodyJson = bodyJson) { body ->
        envelopedUnit(body)
    }

    suspend fun <T> deleteEnveloped(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String? = null,
        serializer: KSerializer<T>,
    ): ApiResult<T> = execute(address, "DELETE", path, token, bodyJson = bodyJson) { body ->
        enveloped(body, serializer)
    }

    suspend fun deleteUnit(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String? = null,
    ): ApiResult<Unit> = execute(address, "DELETE", path, token, bodyJson = bodyJson) { body ->
        envelopedUnit(body)
    }

    suspend fun getJsonElement(
        address: ServerAddress,
        path: String,
        token: String?,
    ): ApiResult<JsonElement> = getEnveloped(address, path, token, JsonElement.serializer())

    suspend fun postJsonElement(
        address: ServerAddress,
        path: String,
        token: String?,
        bodyJson: String = "{}",
    ): ApiResult<JsonElement> = postEnveloped(address, path, token, bodyJson, JsonElement.serializer())

    private suspend fun <T> execute(
        address: ServerAddress,
        method: String,
        path: String,
        token: String?,
        bodyJson: String?,
        retryOnConnectionFailure: Boolean = true,
        decode: (String) -> ApiResult<T>,
    ): ApiResult<T> {
        val cleanPath = path.removePrefix("/")
        // A one-shot body also stops OkHttp's resend on a `503` with `Retry-After: 0`, which ignores retryOnConnectionFailure.
        val reqBody = bodyJson?.let { json ->
            json.toRequestBody(JSON_MEDIA_TYPE).let { if (retryOnConnectionFailure) it else OneShotBody(it) }
        }
        val builder = Request.Builder().url(address.httpUrl(cleanPath))
        when (method) {
            "GET" -> builder.get()
            // postEnveloped and postUnit always pass a body, so a write sent once is always one-shot.
            "POST" -> builder.post(reqBody ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            "PUT" -> builder.put(reqBody ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            "DELETE" -> if (reqBody != null) builder.delete(reqBody) else builder.delete()
            else -> builder.method(method, reqBody)
        }
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        // The host refuses its ledgered writes (file write/rename/copy, git commit/discard/stash,
        // worktree create) without a command id; the other routes ignore the header.
        if (method != "GET") builder.header("X-Command-Id", UUID.randomUUID().toString())
        val request = builder.build()
        // Off the caller's (main) thread: reading the body blocks.
        return try {
            withContext(Dispatchers.IO) {
                (if (retryOnConnectionFailure) client else noRetryClient).newCall(request).await().use { response ->
                    val body = response.body.string()
                    when {
                        response.code == 401 || response.code == 403 -> ApiResult.Unauthorized
                        !response.isSuccessful -> failure(body, response.code)
                        else -> try {
                            decode(body)
                        } catch (e: SerializationException) {
                            ApiResult.ServerError("HTTP ${response.code}", null, response.code)
                        } catch (e: IllegalArgumentException) {
                            ApiResult.ServerError("HTTP ${response.code}", null, response.code)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            ApiResult.Unreachable(e.javaClass.simpleName)
        }
    }

    /** A non-2xx reply: the envelope's reason when there is one, else the status. */
    private fun failure(body: String, code: Int): ApiResult.ServerError {
        val envelope = runCatching { TroupeJson.decodeFromString(HttpEnvelope.serializer(JsonElement.serializer()), body) }.getOrNull()
        return ApiResult.ServerError(envelope?.error ?: "HTTP $code", envelope?.code, code)
    }

    private fun <T> enveloped(body: String, serializer: KSerializer<T>): ApiResult<T> {
        val envelope = TroupeJson.decodeFromString(HttpEnvelope.serializer(serializer), body)
        val data = envelope.data
        return when {
            envelope.success && data != null -> ApiResult.Ok(data)
            // Caught in execute(), which records the 2xx status: the reply did not match the contract.
            envelope.success -> throw SerializationException("a successful reply without data")
            envelope.code == "UNAUTHORIZED" -> ApiResult.Unauthorized
            else -> ApiResult.ServerError(envelope.error ?: "Unknown error", envelope.code)
        }
    }

    private fun envelopedUnit(body: String): ApiResult<Unit> {
        if (body.isBlank()) return ApiResult.Ok(Unit)
        val envelope = TroupeJson.decodeFromString(HttpEnvelope.serializer(JsonElement.serializer()), body)
        return when {
            envelope.success -> ApiResult.Ok(Unit)
            envelope.code == "UNAUTHORIZED" -> ApiResult.Unauthorized
            else -> ApiResult.ServerError(envelope.error ?: "Unknown error", envelope.code)
        }
    }
}

/** [delegate], marked one-shot: OkHttp never transmits it a second time, neither after a connection failure nor as a follow-up. */
private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

    override fun isOneShot(): Boolean = true
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}
