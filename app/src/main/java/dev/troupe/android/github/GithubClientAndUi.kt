package dev.troupe.android.github

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.chat.markdown.MarkdownText
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.StatusDot
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.servers.PairedServer
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GithubLabelDto(
    val name: String,
    val color: String = "6b7280",
)

@Serializable
data class GithubListItemDto(
    val kind: String = "issue",
    val number: Int,
    val title: String,
    val state: String = "open",
    val author: String = "",
    val labels: List<GithubLabelDto> = emptyList(),
    val commentCount: Int = 0,
    val updatedAt: String = "",
    val url: String = "",
    val checks: String? = null,
)

@Serializable
data class GithubCommentDto(
    val id: String = "",
    val author: String = "",
    val body: String = "",
    val createdAt: String = "",
)

@Serializable
data class GithubListResultDto(
    val items: List<GithubListItemDto> = emptyList(),
    val fetchedAt: String = "",
)

@Serializable
data class GithubItemDetailDto(
    val item: GithubListItemDto,
    val body: String = "",
    val createdAt: String = "",
    val comments: List<GithubCommentDto> = emptyList(),
    val fetchedAt: String = "",
) {
    val kind: String get() = item.kind
    val number: Int get() = item.number
    val title: String get() = item.title
    val state: String get() = item.state
    val author: String get() = item.author
    val labels: List<GithubLabelDto> get() = item.labels
    val url: String get() = item.url
}

@Singleton
class GithubApi @Inject constructor(
    private val api: ServerApi,
) {
    suspend fun listItems(
        address: ServerAddress,
        token: String?,
        cwd: String,
        kind: String = "all",
        state: String = "open",
    ): ApiResult<List<GithubListItemDto>> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put(
                "filter",
                buildJsonObject {
                    put("repo", "origin")
                    put("kind", kind)
                    put("state", state)
                    put("search", "")
                    put("labels", kotlinx.serialization.json.buildJsonArray {})
                    put("labelMode", "any")
                    put("assignee", buildJsonObject { put("kind", "anyone") })
                    put("reviewRequested", false)
                    put("sort", "updated")
                },
            )
        }.toString()
        return when (val res = api.postEnveloped(address, "github/items", token, body, GithubListResultDto.serializer())) {
            is ApiResult.Ok -> ApiResult.Ok(res.value.items)
            is ApiResult.ServerError -> res
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Unreachable -> res
        }
    }

    suspend fun getItem(
        address: ServerAddress,
        token: String?,
        cwd: String,
        repo: String,
        kind: String,
        number: Int,
    ): ApiResult<GithubItemDetailDto> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("repo", repo)
            put("kind", kind)
            put("number", number)
        }.toString()
        return api.postEnveloped(address, "github/item", token, body, GithubItemDetailDto.serializer())
    }

    suspend fun comment(
        address: ServerAddress,
        token: String?,
        cwd: String,
        repo: String,
        kind: String,
        number: Int,
        bodyText: String,
    ): ApiResult<Unit> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("repo", repo)
            put("kind", kind)
            put("number", number)
            put("body", bodyText)
        }.toString()
        return api.postUnit(address, "github/comment", token, body)
    }

    suspend fun mergePr(
        address: ServerAddress,
        token: String?,
        cwd: String,
        repo: String,
        number: Int,
    ): ApiResult<Unit> {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("repo", repo)
            put("number", number)
            put("method", "squash")
        }.toString()
        return api.postUnit(address, "github/merge", token, body)
    }
}

