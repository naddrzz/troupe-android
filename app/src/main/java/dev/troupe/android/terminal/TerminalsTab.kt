package dev.troupe.android.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette

/** A Project's Terminals tab: the running Terminals (refreshed whenever it shows), "New terminal" and kill. */
@Composable
fun TerminalsTab(onOpenTerminal: (terminalId: String) -> Unit, viewModel: TerminalsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    val palette = LocalTroupePalette.current
    var confirmKill by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.openTerminal) {
        state.openTerminal?.let {
            viewModel.onTerminalOpened()
            onOpenTerminal(it)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (viewOnly) {
                Text(
                    stringResource(R.string.terminals_view_only),
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else Button(onClick = viewModel::spawn, enabled = !state.spawning, modifier = Modifier.heightIn(min = 48.dp)) {
                if (state.spawning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.terminals_starting), Modifier.padding(start = 8.dp))
                } else {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.terminals_new), Modifier.padding(start = 8.dp))
                }
            }
            if (!viewOnly) state.error?.let {
                Text(
                    stringResource(it.text, it.arg.orEmpty()),
                    color = palette.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!viewOnly && !state.loading && state.terminals.isEmpty()) {
                Text(
                    stringResource(R.string.terminals_empty),
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.loading) {
                Text(stringResource(R.string.terminals_loading), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(color = palette.border)
        LazyColumn(Modifier.weight(1f)) {
            items(state.terminals, key = { it.id }) { row ->
                TerminalRowItem(
                    row,
                    killing = state.killing == row.id,
                    onClick = { onOpenTerminal(row.id) },
                    // One kill at a time: other rows offer none while one is in flight.
                    onKill = if (viewOnly || (state.killing != null && state.killing != row.id)) null else { { confirmKill = row.id } },
                )
                HorizontalDivider(color = palette.border)
            }
        }
    }
    confirmKill?.let { id ->
        KillDialog(onConfirm = { confirmKill = null; viewModel.kill(id) }, onDismiss = { confirmKill = null })
    }
}

/** [onKill] is null when the Terminal can't be killed from here (a View-only server, another kill running): no Kill action then. */
@Composable
private fun TerminalRowItem(row: TerminalRow, killing: Boolean, onClick: () -> Unit, onKill: (() -> Unit)?) {
    val palette = LocalTroupePalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val killLabel = stringResource(R.string.terminals_kill)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics { if (onKill != null) customActions = listOf(CustomAccessibilityAction(killLabel) { onKill(); true }) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.cwd, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            Text(row.shell, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.openElsewhere) {
            Text(
                stringResource(R.string.terminals_open_elsewhere),
                style = MaterialTheme.typography.labelMedium,
                color = palette.info,
                modifier = Modifier
                    .background(palette.elevated, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (onKill != null) Box {
            if (killing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vertical),
                        contentDescription = stringResource(R.string.terminals_row_actions, row.cwd),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(killLabel, color = palette.error) }, onClick = { menuOpen = false; onKill() })
            }
        }
    }
}

@Composable
private fun KillDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.terminals_kill_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.terminals_kill), color = LocalTroupePalette.current.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminals_cancel)) } },
    )
}
