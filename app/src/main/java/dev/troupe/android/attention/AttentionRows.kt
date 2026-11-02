package dev.troupe.android.attention

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import kotlinx.coroutines.delay

/** The server screen's Attention section: a heading and one row per item, oldest first. Nothing when empty. */
@Composable
fun AttentionSection(items: List<AttentionItem>, onOpen: (AttentionItem) -> Unit) {
    if (items.isEmpty()) return
    val palette = LocalTroupePalette.current
    // The ages tick on their own; once a minute is as precise as they read.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxWidth().background(palette.elevated)) {
        Text(
            stringResource(R.string.attention_needs_attention),
            style = MaterialTheme.typography.labelLarge,
            color = palette.warning,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
        items.forEach { item ->
            AttentionRow(item, now, onClick = { onOpen(item) })
        }
        HorizontalDivider(color = palette.border)
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, now: Long, onClick: () -> Unit) {
    val palette = LocalTroupePalette.current
    val kind = stringResource(
        when (item.kind) {
            AttentionKind.Permission -> R.string.attention_kind_permission
            AttentionKind.Question -> R.string.attention_kind_question
            AttentionKind.Stopped -> R.string.attention_kind_stopped
        },
    )
    val name = chatName(item.projectName, item.sessionTitle).let { stringResource(it.res, *it.args.toTypedArray()) }
    val age = DateUtils.getRelativeTimeSpanString(item.since, maxOf(now, item.since), DateUtils.MINUTE_IN_MILLIS).toString()
    val description = stringResource(R.string.attention_row_description, kind, name, age)
    val openLabel = stringResource(R.string.attention_open_chat)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = openLabel) { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.labelMedium, color = palette.warning)
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(age, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
