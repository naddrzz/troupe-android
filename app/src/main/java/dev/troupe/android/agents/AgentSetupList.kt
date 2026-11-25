package dev.troupe.android.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.StatusDot
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMobileSheet
import dev.troupe.android.core.ui.TroupeSpinner

/**
 * Troupe's "Check your agents" list (`AgentsStep.tsx` + `AgentSetupRow.tsx`): featured agents
 * first, one status word per row and the action that moves it forward. A ready row is picked by
 * tapping it; [selectedConfigId] is the pick.
 */
@Composable
fun AgentSetupList(
    state: AgentSetupState,
    selectedConfigId: String?,
    viewOnly: Boolean,
    onSelect: (AgentConfig) -> Unit,
    onInstall: (CatalogAgent) -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var showAll by rememberSaveable { mutableStateOf(false) }
    val ordered = featuredFirst(state.catalog)
    val featuredCount = ordered.count { it.id in FEATURED_AGENT_IDS }
    // Agents the user already has stay visible even when they are not featured.
    val visible = if (showAll) ordered else ordered.filterIndexed { i, a -> i < featuredCount || state.isReady(a) }
    val readyCount = state.catalog.count { state.isReady(it) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (!state.loaded) {
                    stringResource(R.string.agents_looking)
                } else {
                    pluralStringResource(R.plurals.agents_found_ready, readyCount, readyCount)
                },
                style = typo.labelLarge,
                color = palette.mutedForeground,
                modifier = Modifier.weight(1f),
            )
            if (!state.loaded) TroupeSpinner(size = 16.dp)
        }
        TroupeButton(
            text = stringResource(R.string.agents_check_again),
            icon = TroupeIcons.Refresh,
            variant = TroupeButtonVariant.Ghost,
            enabled = state.loaded,
            onClick = onCheckAgain,
        )
        if (state.error != null) {
            Text(text = state.error, style = typo.bodySmall, color = palette.destructive)
        }
        visible.forEach { agent ->
            val config = state.launchable.firstOrNull { it.id == registryConfigId(agent.id) }
            AgentSetupRow(
                agent = agent,
                ready = config != null,
                selected = config != null && config.id == selectedConfigId,
                installing = agent.id in state.installing,
                viewOnly = viewOnly,
                onSelect = { config?.let(onSelect) },
                onInstall = { onInstall(agent) },
            )
        }
        if (!showAll && ordered.size > visible.size) {
            TroupeButton(
                text = stringResource(R.string.agents_show_all, ordered.size),
                variant = TroupeButtonVariant.Ghost,
                onClick = { showAll = true },
            )
        }
    }
}

@Composable
private fun AgentSetupRow(
    agent: CatalogAgent,
    ready: Boolean,
    selected: Boolean,
    installing: Boolean,
    viewOnly: Boolean,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    // "Ready" means a chat can start with it: a ready catalog entry this app cannot build a launch
    // config for is not available here.
    val status = when {
        ready -> AgentStatus.Ready
        agent.status == AgentStatus.Ready -> AgentStatus.Unavailable
        else -> agent.status
    }
    val runtime = stringResource(if (agent.needsUv) R.string.agents_runtime_uv else R.string.agents_runtime_node)
    val (word, tone) = when (status) {
        AgentStatus.Ready -> stringResource(R.string.agents_status_ready) to palette.success
        AgentStatus.NeedsRuntime -> stringResource(R.string.agents_status_needs_runtime, runtime) to palette.warning
        AgentStatus.Unavailable -> stringResource(R.string.agents_status_unavailable) to palette.mutedForeground
        else -> stringResource(R.string.agents_status_not_installed) to palette.mutedForeground
    }
    val hint = when (status) {
        AgentStatus.Ready -> agent.description
        AgentStatus.InstallRequired -> stringResource(R.string.agents_hint_install)
        AgentStatus.NeedsRuntime -> stringResource(R.string.agents_hint_runtime, runtime)
        AgentStatus.ManualInstall -> stringResource(R.string.agents_hint_manual)
        AgentStatus.Unavailable -> stringResource(R.string.agents_hint_unavailable)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.card)
            .border(if (selected) 1.dp else 0.5.dp, if (selected) palette.brandText else palette.border, shape)
            .then(if (ready) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TroupeIcons.Bot, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = agent.name, style = typo.titleSmall, color = palette.foreground)
                if (hint.isNotBlank()) {
                    Text(text = hint, style = typo.bodySmall, color = palette.mutedForeground, maxLines = 2)
                }
            }
            if (selected) {
                Icon(TroupeIcons.Check, contentDescription = null, tint = palette.brandText, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(color = tone, size = 6.dp)
            Text(text = word, style = typo.labelLarge, color = tone, modifier = Modifier.weight(1f))
            if (status == AgentStatus.InstallRequired && !viewOnly) {
                TroupeButton(
                    text = stringResource(if (installing) R.string.agents_installing else R.string.agents_install),
                    variant = TroupeButtonVariant.Secondary,
                    loading = installing,
                    enabled = !installing,
                    onClick = onInstall,
                )
            }
        }
    }
}

/** The agent list as a sheet, for setting up agents after first run. */
@Composable
fun AgentSetupSheet(
    state: AgentSetupState,
    viewOnly: Boolean,
    onSelect: (AgentConfig) -> Unit,
    onInstall: (CatalogAgent) -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    TroupeMobileSheet(title = stringResource(R.string.agents_sheet_title), onDismiss = onDismiss) {
        AgentSetupList(
            state = state,
            selectedConfigId = state.selected?.id,
            viewOnly = viewOnly,
            onSelect = onSelect,
            onInstall = onInstall,
            onCheckAgain = onCheckAgain,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
