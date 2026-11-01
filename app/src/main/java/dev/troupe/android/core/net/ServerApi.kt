package dev.troupe.android.core.net

import dev.troupe.android.core.protocol.Health
import dev.troupe.android.core.protocol.HttpEnvelope
import dev.troupe.android.core.protocol.ProjectsState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
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

/** The OkHttp client every server call shares. No logging interceptor: headers carry the token. */
fun troupeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

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
        return try {
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                when {
                    response.code == 401 || response.code == 403 -> ApiResult.Unauthorized
                    else -> try {
                        decode(body)
                    } catch (e: SerializationException) {
                        ApiResult.ServerError("HTTP ${response.code}", null)
                    } catch (e: IllegalArgumentException) {
                        ApiResult.ServerError("HTTP ${response.code}", null)
                    }
                }
            }
        } catch (e: IOException) {
            ApiResult.Unreachable(e.javaClass.simpleName)
        }
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
