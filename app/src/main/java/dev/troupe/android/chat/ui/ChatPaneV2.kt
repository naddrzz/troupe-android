package dev.troupe.android.chat.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.chat.ChatItem
import dev.troupe.android.chat.ChatUi
import dev.troupe.android.chat.ChatViewModel
import dev.troupe.android.chat.PendingRequest
import dev.troupe.android.chat.PlanEntry
import dev.troupe.android.chat.composer.ComposerV2
import dev.troupe.android.chat.markdown.MarkdownText
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.net.ViewOnlyRegistry
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.StatusDot
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.workspace.AgentDescriptor

/**
 * Embeddable Agent Chat V2 pane for [dev.troupe.android.shell.MobileWorkspaceShellScreen].
 * Renders rich Markdown messages, collapsible Thinking blocks, ToolCallCards, Plan checklist,
 * ApprovalDock (Permission + AskUserQuestion), and Composer V2.
 */
@Composable
fun EmbeddedChatPane(
    serverId: String,
    sessionId: String,
    agents: List<AgentDescriptor>,
    connections: ChatConnections,
    viewOnlyRegistry: ViewOnlyRegistry,
    onOpenGitChanges: (() -> Unit)? = null,
    onOpenFiles: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(serverId, sessionId) {
        ChatViewModel(
            savedState = SavedStateHandle(mapOf("serverId" to serverId, "sessionId" to sessionId)),
            connections = connections,
            viewOnlyRegistry = viewOnlyRegistry,
        )
    }
    ChatPaneV2Content(
        viewModel = viewModel,
        agents = agents,
        onOpenGitChanges = onOpenGitChanges,
        onOpenFiles = onOpenFiles,
        modifier = modifier,
    )
}

@Composable
fun ChatPaneV2Content(
    viewModel: ChatViewModel,
    agents: List<AgentDescriptor>,
    onOpenGitChanges: (() -> Unit)? = null,
    onOpenFiles: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val listState = rememberLazyListState()

    LaunchedEffect(ui.items.size, ui.requests.size) {
        val total = ui.items.size + ui.requests.size
        if (total > 0) {
            listState.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        // Optional plan progress banner
        if (ui.plan.isNotEmpty()) {
            PlanProgressStrip(entries = ui.plan)
        }

        // Transcript list
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TroupeSpinner(size = 22.dp)
                        Text(
                            text = stringResource(R.string.chat_loading),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                        )
                    }
                }
                ui.error != null && ui.items.isEmpty() -> {
                    val err = ui.error!!
                    val msg = if (err.arg != null) stringResource(err.text, err.arg) else stringResource(err.text)
                    Text(
                        text = msg,
                        style = typo.bodyMedium,
                        color = palette.destructive,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                ui.items.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.chat_empty),
                        style = typo.bodyMedium,
                        color = palette.mutedForeground,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(ui.items, key = { it.key }) { item ->
                            when (item) {
                                is ChatItem.UserPrompt -> UserPromptBubble(item.text)
                                is ChatItem.AgentMessage -> if (item.thought) {
                                    ThinkingBlock(item.text)
                                } else {
                                    MarkdownText(markdown = item.text, modifier = Modifier.fillMaxWidth())
                                }
                                is ChatItem.ToolCall -> ToolCallCard(item)
                                is ChatItem.TurnComplete -> TurnCompleteChip(item.stopReason)
                            }
                        }

                        if (ui.turnRunning) {
                            item(key = "streaming-indicator") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    TroupeSpinner(size = 14.dp, color = palette.brandText)
                                    Text(
                                        text = stringResource(R.string.chat_agent_working),
                                        style = typo.bodySmall,
                                        color = palette.mutedForeground,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating status chip shortcut (e.g., Changes -> Open Git Changes sheet)
        val hasToolEdits = ui.items.any { it is ChatItem.ToolCall && (it.kind == "edit" || it.title.contains("edit", true) || it.title.contains("write", true)) }
        if (hasToolEdits && onOpenGitChanges != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.elevated)
                        .border(0.5.dp, palette.border, CircleShape)
                        .clickable(onClick = onOpenGitChanges)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(TroupeIcons.FileDiff, contentDescription = null, tint = palette.brandText, modifier = Modifier.size(14.dp))
                    Text(
                        text = stringResource(R.string.shell_git_changes),
                        style = typo.labelMedium,
                        color = palette.foreground,
                    )
                }
            }
        }

        // Approval Dock (Permission requests & AskUserQuestion cards pinned above composer)
        if (ui.requests.isNotEmpty()) {
            ApprovalDock(
                requests = ui.requests,
                answering = ui.answering,
                enabled = !viewOnly && connection == ConnectionState.Connected,
                onRespondPermission = viewModel::respond,
                onAnswerQuestion = viewModel::answer,
            )
        }

        // Notice banner (e.g. already answered / busy / view-only)
        ui.notice?.let { notice ->
            val msg = if (notice.arg != null) stringResource(notice.text, notice.arg) else stringResource(notice.text)
            Text(
                text = msg,
                style = typo.bodySmall,
                color = palette.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.warningTint)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // Composer V2
        ComposerV2(
            draft = draft,
            onDraftChange = viewModel::onDraftChange,
            placeholder = if (viewOnly) stringResource(R.string.view_only_badge) else stringResource(R.string.chat_composer_label),
            turnRunning = ui.turnRunning,
            enabled = !viewOnly && connection == ConnectionState.Connected,
            agents = agents,
            selectedAgentId = agents.firstOrNull()?.id,
            onSelectAgent = {},
            onSend = viewModel::send,
            onCancel = viewModel::cancel,
            onOpenFiles = onOpenFiles,
        )
    }
}

