package dev.troupe.android.workspace

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.protocol.Project
import dev.troupe.android.core.protocol.ProjectsState
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DirectoryEntry(
    val name: String,
    val path: String,
    val type: String = "file",
    val extension: String? = null,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val ignored: Boolean? = null,
) {
    val isDirectory: Boolean get() = type == "directory" || type == "dir"
}

@Serializable
data class BrowseDirectoryResponse(
    val currentPath: String = "",
    val parentPath: String? = null,
    val entries: List<DirectoryEntry> = emptyList(),
)

/**
 * HTTP API for Troupe Server project registry mutations (`/projects*`) and host directory browsing
 * (`/fs/browse`) used when adding a Project from Android.
 */
@Singleton
class ProjectApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun list(address: ServerAddress, token: String?): ApiResult<ProjectsState> =
        api.projects(address, token)

    suspend fun createProject(
        address: ServerAddress,
        token: String?,
        name: String,
        path: String,
        color: String = "blue",
    ): ApiResult<Project> {
        val body = buildJsonObject {
            put("id", "proj-${UUID.randomUUID().toString().take(8)}")
            put("name", name)
            put("path", path)
            put("color", color)
            put("isArchived", false)
        }.toString()
        return api.postEnveloped(address, "projects", token, body, Project.serializer())
    }

    suspend fun setDefaultProject(
        address: ServerAddress,
        token: String?,
        projectId: String,
    ): ApiResult<Unit> {
        val body = buildJsonObject { put("projectId", projectId) }.toString()
        return api.postUnit(address, "projects/default", token, body)
    }

    suspend fun updateProject(
        address: ServerAddress,
        token: String?,
        projectId: String,
        name: String? = null,
        color: String? = null,
        isArchived: Boolean? = null,
    ): ApiResult<Unit> {
        val body = buildJsonObject {
            if (name != null) put("name", name)
            if (color != null) put("color", color)
            if (isArchived != null) put("isArchived", isArchived)
        }.toString()
        return api.putUnit(address, "projects/$projectId", token, body)
    }

    suspend fun removeProject(
        address: ServerAddress,
        token: String?,
        projectId: String,
        cascade: Boolean = false,
    ): ApiResult<Unit> {
        val path = if (cascade) "projects/$projectId?cascade=true" else "projects/$projectId"
        return api.deleteUnit(address, path, token)
    }

    suspend fun browseDirectory(
        address: ServerAddress,
        token: String?,
        path: String? = null,
    ): ApiResult<BrowseDirectoryResponse> {
        val targetPath = path?.takeIf { it.isNotBlank() } ?: "/"
        val query = "fs/browse?path=${URLEncoder.encode(targetPath, "UTF-8")}"
        return when (val res = api.getEnveloped(address, query, token, ListSerializer(DirectoryEntry.serializer()))) {
            is ApiResult.Ok -> {
                val trimmed = targetPath.trimEnd('/').ifEmpty { "/" }
                val parent = if (trimmed == "/") null else trimmed.substringBeforeLast('/', "").ifEmpty { "/" }
                ApiResult.Ok(
                    BrowseDirectoryResponse(
                        currentPath = trimmed,
                        parentPath = parent,
                        entries = res.value,
                    ),
                )
            }
            is ApiResult.ServerError -> res
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> res
        }
    }

    suspend fun listDirectory(
        address: ServerAddress,
        token: String?,
        path: String,
    ): ApiResult<List<DirectoryEntry>> {
        val query = "fs/ls?path=${URLEncoder.encode(path, "UTF-8")}"
        return api.getEnveloped(address, query, token, ListSerializer(DirectoryEntry.serializer()))
    }
}
