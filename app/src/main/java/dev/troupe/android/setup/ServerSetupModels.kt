package dev.troupe.android.setup

import kotlinx.serialization.Serializable

/**
 * Wire models for Troupe Server web onboarding (setup endpoints) and reconfigure (`/server/config`).
 * Matches `src/shared/types/server-setup.types.ts` 1:1.
 */
@Serializable
data class ServerSetupAnswers(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val sessionsDir: String = "",
    val projectsFile: String = "",
    val allowRemoteWrites: Boolean = true,
    val updateChannel: String? = "stable",
    val updateIntervalSecs: Int = 21600,
)

@Serializable
data class SetupStatusResponse(
    val setupRequired: Boolean = false,
    val configError: String? = null,
    val defaults: ServerSetupAnswers = ServerSetupAnswers(),
)

@Serializable
data class SetupFieldErrors(
    val host: String? = null,
    val port: String? = null,
    val sessionsDir: String? = null,
    val projectsFile: String? = null,
    val allowRemoteWrites: String? = null,
    val updateChannel: String? = null,
    val updateIntervalSecs: String? = null,
) {
    fun hasAny(): Boolean =
        host != null || port != null || sessionsDir != null || projectsFile != null ||
            allowRemoteWrites != null || updateChannel != null || updateIntervalSecs != null
}

@Serializable
data class SetupValidateResponse(
    val ok: Boolean = false,
    val fieldErrors: SetupFieldErrors = SetupFieldErrors(),
)

/**
 * Unified reply for `POST /setup/apply` and `POST /server/config`.
 * When `ok == true`, `host`, `port`, and optional `webAuthToken` are populated.
 * When `ok == false`, `fieldErrors` describes per-field validation errors.
 */
@Serializable
data class SetupApplyReply(
    val ok: Boolean = false,
    val host: String? = null,
    val port: Int? = null,
    val webAuthToken: String? = null,
    val fieldErrors: SetupFieldErrors = SetupFieldErrors(),
) {
    override fun toString(): String =
        "SetupApplyReply(ok=$ok, host=$host, port=$port, hasWebAuthToken=${!webAuthToken.isNullOrEmpty()}, fieldErrors=$fieldErrors)"
}

@Serializable
data class ServerConfigResponse(
    val config: ServerSetupAnswers = ServerSetupAnswers(),
)
