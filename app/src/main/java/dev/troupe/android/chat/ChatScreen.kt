package dev.troupe.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar

/** An Agent Chat, read-only: the transcript as it streams, the agent's plan, and the connection in the header. */
@Composable
fun ChatScreen(title: String?, onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val palette = LocalTroupePalette.current
    Scaffold(topBar = {
        TroupeTopBar(title ?: stringResource(R.string.chat_title), onBack = onBack, connection = connection, onRetry = viewModel::retryConnection)
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(
                    stringResource(it.text, it.arg.orEmpty()),
                    color = palette.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (state.plan.isNotEmpty()) {
                Plan(state.plan)
                HorizontalDivider(color = palette.border)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty() && state.error == null) {
                    Text(
                        stringResource(if (state.loading) R.string.chat_loading else R.string.chat_empty),
                        color = palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                Transcript(state.items)
            }
        }
    }
}

/** The transcript, lazily laid out; it follows new content while the reader is at the bottom. */
@Composable
private fun Transcript(items: List<ChatItem>) {
    val list = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= list.layoutInfo.totalItemsCount - 1
        }
    }
    val lastText = (items.lastOrNull() as? ChatItem.AgentMessage)?.text?.length
    LaunchedEffect(items.size, lastText) {
        if (atBottom && items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                is ChatItem.UserPrompt -> UserPrompt(item)
                is ChatItem.AgentMessage -> AgentMessage(item)
                is ChatItem.ToolCall -> ToolCall(item)
                is ChatItem.TurnComplete -> TurnComplete(item)
                is ChatItem.Request -> RequestPlaceholder(item)
            }
        }
    }
}

@Composable
private fun UserPrompt(item: ChatItem.UserPrompt) {
    val palette = LocalTroupePalette.current
    Column(
        Modifier.fillMaxWidth().background(palette.elevated, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.chat_you), style = MaterialTheme.typography.labelMedium, color = palette.muted)
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AgentMessage(item: ChatItem.AgentMessage) {
    val palette = LocalTroupePalette.current
    if (item.thought) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.chat_thinking), style = MaterialTheme.typography.labelMedium, color = palette.muted)
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = palette.muted, fontStyle = FontStyle.Italic)
        }
    } else {
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToolCall(item: ChatItem.ToolCall) {
    val palette = LocalTroupePalette.current
    val (label, color) = when (item.status) {
        "completed" -> stringResource(R.string.chat_tool_completed) to palette.success
        "failed" -> stringResource(R.string.chat_tool_failed) to palette.error
        "in_progress" -> stringResource(R.string.chat_tool_running) to palette.info
        else -> stringResource(R.string.chat_tool_pending) to palette.muted
    }
    Row(
        Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            item.title.ifBlank { stringResource(R.string.chat_tool_untitled) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun TurnComplete(item: ChatItem.TurnComplete) {
    val text = if (item.stopReason == "end_turn") stringResource(R.string.chat_turn_done) else stringResource(R.string.chat_turn_ended, item.stopReason)
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalTroupePalette.current.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun RequestPlaceholder(item: ChatItem.Request) {
    val palette = LocalTroupePalette.current
    val text = when (item.kind) {
        RequestKind.Permission -> stringResource(R.string.chat_permission_request, item.text)
        RequestKind.Question -> stringResource(R.string.chat_question_request, item.text)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.warning,
        modifier = Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(6.dp)).padding(12.dp),
    )
}

@Composable
private fun Plan(entries: List<PlanEntry>) {
    val palette = LocalTroupePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.chat_plan), style = MaterialTheme.typography.labelMedium, color = palette.muted)
        entries.forEach { entry ->
            val (text, color) = when (entry.status) {
                "completed" -> stringResource(R.string.chat_plan_done, entry.content) to palette.muted
                "in_progress" -> stringResource(R.string.chat_plan_running, entry.content) to palette.info
                else -> stringResource(R.string.chat_plan_pending, entry.content) to palette.foreground
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
