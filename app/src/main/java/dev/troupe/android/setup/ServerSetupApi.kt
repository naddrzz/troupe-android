package dev.troupe.android.setup

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
private val SetupRequestJson = Json(TroupeJson) {
    explicitNulls = true
    encodeDefaults = true
}

/**
 * HTTP client for Troupe Server's first-run setup wizard (the setup endpoints, authenticated with
 * the one-time `?setup=<token>` bearer token) and post-setup server reconfigure (`/server/config`,
 * authenticated with the normal web auth bearer token).
 */
@Singleton
class ServerSetupApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun getStatus(address: ServerAddress, setupToken: String?): ApiResult<SetupStatusResponse> =
        api.getEnveloped(address, "setup/status", setupToken, SetupStatusResponse.serializer())

    suspend fun validate(
        address: ServerAddress,
        setupToken: String?,
        answers: ServerSetupAnswers,
    ): ApiResult<SetupValidateResponse> = api.postEnveloped(
        address = address,
        path = "setup/validate",
        token = setupToken,
        bodyJson = SetupRequestJson.encodeToString(ServerSetupAnswers.serializer(), answers),
        serializer = SetupValidateResponse.serializer(),
    )

    suspend fun apply(
        address: ServerAddress,
        setupToken: String?,
        answers: ServerSetupAnswers,
    ): ApiResult<SetupApplyReply> = api.postEnveloped(
        address = address,
        path = "setup/apply",
        token = setupToken,
        bodyJson = SetupRequestJson.encodeToString(ServerSetupAnswers.serializer(), answers),
        serializer = SetupApplyReply.serializer(),
    )

    suspend fun getServerConfig(address: ServerAddress, authToken: String?): ApiResult<ServerConfigResponse> =
        api.getEnveloped(address, "server/config", authToken, ServerConfigResponse.serializer())

    suspend fun applyServerConfig(
        address: ServerAddress,
        authToken: String?,
        answers: ServerSetupAnswers,
    ): ApiResult<SetupApplyReply> = api.postEnveloped(
        address = address,
        path = "server/config",
        token = authToken,
        bodyJson = SetupRequestJson.encodeToString(ServerSetupAnswers.serializer(), answers),
        serializer = SetupApplyReply.serializer(),
    )
}
