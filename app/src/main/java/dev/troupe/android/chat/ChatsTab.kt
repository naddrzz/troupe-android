package dev.troupe.android.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import kotlinx.coroutines.delay

/** A Project's Chats tab: its Sessions, newest first, kept current by the server. */
@Composable
fun ChatsTab(onOpenChat: (sessionId: String, title: String?) -> Unit, viewModel: ChatsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val palette = LocalTroupePalette.current
    // The ages tick on their own; once a minute is as precise as they read.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize()) {
        val notice = when {
            state.error != null -> null
            state.loading -> stringResource(R.string.chats_loading)
            state.sessions.isEmpty() -> stringResource(R.string.chats_empty)
            else -> null
        }
        state.error?.let {
            Text(
                stringResource(it.text, it.arg.orEmpty()),
                color = palette.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        notice?.let {
            Text(
                it,
                color = palette.muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.sessions, key = { it.id }) { row ->
                SessionRowItem(row, now, onClick = { onOpenChat(row.id, row.title) })
                HorizontalDivider(color = palette.border)
            }
        }
    }
}

@Composable
private fun SessionRowItem(row: SessionRow, now: Long, onClick: () -> Unit) {
    val palette = LocalTroupePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.title ?: stringResource(R.string.chats_untitled),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.lastActivityAt > 0) {
            Text(
                DateUtils.getRelativeTimeSpanString(row.lastActivityAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
            )
        }
    }
}
