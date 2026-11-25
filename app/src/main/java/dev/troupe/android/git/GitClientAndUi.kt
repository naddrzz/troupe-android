package dev.troupe.android.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.theme.MonoFamily
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.servers.PairedServer
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GitChangedFile(
    val path: String,
    val status: String = "modified",
    val staged: Boolean = false,
)

@Serializable
data class GitStatusPayload(
    val branch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: List<GitChangedFile> = emptyList(),
    val unstaged: List<GitChangedFile> = emptyList(),
    val untracked: List<GitChangedFile> = emptyList(),
    val files: List<GitChangedFile> = emptyList(),
) {
    fun stagedFiles(): List<GitChangedFile> =
        if (staged.isNotEmpty()) staged else files.filter { it.staged }

    fun unstagedFiles(): List<GitChangedFile> =
        if (unstaged.isNotEmpty() || untracked.isNotEmpty()) unstaged + untracked else files.filterNot { it.staged }
}

@Serializable
data class GitCommitEntry(
    val hash: String,
    val shortHash: String = "",
    val subject: String = "",
    val author: String = "",
    val date: String = "",
)

@Serializable
data class GitBranchInfo(
    val name: String,
    val current: Boolean = false,
    val isRemote: Boolean = false,
)

@Serializable
data class GitCommitContextDto(
    val branch: String? = null,
    val hasUpstream: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val stagedCount: Int = 0,
)

@Singleton
class GitApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun status(address: ServerAddress, token: String?, cwd: String): ApiResult<GitStatusPayload> {
        val body = buildJsonObject { put("cwd", cwd) }.toString()
        val rowsRes = api.postEnveloped(address, "git/status", token, body, ListSerializer(GitChangedFile.serializer()))
        return when (rowsRes) {
            is ApiResult.Ok -> {
                val ctx = (api.postEnveloped(
                    address,
                    "git/commit-context",
                    token,
                    body,
                    GitCommitContextDto.serializer(),
                ) as? ApiResult.Ok)?.value
                ApiResult.Ok(
                    GitStatusPayload(
                        branch = ctx?.branch,
                        ahead = ctx?.ahead ?: 0,
                        behind = ctx?.behind ?: 0,
                        files = rowsRes.value,
                    ),
                )
            }
            is ApiResult.ServerError -> rowsRes
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> rowsRes
        }
    }

    suspend fun diff(address: ServerAddress, token: String?, cwd: String, path: String, staged: Boolean): ApiResult<String> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("path", path)
            put("staged", staged)
        }.toString()
        return when (val res = api.postJsonElement(address, "git/diff", token, body)) {
            is ApiResult.Ok -> ApiResult.Ok(extractDiffString(res.value))
            is ApiResult.ServerError -> res
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> res
        }
    }

    suspend fun stage(address: ServerAddress, token: String?, cwd: String, path: String): ApiResult<Unit> =
        api.postUnit(address, "git/stage", token, buildJsonObject { put("cwd", cwd); put("path", path) }.toString())

    suspend fun unstage(address: ServerAddress, token: String?, cwd: String, path: String): ApiResult<Unit> =
        api.postUnit(address, "git/unstage", token, buildJsonObject { put("cwd", cwd); put("path", path) }.toString())

    suspend fun discard(address: ServerAddress, token: String?, cwd: String, path: String): ApiResult<Unit> =
        api.postUnit(address, "git/discard", token, buildJsonObject { put("cwd", cwd); put("path", path) }.toString())

    suspend fun commit(address: ServerAddress, token: String?, cwd: String, summary: String, description: String = ""): ApiResult<Unit> =
        api.postUnit(
            address,
            "git/commit",
            token,
            buildJsonObject {
                put("cwd", cwd)
                put("summary", summary)
                put("description", description)
            }.toString(),
        )

    suspend fun push(address: ServerAddress, token: String?, cwd: String): ApiResult<Unit> =
        api.postUnit(address, "git/push", token, buildJsonObject { put("cwd", cwd) }.toString())

    suspend fun log(address: ServerAddress, token: String?, cwd: String, limit: Int = 50): ApiResult<List<GitCommitEntry>> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("limit", limit)
        }.toString()
        return api.postEnveloped(address, "git/log", token, body, ListSerializer(GitCommitEntry.serializer()))
    }

    suspend fun branches(address: ServerAddress, token: String?, cwd: String): ApiResult<List<GitBranchInfo>> {
        val q = "git/branch-list?cwd=${URLEncoder.encode(cwd, "UTF-8")}"
        val namesRes = api.getEnveloped(address, q, token, ListSerializer(String.serializer()))
        return when (namesRes) {
            is ApiResult.Ok -> {
                val ctx = (api.postEnveloped(
                    address,
                    "git/commit-context",
                    token,
                    buildJsonObject { put("cwd", cwd) }.toString(),
                    GitCommitContextDto.serializer(),
                ) as? ApiResult.Ok)?.value
                val currentBranch = ctx?.branch
                ApiResult.Ok(
                    namesRes.value.map { raw ->
                        GitBranchInfo(
                            name = raw,
                            current = raw == currentBranch,
                            isRemote = raw.startsWith("origin/") || raw.startsWith("remotes/"),
                        )
                    },
                )
            }
            is ApiResult.ServerError -> namesRes
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> namesRes
        }
    }

    suspend fun switchBranch(address: ServerAddress, token: String?, cwd: String, name: String): ApiResult<Unit> =
        api.postUnit(address, "git/branch-switch", token, buildJsonObject { put("cwd", cwd); put("name", name) }.toString())

    private fun extractDiffString(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonObject -> el["diff"]?.jsonPrimitive?.contentOrNull
            ?: el["patch"]?.jsonPrimitive?.contentOrNull
            ?: el.toString()
        else -> el.toString()
    }
}

