package dev.troupe.android.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet

/**
 * 1:1 port of Troupe's `MobileTerminalActionsSheet.tsx`:
 * Inline rename terminal, restart terminal, Git changes, Files, Command palette, Settings,
 * and destructive Kill/Close terminal.
 */
@Composable
fun MobileTerminalActionsSheet(
    name: String,
    lastExitCode: Int?,
    viewOnly: Boolean,
    hasProjectPath: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRestart: () -> Unit,
    onOpenGitChanges: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onKill: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var renaming by remember { mutableStateOf(false) }
    var draftName by remember(name) { mutableStateOf(name) }

    TroupeMobileSheet(
        title = name,
        subtitle = lastExitCode?.let { stringResource(R.string.terminal_last_exit_code, it) },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetGroup {
                if (renaming) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
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
                            text = stringResource(R.string.servers_save),
                            onClick = {
                                if (draftName.isNotBlank()) {
                                    onRename(draftName.trim())
                                    onDismiss()
                                }
                            },
                        )
                    }
                } else {
                    SheetRow(
                        title = stringResource(R.string.terminal_rename),
                        leading = {
                            Icon(TroupeIcons.Edit, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                        },
                        onClick = { renaming = true },
                    )
                }

                if (!viewOnly) {
                    SheetRowDivider()
                    SheetRow(
                        title = stringResource(R.string.terminal_restart),
                        leading = {
                            Icon(TroupeIcons.Refresh, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onDismiss()
                            onRestart()
                        },
                    )
                }

                if (hasProjectPath) {
                    SheetRowDivider()
                    SheetRow(
                        title = stringResource(R.string.shell_git_changes),
                        leading = {
                            Icon(TroupeIcons.FileDiff, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onDismiss()
                            onOpenGitChanges()
                        },
                    )
                    SheetRowDivider()
                    SheetRow(
                        title = stringResource(R.string.shell_files),
                        leading = {
                            Icon(TroupeIcons.Folder, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onDismiss()
                            onOpenFiles()
                        },
                    )
                }

                SheetRowDivider()
                SheetRow(
                    title = stringResource(R.string.shell_command_palette),
                    leading = {
                        Icon(TroupeIcons.Search, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onDismiss()
                        onOpenCommandPalette()
                    },
                )
                SheetRowDivider()
                SheetRow(
                    title = stringResource(R.string.settings_title),
                    leading = {
                        Icon(TroupeIcons.Settings, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
            }

            if (!viewOnly) {
                var confirmKill by remember { mutableStateOf(false) }
                HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                SheetGroup {
                    SheetRow(
                        title = stringResource(R.string.terminals_kill),
                        destructive = true,
                        leading = {
                            Icon(TroupeIcons.Trash, contentDescription = null, tint = palette.destructive, modifier = Modifier.size(18.dp))
                        },
                        onClick = { confirmKill = true },
                    )
                }
                if (confirmKill) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmKill = false },
                        containerColor = palette.popover,
                        titleContentColor = palette.foreground,
                        textContentColor = palette.secondary,
                        title = { Text(stringResource(R.string.terminals_kill)) },
                        text = { Text(stringResource(R.string.terminals_kill_confirm)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    confirmKill = false
                                    onDismiss()
                                    onKill()
                                },
                            ) {
                                Text(stringResource(R.string.terminals_kill), color = palette.error)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmKill = false }) {
                                Text(stringResource(R.string.terminals_cancel), color = palette.foreground)
                            }
                        },
                    )
                }
            }
        }
    }
}
