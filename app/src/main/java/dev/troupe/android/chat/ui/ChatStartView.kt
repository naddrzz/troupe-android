package dev.troupe.android.chat.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.chat.composer.ComposerV2
import dev.troupe.android.chat.net.ChatConnection
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.protocol.Project
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMark
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.workspace.AgentDescriptor
import dev.troupe.android.worktree.WorktreeApi
import dev.troupe.android.worktree.WorktreeInfo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.UUID

/**
 * 1:1 port of Troupe's `chat-start.tsx`:
 * Centered 56dp `TroupeMark` + `"What should we do in <project>?"` (22/28 display hero),
 * worktree isolation selector strip (Local / existing worktrees / + New worktree),
 * and pinned `ComposerV2` at the bottom that creates a live ACP session on `/ws`.
 */
@Composable
fun ChatStartView(
    server: PairedServer?,
    project: Project?,
    activeWorktreePath: String?,
    agents: List<AgentDescriptor>,
    viewOnly: Boolean,
    connection: ChatConnection?,
    worktreeApi: WorktreeApi,
    tokenProvider: suspend () -> String?,
    onSelectWorktree: (projectId: String, path: String) -> Unit,
    onSessionCreated: (sessionId: String, title: String) -> Unit,
    onOpenFiles: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var draft by remember(project?.id) { mutableStateOf("") }
    var selectedAgentId by remember(agents) { mutableStateOf(agents.firstOrNull()?.id) }
    var worktrees by remember(project?.id) { mutableStateOf<List<WorktreeInfo>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var inFlightSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNewWorktreeSheet by remember { mutableStateOf(false) }

    LaunchedEffect(server?.id, project?.path) {
        val srv = server ?: return@LaunchedEffect
        val path = project?.path ?: return@LaunchedEffect
        val token = tokenProvider()
        val res = worktreeApi.list(srv.address, token, path)
        if (res is ApiResult.Ok) {
            worktrees = res.value
        }
    }

    fun cancelLaunch() {
        launchJob?.cancel()
        launchJob = null
        creating = false
        val active = inFlightSession
        inFlightSession = null
        val conn = connection
        if (active != null && conn != null) {
            scope.launch {
                runCatching {
                    conn.socket.request(
                        type = "cancel_prompt",
                        payload = buildJsonObject {
                            put("agentId", active.second)
                            put("sessionId", active.first)
                        },
                        commandId = null,
                    )
                }
            }
        }
    }

    fun launchNewChat() {
        val conn = connection ?: return
        val proj = project ?: return
        val prompt = draft.trim()
        if (prompt.isEmpty() || creating) return
        val agentId = selectedAgentId ?: agents.firstOrNull()?.id ?: "claude"
        val cwd = activeWorktreePath ?: proj.path.orEmpty()
        creating = true
        error = null
        inFlightSession = null
        launchJob?.cancel()
        launchJob = scope.launch {
            try {
                val createReply = conn.socket.request(
                    type = "create_session",
                    payload = buildJsonObject {
                        put("agentId", agentId)
                        put("cwd", cwd)
                        put("projectId", proj.id)
                    },
                    commandId = UUID.randomUUID().toString(),
                )
                if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) return@launch
                if (!createReply.ok) {
                    error = createReply.message ?: "Failed to start chat."
                    creating = false
                    return@launch
                }
                val sessionId = (createReply.payload as? JsonObject)
                    ?.get("sessionId")?.jsonPrimitive?.contentOrNull
                if (sessionId.isNullOrBlank()) {
                    error = "Server returned no session ID."
                    creating = false
                    return@launch
                }
                inFlightSession = sessionId to agentId
                val turnId = UUID.randomUUID().toString()
                // Dispatch without waiting for turn completion (which may exceed the 15 s RPC timeout);
                // ChatPaneV2 streams the live turn and surfaces any server errors.
                conn.socket.dispatch(
                    type = "send_prompt",
                    payload = buildJsonObject {
                        put("agentId", agentId)
                        put("sessionId", sessionId)
                        put("turnId", turnId)
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            }
                        }
                    },
                    commandId = turnId,
                )
                if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) return@launch
                draft = ""
                creating = false
                launchJob = null
                inFlightSession = null
                onSessionCreated(sessionId, prompt.take(48))
            } catch (_: kotlinx.coroutines.CancellationException) {
                creating = false
                launchJob = null
            } catch (e: IOException) {
                error = e.message ?: "Could not reach server."
                creating = false
                launchJob = null
                inFlightSession = null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        // Centered hero region (56dp TroupeMark + 20dp gap + 22/28 display question)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TroupeMark(
                    size = 56.dp,
                    tint = palette.secondary,
                )
                Text(
                    text = if (project != null) {
                        stringResource(R.string.chat_start_question_project, project.name)
                    } else {
                        stringResource(R.string.chat_start_question)
                    },
                    style = typo.displayLarge,
                    color = palette.foreground,
                    textAlign = TextAlign.Center,
                )
                if (viewOnly) {
                    Text(
                        text = stringResource(R.string.chat_view_only),
                        style = typo.bodySmall,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.Center,
                    )
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        style = typo.bodySmall,
                        color = palette.destructive,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Worktree isolation selector strip above the composer
        if (project?.path != null && !viewOnly) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val mainPath = project.path
                WorktreeChip(
                    label = stringResource(R.string.worktree_local),
                    selected = activeWorktreePath == null || activeWorktreePath == mainPath,
                    onClick = { onSelectWorktree(project.id, mainPath) },
                )
                worktrees.filterNot { it.isMain || it.path == mainPath }.forEach { wt ->
                    WorktreeChip(
                        label = wt.branch ?: wt.path.substringAfterLast('/'),
                        selected = activeWorktreePath == wt.path,
                        onClick = { onSelectWorktree(project.id, wt.path) },
                    )
                }
                WorktreeChip(
                    label = stringResource(R.string.worktree_new),
                    selected = false,
                    onClick = { showNewWorktreeSheet = true },
                )
            }
        }

        // Pinned Composer V2
        ComposerV2(
            draft = draft,
            onDraftChange = { draft = it },
            placeholder = stringResource(R.string.chat_composer_label),
            turnRunning = creating,
            enabled = !viewOnly && project != null && connection != null,
            agents = agents,
            selectedAgentId = selectedAgentId,
            onSelectAgent = { selectedAgentId = it },
            onSend = { launchNewChat() },
            onCancel = { cancelLaunch() },
            onOpenFiles = onOpenFiles,
        )
    }

    if (showNewWorktreeSheet && server != null && project?.path != null) {
        CreateWorktreeSheet(
            server = server,
            project = project,
            worktreeApi = worktreeApi,
            tokenProvider = tokenProvider,
            onDismiss = { showNewWorktreeSheet = false },
            onCreated = { created ->
                showNewWorktreeSheet = false
                worktrees = worktrees + WorktreeInfo(path = created.path, branch = created.branch)
                onSelectWorktree(project.id, created.path)
            },
        )
    }
}

