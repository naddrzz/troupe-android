package dev.troupe.android.worktree

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.pairing.ServerAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WorktreeInfo(
    val name: String = "",
    val path: String,
    val branch: String? = null,
    val headCommit: String? = null,
    val head: String? = null,
    val isMain: Boolean = false,
    val isBare: Boolean = false,
    val isDetached: Boolean = false,
)

@Serializable
data class WorktreeBranchEntry(
    val name: String,
    val isRemote: Boolean = false,
    val isCurrent: Boolean = false,
    val upstream: String? = null,
    val hasOtherWorktree: Boolean = false,
)

@Serializable
data class WorktreeCreateResult(
    val name: String = "",
    val path: String,
    val branch: String,
    val headCommit: String = "",
)

@Singleton
class WorktreeApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun list(address: ServerAddress, token: String?, projectPath: String): ApiResult<List<WorktreeInfo>> {
        val body = buildJsonObject { put("projectPath", projectPath) }.toString()
        return api.postEnveloped(address, "worktree/list", token, body, ListSerializer(WorktreeInfo.serializer()))
    }

    suspend fun branches(address: ServerAddress, token: String?, projectPath: String): ApiResult<List<String>> {
        val query = "worktree/branches?projectPath=${URLEncoder.encode(projectPath, "UTF-8")}"
        return when (val res = api.getEnveloped(address, query, token, ListSerializer(WorktreeBranchEntry.serializer()))) {
            is ApiResult.Ok -> ApiResult.Ok(res.value.map { it.name })
            is ApiResult.ServerError -> res
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> res
        }
    }

    suspend fun create(
        address: ServerAddress,
        token: String?,
        projectPath: String,
        branchName: String,
        baseBranch: String? = null,
        isNewBranch: Boolean = true,
    ): ApiResult<WorktreeCreateResult> {
        val worktreeName = branchName.trim().replace('/', '-').replace(Regex("[^A-Za-z0-9._-]"), "-").ifEmpty { "worktree" }
        val body = buildJsonObject {
            put("projectPath", projectPath)
            put("name", worktreeName)
            put("branch", branchName.trim())
            put("isNewBranch", isNewBranch)
            if (!baseBranch.isNullOrBlank()) put("startRef", baseBranch.trim())
        }.toString()
        return api.postEnveloped(address, "worktree/create", token, body, WorktreeCreateResult.serializer())
    }

    suspend fun remove(
        address: ServerAddress,
        token: String?,
        projectPath: String,
        worktreePath: String,
        force: Boolean = false,
    ): ApiResult<Unit> {
        val body = buildJsonObject {
            put("projectPath", projectPath)
            put("worktreePath", worktreePath)
            put("force", force)
        }.toString()
        return api.postUnit(address, "worktree/remove", token, body)
    }
}
