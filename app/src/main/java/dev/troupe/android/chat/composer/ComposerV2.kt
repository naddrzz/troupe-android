package dev.troupe.android.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.SheetSearchField
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.workspace.AgentDescriptor

/**
 * 1:1 port of Troupe's Composer V2 (`docs/design/composer-v2-handoff.md` + `ChatInputBar.tsx`):
 * - Compact rounded-20 card (`card` fill, `0.5dp` `border`, subtle brand beam glow while running).
 * - 16sp input (40dp min -> 160dp max, scrolls inside).
 * - Controls row: `+` Add sheet (44dp) | Model/Agent pill | spacer | Blue emboss Send / Stop.
 */
@Composable
fun ComposerV2(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    turnRunning: Boolean,
    enabled: Boolean,
    agents: List<AgentDescriptor>,
    selectedAgentId: String?,
    onSelectAgent: ((String) -> Unit)? = null,
    /** Opens agent setup; with it the pill works even when no agent is ready. */
    onSetUpAgents: (() -> Unit)? = null,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onOpenFiles: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var showAddSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()
    val setUpAgents = onSetUpAgents
    val canSend = enabled && draft.isNotBlank() && !turnRunning

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .drawBehind {
                    if (turnRunning) {
                        // Subtle top beam glow matching composer-v2-handoff.md
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.brand.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width / 2f, 0f),
                                radius = size.width * 0.7f,
                            ),
                        )
                    }
                }
                .border(0.5.dp, if (turnRunning) palette.brand.copy(alpha = 0.45f) else palette.border, RoundedCornerShape(20.dp))
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Text area (16sp prevents zoom feel, grows smoothly 40dp..160dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 160.dp)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = typo.bodyLarge,
                        color = palette.mutedForeground,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    textStyle = typo.bodyLarge.copy(color = palette.foreground),
                    cursorBrush = SolidColor(palette.brand),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Bottom action row: + | Model pill | spacer | Send/Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TroupeIconButton(
                    icon = TroupeIcons.Plus,
                    contentDescription = stringResource(R.string.composer_add),
                    onClick = { showAddSheet = true },
                    enabled = enabled,
                    size = 40.dp,
                    iconSize = 18.dp,
                    shape = RoundedCornerShape(10.dp),
                    contentColor = palette.secondary,
                )

                // Model / Agent selector pill (interactive only when caller provides onSelectAgent)
                val agentSelectable = onSelectAgent != null && enabled && (agents.isNotEmpty() || setUpAgents != null)
                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (agentSelectable) {
                                Modifier.clickable(role = Role.Button) {
                                    if (agents.isEmpty() && setUpAgents != null) setUpAgents() else showModelSheet = true
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = TroupeIcons.Sparkles,
                        contentDescription = null,
                        tint = palette.brandText,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = selectedAgent?.name
                            ?: stringResource(if (agentSelectable) R.string.agents_set_up else R.string.composer_default_agent),
                        style = typo.labelMedium,
                        color = palette.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (agentSelectable) {
                        Icon(
                            imageVector = TroupeIcons.ChevronDown,
                            contentDescription = null,
                            tint = palette.mutedForeground,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (turnRunning) {
                    TroupeButton(
                        onClick = onCancel,
                        variant = TroupeButtonVariant.Secondary,
                        minHeight = 36.dp,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = TroupeIcons.StopSquare,
                            contentDescription = stringResource(R.string.chat_cancel_turn_description),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    TroupeButton(
                        onClick = onSend,
                        variant = TroupeButtonVariant.Composer,
                        enabled = canSend,
                        minHeight = 36.dp,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = TroupeIcons.ArrowUp,
                            contentDescription = stringResource(R.string.chat_send),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ComposerAddSheet(
            onDismiss = { showAddSheet = false },
            onInsertTrigger = { trigger ->
                showAddSheet = false
                val sep = if (draft.isEmpty() || draft.endsWith(" ")) "" else " "
                onDraftChange("$draft$sep$trigger")
            },
            onOpenFiles = onOpenFiles?.let { open ->
                {
                    showAddSheet = false
                    open()
                }
            },
        )
    }

    if (showModelSheet && onSelectAgent != null) {
        MobileModelSheet(
            agents = agents,
            selectedAgentId = selectedAgent?.id,
            onDismiss = { showModelSheet = false },
            onSelect = { id ->
                onSelectAgent(id)
                showModelSheet = false
            },
            onSetUpAgents = setUpAgents?.let { open ->
                {
                    showModelSheet = false
                    open()
                }
            },
        )
    }
}

@Composable
private fun ComposerAddSheet(
    onDismiss: () -> Unit,
    onInsertTrigger: (String) -> Unit,
    onOpenFiles: (() -> Unit)?,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    TroupeMobileSheet(
        title = stringResource(R.string.composer_add_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Attach tile row (1:1 ComposerAddSheet.tsx 76dp rounded-xl tiles)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachTile(
                    icon = TroupeIcons.FileText,
                    label = stringResource(R.string.composer_mention_file),
                    onClick = { onInsertTrigger("@") },
                    modifier = Modifier.weight(1f),
                )
                AttachTile(
                    icon = TroupeIcons.Wrench,
                    label = stringResource(R.string.composer_slash_command),
                    onClick = { onInsertTrigger("/") },
                    modifier = Modifier.weight(1f),
                )
                if (onOpenFiles != null) {
                    AttachTile(
                        icon = TroupeIcons.Folder,
                        label = stringResource(R.string.shell_files),
                        onClick = onOpenFiles,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SheetGroup {
                SheetRow(
                    title = stringResource(R.string.composer_mention_row),
                    subtitle = stringResource(R.string.composer_mention_row_sub),
                    leading = {
                        Text("@", style = typo.titleMedium, color = palette.brandText)
                    },
                    onClick = { onInsertTrigger("@") },
                )
                SheetRowDivider()
                SheetRow(
                    title = stringResource(R.string.composer_commands_row),
                    subtitle = stringResource(R.string.composer_commands_row_sub),
                    leading = {
                        Text("/", style = typo.titleMedium, color = palette.brandText)
                    },
                    onClick = { onInsertTrigger("/") },
                )
            }
        }
    }
}

@Composable
private fun AttachTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.mutedSurface)
            .border(0.5.dp, palette.popoverEdge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp))
        Text(text = label, style = typo.labelLarge, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MobileModelSheet(
    agents: List<AgentDescriptor>,
    selectedAgentId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onSetUpAgents: (() -> Unit)? = null,
) {
    val palette = TroupeTheme.palette
    var query by remember { mutableStateOf("") }
    val filtered = agents.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true)
    }

    TroupeMobileSheet(
        title = stringResource(R.string.composer_choose_agent),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (agents.size >= 6) {
                SheetSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.composer_search_agents),
                )
            }
            SheetGroup {
                filtered.forEachIndexed { idx, agent ->
                    if (idx > 0) SheetRowDivider()
                    SheetRow(
                        title = agent.name,
                        picked = agent.id == selectedAgentId,
                        leading = {
                            Icon(
                                imageVector = TroupeIcons.Bot,
                                contentDescription = null,
                                tint = if (agent.id == selectedAgentId) palette.brandText else palette.mutedForeground,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { onSelect(agent.id) },
                    )
                }
            }
            if (onSetUpAgents != null) {
                TroupeButton(
                    text = stringResource(R.string.agents_set_up),
                    variant = TroupeButtonVariant.Secondary,
                    onClick = onSetUpAgents,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
