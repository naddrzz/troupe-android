package dev.troupe.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.FadeEndText
import dev.troupe.android.core.ui.NeedsYouPill
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons

/**
 * 1:1 port of Troupe's `MobileShellHeader.tsx`:
 * `☰` · title (15/20 fade-end) + subtitle (`project · branch · Local ▾`) · Attention pill · `✎` · `⋯`.
 * Every interactive slot keeps a 44dp touch floor; on narrow screens (`<= 360dp`) the attention
 * pill folds into an 8dp warning dot on the menu button.
 */
@Composable
fun MobileShellHeader(
    title: String,
    subtitle: String,
    attentionCount: Int,
    isTerminal: Boolean,
    canNewChat: Boolean,
    connectionState: ConnectionState,
    viewOnly: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenProjectSheet: () -> Unit,
    onNewChat: () -> Unit,
    onNewTerminal: () -> Unit,
    onOpenMore: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    Column(modifier = modifier.fillMaxWidth().background(palette.background)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val narrow = maxWidth <= 360.dp
            val hasAttention = attentionCount > 0
            val foldedIntoMenu = narrow && hasAttention
            val showPill = hasAttention && !narrow

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Slot 1: Menu button (44dp) with folded warning dot on <= 360dp
                Box {
                    TroupeIconButton(
                        icon = TroupeIcons.Menu,
                        contentDescription = stringResource(R.string.shell_open_menu),
                        onClick = onOpenDrawer,
                    )
                    if (foldedIntoMenu) {
                        val attentionDesc = pluralStringResource(R.plurals.needs_you, attentionCount, attentionCount)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette.warning)
                                .semantics { contentDescription = attentionDesc },
                        )
                    }
                }

                // Slot 2: Title + subtitle button (opens Project/Server sheet)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onOpenProjectSheet)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    FadeEndText(
                        text = title,
                        style = typo.titleMedium,
                        color = palette.foreground,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        FadeEndText(
                            text = subtitle,
                            style = typo.bodySmall,
                            color = palette.mutedForeground,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = TroupeIcons.ChevronDown,
                            contentDescription = null,
                            tint = palette.mutedForeground,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                // Optional Slot 3: Attention pill (when > 0 and width > 360dp)
                if (showPill) {
                    NeedsYouPill(
                        count = attentionCount,
                        onClick = onOpenDrawer,
                    )
                }

                // Slot 4: New Chat / New Terminal
                if (isTerminal) {
                    TroupeIconButton(
                        icon = TroupeIcons.Plus,
                        contentDescription = stringResource(R.string.terminals_new),
                        onClick = onNewTerminal,
                        enabled = !viewOnly,
                    )
                } else if (canNewChat) {
                    TroupeIconButton(
                        icon = TroupeIcons.MessageSquarePlus,
                        contentDescription = stringResource(R.string.shell_new_chat),
                        onClick = onNewChat,
                    )
                }

                // Slot 5: More / Terminal actions
                TroupeIconButton(
                    icon = TroupeIcons.MoreHorizontal,
                    contentDescription = stringResource(R.string.shell_more_actions),
                    onClick = onOpenMore,
                )
            }
        }

        // Subtle inline connection banner only when disconnected/reconnecting or view-only
        val statusBanner: Triple<String, Boolean, Boolean>? = when (connectionState) {
            is ConnectionState.Connecting -> Triple(stringResource(R.string.connection_connecting), false, false)
            is ConnectionState.Reconnecting -> Triple(stringResource(R.string.connection_reconnecting, connectionState.attempt), true, true)
            is ConnectionState.ServerOffline -> Triple(stringResource(R.string.connection_offline), true, true)
            is ConnectionState.Failed -> {
                val recoverable = connectionState.reason == dev.troupe.android.core.net.ConnectionFailure.Unreachable
                Triple(connectionState.message ?: stringResource(R.string.connection_disconnected), true, recoverable)
            }
            else -> if (viewOnly) Triple(stringResource(R.string.view_only_badge), false, false) else null
        }

        if (statusBanner != null) {
            val (bannerText, isWarning, canRetry) = statusBanner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isWarning) palette.warningTint else palette.mutedSurface)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = bannerText,
                    style = typo.labelSmall,
                    color = if (isWarning) palette.warning else palette.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                if (canRetry) {
                    Text(
                        text = stringResource(R.string.connection_retry),
                        style = typo.labelMedium,
                        color = palette.brandText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onRetryConnection)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
    }
}
