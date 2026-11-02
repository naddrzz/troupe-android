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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class Unreachable(val cause: String) : ApiResult<Nothing>
    data class ServerError(val message: String, val code: String?) : ApiResult<Nothing>
}

/**
 * The OkHttp client every server call and socket shares. No logging interceptor: headers carry the token.
 * [CleartextGuard] runs before the first request (so nothing connects) and again on every redirect.
 */
fun troupeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
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

/**
 * The Troupe Server's HTTP routes. The token travels only in `Authorization: Bearer`; no
 * `Origin` header is sent (the server checks Origin only when present).
 */
class ServerApi @Inject constructor(private val client: OkHttpClient) {

    suspend fun health(address: ServerAddress): ApiResult<Health> =
        get(address, "health", token = null) { body -> ApiResult.Ok(TroupeJson.decodeFromString(Health.serializer(), body)) }

    suspend fun projects(address: ServerAddress, token: String): ApiResult<ProjectsState> =
        get(address, "projects", token) { body -> enveloped(body, ProjectsState.serializer()) }

    private suspend fun <T> get(
        address: ServerAddress,
        path: String,
        token: String?,
        decode: (String) -> ApiResult<T>,
    ): ApiResult<T> {
        val request = Request.Builder().url(address.httpUrl(path)).get()
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        // Off the caller's (main) thread: reading the body blocks.
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).await().use { response ->
                    val body = response.body.string()
                    when {
                        response.code == 401 || response.code == 403 -> ApiResult.Unauthorized
                        !response.isSuccessful -> failure(body, response.code)
                        else -> try {
                            decode(body)
                        } catch (e: SerializationException) {
                            ApiResult.ServerError("HTTP ${response.code}", null)
                        } catch (e: IllegalArgumentException) {
                            ApiResult.ServerError("HTTP ${response.code}", null)
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
        return ApiResult.ServerError(envelope?.error ?: "HTTP $code", envelope?.code)
    }

    private fun <T> enveloped(body: String, serializer: KSerializer<T>): ApiResult<T> {
        val envelope = TroupeJson.decodeFromString(HttpEnvelope.serializer(serializer), body)
        val data = envelope.data
        return when {
            envelope.success && data != null -> ApiResult.Ok(data)
            envelope.code == "UNAUTHORIZED" -> ApiResult.Unauthorized
            else -> ApiResult.ServerError(envelope.error ?: "Unknown error", envelope.code)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}