@Composable
private fun WorktreeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) palette.secondarySurface else palette.card)
            .border(0.5.dp, if (selected) palette.brandText.copy(alpha = 0.5f) else palette.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = TroupeIcons.GitBranch,
            contentDescription = null,
            tint = if (selected) palette.brandText else palette.mutedForeground,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = typo.labelMedium,
            color = if (selected) palette.foreground else palette.mutedForeground,
        )
    }
}

@Composable
private fun CreateWorktreeSheet(
    server: PairedServer,
    project: Project,
    worktreeApi: WorktreeApi,
    tokenProvider: suspend () -> String?,
    onDismiss: () -> Unit,
    onCreated: (dev.troupe.android.worktree.WorktreeCreateResult) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf("") }
    var baseBranch by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    TroupeMobileSheet(
        title = stringResource(R.string.worktree_create_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text(stringResource(R.string.worktree_branch_label)) },
                placeholder = { Text("feat/my-task") },
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
            OutlinedTextField(
                value = baseBranch,
                onValueChange = { baseBranch = it },
                label = { Text(stringResource(R.string.worktree_base_branch_label)) },
                placeholder = { Text("main") },
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
            if (err != null) {
                Text(text = err!!, style = typo.bodySmall, color = palette.destructive)
            }
            TroupeButton(
                text = stringResource(R.string.worktree_create_action),
                onClick = {
                    val path = project.path ?: return@TroupeButton
                    submitting = true
                    err = null
                    scope.launch {
                        val token = tokenProvider()
                        when (val res = worktreeApi.create(server.address, token, path, branch.trim(), baseBranch.trim().ifEmpty { null })) {
                            is ApiResult.Ok -> onCreated(res.value)
                            is ApiResult.ServerError -> {
                                err = res.message
                                submitting = false
                            }
                            else -> {
                                err = "Failed to create worktree."
                                submitting = false
                            }
                        }
                    }
                },
                enabled = branch.isNotBlank(),
                loading = submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