/**
 * 1:1 mobile Git Changes bottom sheet (`MobileWorkspaceShell.tsx`):
 * Single-column stacked flow (Status & file list <-> Diff viewer <-> Branches),
 * with stage/unstage/discard, commit summary, and push actions.
 */
@Composable
fun GitChangesSheet(
    server: PairedServer,
    cwd: String,
    viewOnly: Boolean,
    gitApi: GitApi,
    tokenProvider: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<GitStatusPayload?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Drill-down: viewing a specific file diff or branch list
    var activeDiff by remember { mutableStateOf<Triple<String, Boolean, String>?>(null) } // path, staged, diffText
    var showBranches by remember { mutableStateOf(false) }
    var branches by remember { mutableStateOf<List<GitBranchInfo>>(emptyList()) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val token = tokenProvider()
            when (val res = gitApi.status(server.address, token, cwd)) {
                is ApiResult.Ok -> {
                    status = res.value
                    loading = false
                }
                is ApiResult.ServerError -> {
                    error = res.message
                    loading = false
                }
                else -> {
                    error = "Could not load git status."
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(server.id, cwd) { refresh() }

    val sheetTitle = when {
        activeDiff != null -> activeDiff!!.first.substringAfterLast('/')
        showBranches -> stringResource(R.string.git_branches)
        else -> stringResource(R.string.shell_git_changes)
    }

    TroupeMobileSheet(
        title = sheetTitle,
        subtitle = status?.branch?.let { b ->
            val sync = buildString {
                if ((status?.ahead ?: 0) > 0) append(" ↑${status?.ahead}")
                if ((status?.behind ?: 0) > 0) append(" ↓${status?.behind}")
            }
            "$b$sync"
        },
        canPop = activeDiff != null || showBranches,
        onPop = {
            activeDiff = null
            showBranches = false
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                Text(error!!, style = typo.bodySmall, color = palette.destructive)
            }

            when {
                activeDiff != null -> {
                    val (path, staged, diffText) = activeDiff!!
                    if (!viewOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TroupeButton(
                                text = stringResource(if (staged) R.string.git_unstage else R.string.git_stage),
                                variant = TroupeButtonVariant.Secondary,
                                onClick = {
                                    scope.launch {
                                        val token = tokenProvider()
                                        val res = if (staged) {
                                            gitApi.unstage(server.address, token, cwd, path)
                                        } else {
                                            gitApi.stage(server.address, token, cwd, path)
                                        }
                                        when (res) {
                                            is ApiResult.Ok -> {
                                                error = null
                                                activeDiff = null
                                                refresh()
                                            }
                                            is ApiResult.ServerError -> error = res.message
                                            else -> error = "Could not update staging state."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (!staged) {
                                TroupeButton(
                                    text = stringResource(R.string.git_discard),
                                    variant = TroupeButtonVariant.Destructive,
                                    onClick = {
                                        scope.launch {
                                            val token = tokenProvider()
                                            when (val res = gitApi.discard(server.address, token, cwd, path)) {
                                                is ApiResult.Ok -> {
                                                    error = null
                                                    activeDiff = null
                                                    refresh()
                                                }
                                                is ApiResult.ServerError -> error = res.message
                                                else -> error = "Could not discard changes."
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    UnifiedDiffBox(diffText = diffText)
                }

                showBranches -> {
                    SheetGroup {
                        branches.forEachIndexed { idx, branch ->
                            if (idx > 0) SheetRowDivider()
                            SheetRow(
                                title = branch.name,
                                picked = branch.current,
                                leading = {
                                    Icon(TroupeIcons.GitBranch, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    if (!viewOnly && !branch.current) {
                                        scope.launch {
                                            when (val res = gitApi.switchBranch(server.address, tokenProvider(), cwd, branch.name)) {
                                                is ApiResult.Ok -> {
                                                    error = null
                                                    showBranches = false
                                                    refresh()
                                                }
                                                is ApiResult.ServerError -> error = res.message
                                                else -> error = "Could not switch to ${branch.name}."
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    TroupeSpinner(size = 20.dp)
                }

                else -> {
                    val st = status
                    val staged = st?.stagedFiles().orEmpty()
                    val unstaged = st?.unstagedFiles().orEmpty()

                    // Branch switcher & Push bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TroupeButton(
                            text = st?.branch ?: stringResource(R.string.git_branches),
                            icon = TroupeIcons.GitBranch,
                            variant = TroupeButtonVariant.Outline,
                            onClick = {
                                showBranches = true
                                scope.launch {
                                    val res = gitApi.branches(server.address, tokenProvider(), cwd)
                                    if (res is ApiResult.Ok) branches = res.value
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (!viewOnly) {
                            TroupeButton(
                                text = stringResource(R.string.git_push),
                                variant = TroupeButtonVariant.Secondary,
                                loading = busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        when (val r = gitApi.push(server.address, tokenProvider(), cwd)) {
                                            is ApiResult.ServerError -> error = r.message
                                            else -> refresh()
                                        }
                                        busy = false
                                    }
                                },
                            )
                        }
                    }

                    if (staged.isEmpty() && unstaged.isEmpty()) {
                        Text(
                            text = stringResource(R.string.git_clean_working_tree),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        if (staged.isNotEmpty()) {
                            Text(
                                text = "${stringResource(R.string.git_staged)} (${staged.size})",
                                style = typo.labelMedium,
                                color = palette.mutedForeground,
                            )
                            SheetGroup {
                                staged.forEachIndexed { idx, file ->
                                    if (idx > 0) SheetRowDivider()
                                    SheetRow(
                                        title = file.path.substringAfterLast('/'),
                                        subtitle = file.path,
                                        trailing = {
                                            Text(
                                                text = file.status.take(1).uppercase(),
                                                style = typo.labelSmall,
                                                color = palette.diffAddedForeground,
                                            )
                                        },
                                        onClick = {
                                            scope.launch {
                                                val res = gitApi.diff(server.address, tokenProvider(), cwd, file.path, staged = true)
                                                if (res is ApiResult.Ok) activeDiff = Triple(file.path, true, res.value)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (unstaged.isNotEmpty()) {
                            Text(
                                text = "${stringResource(R.string.git_changes)} (${unstaged.size})",
                                style = typo.labelMedium,
                                color = palette.mutedForeground,
                            )
                            SheetGroup {
                                unstaged.forEachIndexed { idx, file ->
                                    if (idx > 0) SheetRowDivider()
                                    SheetRow(
                                        title = file.path.substringAfterLast('/'),
                                        subtitle = file.path,
                                        trailing = {
                                            Text(
                                                text = file.status.take(1).uppercase(),
                                                style = typo.labelSmall,
                                                color = palette.diffModified,
                                            )
                                        },
                                        onClick = {
                                            scope.launch {
                                                val res = gitApi.diff(server.address, tokenProvider(), cwd, file.path, staged = false)
                                                if (res is ApiResult.Ok) activeDiff = Triple(file.path, false, res.value)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (!viewOnly && staged.isNotEmpty()) {
                            OutlinedTextField(
                                value = commitMessage,
                                onValueChange = { commitMessage = it },
                                label = { Text(stringResource(R.string.git_commit_summary)) },
                                singleLine = true,
                                textStyle = typo.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = palette.ring,
                                    unfocusedBorderColor = palette.border,
                                    focusedTextColor = palette.foreground,
                                    unfocusedTextColor = palette.foreground,
                                ),
                            )
                            TroupeButton(
                                text = stringResource(R.string.git_commit_action),
                                enabled = commitMessage.isNotBlank(),
                                loading = busy,
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        when (val r = gitApi.commit(server.address, tokenProvider(), cwd, commitMessage.trim())) {
                                            is ApiResult.Ok -> {
                                                commitMessage = ""
                                                refresh()
                                            }
                                            is ApiResult.ServerError -> error = r.message
                                            else -> error = "Commit failed."
                                        }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedDiffBox(diffText: String) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val lines = remember(diffText) { diffText.ifBlank { "(empty diff)" }.lines() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        lines.forEach { line ->
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> palette.diffAdded.copy(alpha = 0.28f)
                line.startsWith("-") && !line.startsWith("---") -> palette.error.copy(alpha = 0.22f)
                line.startsWith("@@") -> palette.brand.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            val fg = when {
                line.startsWith("+") && !line.startsWith("+++") -> palette.diffAddedForeground
                line.startsWith("-") && !line.startsWith("---") -> palette.error
                line.startsWith("@@") -> palette.brandText
                else -> palette.foreground
            }
            Text(
                text = line,
                style = typo.bodySmall.copy(fontFamily = MonoFamily, lineHeight = 18.sp),
                color = fg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
    }
}

/** Git History tab pane (`WorkspaceTab.GitHistory`). */
@Composable
fun GitHistoryPane(
    server: PairedServer,
    cwd: String,
    gitApi: GitApi,
    tokenProvider: suspend () -> String?,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var commits by remember(cwd) { mutableStateOf<List<GitCommitEntry>>(emptyList()) }
    var loading by remember(cwd) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.id, cwd) {
        loading = true
        when (val res = gitApi.log(server.address, tokenProvider(), cwd)) {
            is ApiResult.Ok -> {
                commits = res.value
                loading = false
            }
            is ApiResult.ServerError -> {
                error = res.message
                loading = false
            }
            else -> {
                error = "Could not load git history."
                loading = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(palette.background)) {
        when {
            loading -> TroupeSpinner(size = 22.dp, modifier = Modifier.align(Alignment.Center))
            error != null -> Text(error!!, style = typo.bodyMedium, color = palette.destructive, modifier = Modifier.align(Alignment.Center).padding(20.dp))
            commits.isEmpty() -> Text(stringResource(R.string.git_history_empty), style = typo.bodyMedium, color = palette.mutedForeground, modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(commits, key = { it.hash }) { commit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.card)
                            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = commit.subject.ifBlank { commit.hash.take(8) },
                            style = typo.titleSmall,
                            color = palette.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = commit.author,
                                style = typo.bodySmall,
                                color = palette.mutedForeground,
                            )
                            Text(
                                text = commit.shortHash.ifBlank { commit.hash.take(7) },
                                style = typo.labelSmall.copy(fontFamily = MonoFamily),
                                color = palette.brandText,
                            )
                        }
                    }
                }
            }
        }
    }
}