@Composable
private fun UserPromptBubble(text: String) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(palette.secondarySurface)
                .border(0.5.dp, palette.border, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = text, style = typo.bodyMedium, color = palette.foreground)
        }
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (expanded) TroupeIcons.ChevronDown else TroupeIcons.ChevronRight,
                contentDescription = null,
                tint = palette.mutedForeground,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.chat_thinking),
                style = typo.labelMedium,
                color = palette.mutedForeground,
            )
        }
        if (expanded) {
            Text(
                text = text,
                style = typo.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = palette.secondary,
            )
        }
    }
}

@Composable
private fun ToolCallCard(tool: ChatItem.ToolCall) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val statusColor = when (tool.status) {
        "completed" -> palette.success
        "failed" -> palette.error
        "in_progress" -> palette.brandText
        else -> palette.mutedForeground
    }
    val statusLabel = when (tool.status) {
        "pending" -> stringResource(R.string.chat_tool_pending)
        "in_progress" -> stringResource(R.string.chat_tool_running)
        "completed" -> stringResource(R.string.chat_tool_completed)
        "failed" -> stringResource(R.string.chat_tool_failed)
        else -> tool.status.orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tool.status == "in_progress") {
            TroupeSpinner(size = 13.dp, color = palette.brandText)
        } else {
            StatusDot(color = statusColor)
        }
        Text(
            text = tool.title.ifBlank { stringResource(R.string.chat_tool_untitled) },
            style = typo.bodySmall,
            color = palette.foreground,
            modifier = Modifier.weight(1f),
        )
        if (statusLabel.isNotBlank()) {
            Text(
                text = statusLabel,
                style = typo.labelSmall,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun TurnCompleteChip(stopReason: String) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val label = if (stopReason == "end_turn") {
        stringResource(R.string.chat_turn_done)
    } else {
        stringResource(R.string.chat_turn_ended, stopReason)
    }
    Text(
        text = label,
        style = typo.labelSmall,
        color = palette.mutedForeground,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun PlanProgressStrip(entries: List<PlanEntry>) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var expanded by remember { mutableStateOf(false) }
    val doneCount = entries.count { it.status == "completed" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.card)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${stringResource(R.string.chat_plan)} ($doneCount/${entries.size})",
                style = typo.labelMedium,
                color = palette.foreground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) TroupeIcons.ChevronDown else TroupeIcons.ChevronRight,
                contentDescription = null,
                tint = palette.mutedForeground,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            entries.forEach { entry ->
                val color = when (entry.status) {
                    "completed" -> palette.success
                    "in_progress" -> palette.brandText
                    else -> palette.mutedForeground
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(color = color, size = 6.dp)
                    Text(text = entry.content, style = typo.bodySmall, color = palette.secondary)
                }
            }
        }
    }
}

@Composable
private fun ApprovalDock(
    requests: List<PendingRequest>,
    answering: Set<String>,
    enabled: Boolean,
    onRespondPermission: (request: PendingRequest.Permission, optionId: String) -> Unit,
    onAnswerQuestion: (question: PendingRequest.Question, values: List<String>) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        requests.forEach { req ->
            val busy = req.id in answering
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.elevated)
                    .border(1.dp, palette.warning.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (req) {
                    is PendingRequest.Permission -> {
                        Text(
                            text = req.title.ifBlank { stringResource(R.string.chat_permission_title) },
                            style = typo.titleSmall,
                            color = palette.foreground,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            req.options.forEach { opt ->
                                val isReject = opt.kind?.startsWith("reject") == true
                                TroupeButton(
                                    text = opt.name,
                                    onClick = { onRespondPermission(req, opt.optionId) },
                                    variant = when {
                                        isReject -> TroupeButtonVariant.Destructive
                                        opt.kind == "allow_always" -> TroupeButtonVariant.Default
                                        else -> TroupeButtonVariant.Secondary
                                    },
                                    enabled = enabled && !busy,
                                    loading = busy,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    is PendingRequest.Question -> {
                        Text(
                            text = req.question.ifBlank { stringResource(R.string.chat_question_title) },
                            style = typo.titleSmall,
                            color = palette.foreground,
                        )
                        var selectedValue by remember(req.id) { mutableStateOf("") }
                        if (req.options.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                req.options.forEach { opt ->
                                    val picked = selectedValue == opt.value
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (picked) palette.pickedFill else palette.card)
                                            .border(
                                                0.5.dp,
                                                if (picked) palette.brandText else palette.border,
                                                RoundedCornerShape(10.dp),
                                            )
                                            .clickable(enabled = enabled && !busy) {
                                                selectedValue = opt.value
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Column {
                                            Text(opt.label, style = typo.labelLarge, color = palette.foreground)
                                            if (!opt.description.isNullOrBlank()) {
                                                Text(opt.description, style = typo.bodySmall, color = palette.mutedForeground)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (req.allowFreeText || req.options.isEmpty()) {
                            OutlinedTextField(
                                value = selectedValue,
                                onValueChange = { selectedValue = it },
                                label = { Text(stringResource(R.string.chat_answer_label)) },
                                singleLine = false,
                                textStyle = typo.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = palette.ring,
                                    unfocusedBorderColor = palette.border,
                                    focusedTextColor = palette.foreground,
                                    unfocusedTextColor = palette.foreground,
                                ),
                            )
                        }
                        TroupeButton(
                            text = stringResource(R.string.chat_answer_send),
                            onClick = { onAnswerQuestion(req, listOf(selectedValue)) },
                            enabled = enabled && !busy && selectedValue.isNotBlank(),
                            loading = busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
