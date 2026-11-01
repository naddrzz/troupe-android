package dev.troupe.android.core.protocol

import kotlinx.serialization.Serializable

/** `GET /health`: answered bare (no envelope) and without a token. */
@Serializable
data class Health(
    val status: String,
    val allowRemoteWrites: Boolean = false,
    val setupRequired: Boolean = false,
)

/** termul's `ProjectSummary`. `color` is one of termul's named colors (blue, purple, …). */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val color: String? = null,
    val path: String? = null,
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
)

/** `GET /projects` data: termul's `ProjectListPayload`. */
@Serializable
data class ProjectsState(val projects: List<Project>, val defaultProjectId: String? = null)

/** The `{success, data}` / `{success: false, error, code}` envelope of the server's HTTP replies. */
@Serializable
data class HttpEnvelope<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val code: String? = null,
)
