package dev.troupe.android.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar

@Composable
fun ServersScreen(
    onPair: () -> Unit,
    onOpenServer: (String) -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.probe() }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TroupeTopBar(
                title = stringResource(R.string.servers_title),
                actions = {
                    if (!rows.isNullOrEmpty()) {
                        IconButton(onClick = onPair) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.servers_pair_action))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyServers(onPair, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.server.id }) { row ->
                    ServerRowItem(
                        row,
                        onClick = { onOpenServer(row.server.id) },
                        onRename = { renaming = row.server.id },
                        onRemove = { removing = row.server.id },
                    )
                    HorizontalDivider(color = LocalTroupePalette.current.border)
                }
            }
        }
        list.firstOrNull { it.server.id == renaming }?.let { row ->
            RenameDialog(
                current = row.server.name,
                onSave = { viewModel.rename(row.server.id, it); renaming = null },
                onDismiss = { renaming = null },
            )
        }
        list.firstOrNull { it.server.id == removing }?.let { row ->
            RemoveDialog(
                name = row.server.name,
                onConfirm = { viewModel.remove(row.server.id); removing = null },
                onDismiss = { removing = null },
            )
        }
    }
}

@Composable
private fun EmptyServers(onPair: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.servers_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.servers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalTroupePalette.current.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPair, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.servers_pair_action))
        }
    }
}

/** Tap opens the server; long-press, the overflow button and TalkBack actions all offer Rename and Remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRowItem(row: ServerRow, onClick: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val renameLabel = stringResource(R.string.servers_rename)
    val removeLabel = stringResource(R.string.servers_remove)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                    CustomAccessibilityAction(removeLabel) { onRemove(); true },
                )
            }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.server.address.baseUrl.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = LocalTroupePalette.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConnectionBadge(row.status)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vertical),
                    contentDescription = stringResource(R.string.servers_row_actions, row.server.name),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(renameLabel) }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text(removeLabel) }, onClick = { menuOpen = false; onRemove() })
            }
        }
    }
}

@Composable
private fun ConnectionBadge(status: ServerStatus) {
    val palette = LocalTroupePalette.current
    val (color: Color, label: Int) = when (status) {
        ServerStatus.Checking -> palette.muted to R.string.servers_status_checking
        ServerStatus.Connected -> palette.success to R.string.servers_status_connected
        ServerStatus.Unreachable -> palette.warning to R.string.servers_status_unreachable
        ServerStatus.TokenRejected -> palette.error to R.string.servers_status_token_rejected
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = palette.muted)
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.servers_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.servers_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.servers_cancel)) } },
    )
}

@Composable
private fun RemoveDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_remove_title, name)) },
        text = { Text(stringResource(R.string.servers_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.servers_remove), color = LocalTroupePalette.current.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.servers_cancel)) } },
    )
}
