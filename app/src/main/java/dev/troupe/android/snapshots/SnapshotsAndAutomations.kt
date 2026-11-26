package dev.troupe.android.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.core.store.PersistenceKeys
import dev.troupe.android.core.store.StoreReadResult
import dev.troupe.android.core.store.WebStoreClient
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.core.ui.TroupeSpinner
import dev.troupe.android.terminal.protocol.PreservedTerminal
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class PersistedTerminalEntry(
    val id: String,
    val name: String,
    val shell: String,
    val cwd: String? = null,
)

@Serializable
data class PersistedSnapshotDto(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String? = null,
    val createdAt: String,
    val terminals: List<PersistedTerminalEntry> = emptyList(),
    val activeTerminalId: String? = null,
)

@Serializable
data class PersistedSnapshotListDto(
    val snapshots: List<PersistedSnapshotDto> = emptyList(),
    val updatedAt: String = "",
)

/**
 * Snapshots bottom sheet synced with `WebStore` (`device/<id>/snapshots/<projectId>`).
 */
@Composable
fun SnapshotsSheet(
    projectId: String,
    terminals: List<PreservedTerminal>,
    storeClient: WebStoreClient?,
    viewOnly: Boolean,
    onDismiss: () -> Unit,
    onRestoreSnapshot: (PersistedSnapshotDto) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()

    var snapshots by remember(projectId) { mutableStateOf<List<PersistedSnapshotDto>>(emptyList()) }
    var loading by remember(projectId) { mutableStateOf(true) }
    var loadSucceeded by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var saving by remember(projectId) { mutableStateOf(false) }
    var nameInput by remember(projectId) { mutableStateOf("") }
    /** Id of the snapshot whose restore waits for confirmation. */
    var confirmRestore by remember(projectId) { mutableStateOf<String?>(null) }

    fun persist(next: List<PersistedSnapshotDto>, savedNameToClear: String? = null) {
        if (!loadSucceeded || saving) return
        val client = storeClient
        if (client == null) {
            error = "Not connected to server."
            return
        }
        saving = true
        error = null
        scope.launch {
            val encoded = TroupeJson.encodeToJsonElement(
                PersistedSnapshotListDto.serializer(),
                PersistedSnapshotListDto(snapshots = next, updatedAt = Instant.now().toString()),
            )
            when (val res = client.write(PersistenceKeys.snapshots(projectId), encoded, immediate = true)) {
                is dev.troupe.android.core.store.StoreWriteResult.Ok -> {
                    snapshots = next
                    if (savedNameToClear != null && nameInput.trim() == savedNameToClear) {
                        nameInput = ""
                    }
                }
                is dev.troupe.android.core.store.StoreWriteResult.Error -> {
                    error = res.message ?: "Failed to save snapshots."
                }
            }
            saving = false
        }
    }

    LaunchedEffect(projectId, storeClient) {
        loading = true
        loadSucceeded = false
        error = null
        val client = storeClient
        if (client == null) {
            error = "Not connected to server."
            loading = false
            return@LaunchedEffect
        }
        when (val res = client.read(PersistenceKeys.snapshots(projectId))) {
            is StoreReadResult.Found -> {
                val decoded = runCatching {
                    TroupeJson.decodeFromJsonElement(PersistedSnapshotListDto.serializer(), res.data)
                }.getOrNull()
                if (decoded != null) {
                    snapshots = decoded.snapshots
                    loadSucceeded = true
                } else {
                    error = "Could not decode saved snapshots."
                }
            }
            StoreReadResult.NotFound -> {
                snapshots = emptyList()
                loadSucceeded = true
            }
            is StoreReadResult.Error -> {
                error = res.message ?: "Could not load snapshots."
            }
        }
        loading = false
    }

    TroupeMobileSheet(
        title = stringResource(R.string.shell_snapshots),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                Text(text = error!!, style = typo.bodySmall, color = palette.destructive)
            }
            if (!viewOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(R.string.snapshots_name_hint)) },
                        enabled = loadSucceeded && !saving,
                        singleLine = true,
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
                        text = stringResource(R.string.snapshots_save),
                        enabled = loadSucceeded && !saving && nameInput.isNotBlank(),
                        loading = saving,
                        onClick = {
                            val trimmedName = nameInput.trim()
                            val snap = PersistedSnapshotDto(
                                id = UUID.randomUUID().toString(),
                                projectId = projectId,
                                name = trimmedName,
                                createdAt = Instant.now().toString(),
                                terminals = terminals.map {
                                    PersistedTerminalEntry(
                                        id = it.id,
                                        name = it.shell.substringAfterLast('/'),
                                        shell = it.shell,
                                        cwd = it.cwd,
                                    )
                                },
                                activeTerminalId = terminals.firstOrNull()?.id,
                            )
                            persist(listOf(snap) + snapshots, savedNameToClear = trimmedName)
                        },
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    TroupeSpinner(size = 20.dp)
                }
                !loadSucceeded -> Unit
                snapshots.isEmpty() -> Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = stringResource(R.string.snapshots_empty), style = typo.titleSmall, color = palette.foreground)
                    Text(text = stringResource(R.string.snapshots_empty_body), style = typo.bodyMedium, color = palette.mutedForeground)
                }
                else -> snapshots.forEach { snap ->
                    if (confirmRestore == snap.id) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.snapshots_restore_confirm,
                                snap.terminals.size,
                                snap.name,
                                snap.terminals.size,
                            ),
                            style = typo.bodySmall,
                            color = palette.warning,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.card)
                            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(snap.name, style = typo.titleSmall, color = palette.foreground)
                            Text(
                                text = "${snap.terminals.size} terminals",
                                style = typo.bodySmall,
                                color = palette.mutedForeground,
                            )
                        }
                        if (!viewOnly) {
                            TroupeButton(
                                text = stringResource(R.string.snapshots_restore),
                                variant = TroupeButtonVariant.Secondary,
                                minHeight = 36.dp,
                                onClick = {
                                    if (confirmRestore != snap.id) {
                                        confirmRestore = snap.id
                                    } else {
                                        onDismiss()
                                        onRestoreSnapshot(snap)
                                    }
                                },
                            )
                            TroupeIconButton(
                                icon = TroupeIcons.Trash,
                                contentDescription = stringResource(R.string.servers_remove),
                                onClick = { persist(snapshots.filterNot { it.id == snap.id }) },
                                enabled = loadSucceeded && !saving,
                                iconSize = 16.dp,
                                contentColor = palette.mutedForeground,
                            )
                        }
                    }
                }
            }
        }
    }
}