/** Embedded GitHub Issues & PRs list inside the Drawer's GitHub section. */
@Composable
fun MobileGithubDrawerSection(
    server: PairedServer?,
    cwd: String?,
    githubApi: GithubApi,
    tokenProvider: suspend () -> String?,
    onOpenItem: (GithubListItemDto) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var kindFilter by remember { mutableStateOf("all") }
    var items by remember { mutableStateOf<List<GithubListItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    if (server == null || cwd.isNullOrBlank()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.github_no_project), style = typo.bodyMedium, color = palette.mutedForeground)
        }
        return
    }

    LaunchedEffect(server.id, cwd, kindFilter) {
        loading = true
        error = null
        when (val res = githubApi.listItems(server.address, tokenProvider(), cwd, kind = kindFilter)) {
            is ApiResult.Ok -> {
                items = res.value
                loading = false
            }
            is ApiResult.ServerError -> {
                error = res.message
                loading = false
            }
            else -> {
                error = "Could not load GitHub items."
                loading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "all" to stringResource(R.string.github_filter_all),
                "issue" to stringResource(R.string.github_filter_issues),
                "pr" to stringResource(R.string.github_filter_prs),
            ).forEach { (id, label) ->
                val selected = kindFilter == id
                Text(
                    text = label,
                    style = typo.labelMedium,
                    color = if (selected) palette.foreground else palette.mutedForeground,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) palette.secondarySurface else palette.card)
                        .clickable { kindFilter = id }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> TroupeSpinner(size = 20.dp, modifier = Modifier.align(Alignment.Center))
                error != null -> Text(error!!, style = typo.bodySmall, color = palette.mutedForeground, modifier = Modifier.align(Alignment.Center).padding(20.dp))
                items.isEmpty() -> Text(stringResource(R.string.github_empty), style = typo.bodyMedium, color = palette.mutedForeground, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { "${it.kind}-${it.number}" }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenItem(item) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusDot(
                                color = when (item.state) {
                                    "open" -> palette.success
                                    "merged" -> palette.brandText
                                    else -> palette.mutedForeground
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${item.number} ${item.title}",
                                    style = typo.bodyMedium,
                                    color = palette.foreground,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.kind.uppercase()} · ${item.author}",
                                    style = typo.labelSmall,
                                    color = palette.mutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** GitHub Issue / PR detail pane (`WorkspaceTab.GithubItem`). */
@Composable
fun GithubItemPane(
    server: PairedServer,
    cwd: String,
    repo: String,
    kind: String,
    number: Int,
    viewOnly: Boolean,
    githubApi: GithubApi,
    tokenProvider: suspend () -> String?,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var detail by remember(number) { mutableStateOf<GithubItemDetailDto?>(null) }
    var loading by remember(number) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var commentText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        scope.launch {
            when (val res = githubApi.getItem(server.address, tokenProvider(), cwd, repo, kind, number)) {
                is ApiResult.Ok -> {
                    detail = res.value
                    loading = false
                }
                is ApiResult.ServerError -> {
                    error = res.message
                    loading = false
                }
                else -> {
                    error = "Could not load item #$number."
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(server.id, cwd, number) { load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TroupeSpinner(size = 22.dp)
            }
            error != null -> Text(error!!, style = typo.bodyMedium, color = palette.destructive, modifier = Modifier.padding(20.dp))
            detail != null -> {
                val item = detail!!
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "#${item.number} ${item.title}", style = typo.titleLarge, color = palette.foreground)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.state.uppercase(),
                            style = typo.labelSmall,
                            color = palette.foreground,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.secondarySurface)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Text(text = item.author, style = typo.bodySmall, color = palette.mutedForeground)
                    }
                    if (item.kind == "pr" && item.state == "open" && !viewOnly) {
                        TroupeButton(
                            text = stringResource(R.string.github_merge_pr),
                            variant = TroupeButtonVariant.Secondary,
                            onClick = {
                                scope.launch {
                                    githubApi.mergePr(server.address, tokenProvider(), cwd, repo, number)
                                    load()
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                    if (item.body.isNotBlank()) {
                        MarkdownText(markdown = item.body)
                    }
                    if (item.comments.isNotEmpty()) {
                        HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                        Text(
                            text = stringResource(R.string.github_comments, item.comments.size),
                            style = typo.titleSmall,
                            color = palette.foreground,
                        )
                        item.comments.forEach { comment ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.card)
                                    .border(0.5.dp, palette.border, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(text = comment.author, style = typo.labelMedium, color = palette.brandText)
                                MarkdownText(markdown = comment.body)
                            }
                        }
                    }
                }

                if (!viewOnly) {
                    HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            placeholder = { Text(stringResource(R.string.github_add_comment)) },
                            singleLine = false,
                            maxLines = 3,
                            textStyle = typo.bodyLarge,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = palette.ring,
                                unfocusedBorderColor = palette.border,
                                focusedTextColor = palette.foreground,
                                unfocusedTextColor = palette.foreground,
                            ),
                        )
                        TroupeButton(
                            text = stringResource(R.string.chat_send),
                            enabled = commentText.isNotBlank(),
                            loading = sending,
                            onClick = {
                                sending = true
                                scope.launch {
                                    githubApi.comment(server.address, tokenProvider(), cwd, repo, kind, number, commentText.trim())
                                    commentText = ""
                                    sending = false
                                    load()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
