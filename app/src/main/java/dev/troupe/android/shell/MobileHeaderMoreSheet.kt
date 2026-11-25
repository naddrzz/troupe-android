package dev.troupe.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet

/**
 * 1:1 port of Troupe's `MobileHeaderMoreSheet.tsx`:
 * Git changes, Files, Command palette, Project settings, Reload chat (for agent-chat),
 * and destructive Close chat / Close tab separated by a divider.
 */
@Composable
fun MobileHeaderMoreSheet(
    title: String,
    subtitle: String,
    hasProjectPath: Boolean,
    isChatTab: Boolean,
    hasActiveTab: Boolean,
    onDismiss: () -> Unit,
    onOpenGitChanges: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onReloadChat: (() -> Unit)? = null,
    onCloseTab: (() -> Unit)? = null,
) {
    val palette = TroupeTheme.palette

    TroupeMobileSheet(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetGroup {
                if (hasProjectPath) {
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
                    SheetRowDivider()
                }
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
                if (isChatTab && onReloadChat != null) {
                    SheetRowDivider()
                    SheetRow(
                        title = stringResource(R.string.shell_reload_chat),
                        leading = {
                            Icon(TroupeIcons.Refresh, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onDismiss()
                            onReloadChat()
                        },
                    )
                }
            }

            if (hasActiveTab && onCloseTab != null) {
                HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                SheetGroup {
                    SheetRow(
                        title = stringResource(if (isChatTab) R.string.shell_close_chat else R.string.shell_close_tab),
                        destructive = true,
                        leading = {
                            Icon(TroupeIcons.Close, contentDescription = null, tint = palette.destructive, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onDismiss()
                            onCloseTab()
                        },
                    )
                }
            }
        }
    }
}
