package dev.troupe.android.lock

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.AppearanceMode
import dev.troupe.android.core.theme.BundledThemes
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.SheetGroup
import dev.troupe.android.core.ui.SheetRow
import dev.troupe.android.core.ui.SheetRowDivider
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.shell.WorkspaceShellViewModel
import kotlinx.coroutines.launch

private enum class SettingsSection {
    Appearance,
    Security,
    Terminal,
    Notifications,
    Servers,
}

/**
 * Full Troupe V2 single-column Settings screen with category -> detail drill-down:
 * - Appearance & 20 OKLCH Themes (synced to `shared/lww/settings/app`)
 * - Security & Biometric App Lock (uses MainActivity's [LockViewModel])
 * - Terminal (font size + confirm close synced to `settings/app`)
 * - Notifications (synced to `settings/app`)
 * - Paired Servers & Server Reconfigure (`/server/config`)
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit = {},
    onReconfigureServer: (serverId: String) -> Unit = {},
    lockViewModel: LockViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
    shellViewModel: WorkspaceShellViewModel = hiltViewModel(),
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var section by remember { mutableStateOf<SettingsSection?>(null) }

    val title = when (section) {
        null -> stringResource(R.string.settings_title)
        SettingsSection.Appearance -> stringResource(R.string.settings_cat_appearance)
        SettingsSection.Security -> stringResource(R.string.settings_cat_security)
        SettingsSection.Terminal -> stringResource(R.string.settings_cat_terminal)
        SettingsSection.Notifications -> stringResource(R.string.settings_cat_notifications)
        SettingsSection.Servers -> stringResource(R.string.servers_title)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TroupeIconButton(
                icon = TroupeIcons.ChevronLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = {
                    if (section != null) section = null else onBack()
                },
            )
            Text(text = title, style = typo.titleMedium, color = palette.foreground)
        }
        HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                null -> SettingsCategoryList(
                    shellViewModel = shellViewModel,
                    onSelect = { section = it },
                )
                SettingsSection.Appearance -> AppearanceSettingsDetail(shellViewModel)
                SettingsSection.Security -> SecuritySettingsDetail(lockViewModel)
                SettingsSection.Terminal -> TerminalSettingsDetail(shellViewModel)
                SettingsSection.Notifications -> NotificationSettingsDetail(shellViewModel)
                SettingsSection.Servers -> ServersSettingsDetail(
                    shellViewModel = shellViewModel,
                    onAddServer = onAddServer,
                    onReconfigureServer = onReconfigureServer,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCategoryList(
    shellViewModel: WorkspaceShellViewModel,
    onSelect: (SettingsSection) -> Unit,
) {
    val palette = TroupeTheme.palette
    val settings by shellViewModel.appSettings.settings.collectAsStateWithLifecycle()
    val shellState by shellViewModel.session.state.collectAsStateWithLifecycle()
    val themeName = BundledThemes.byId(settings.colorTheme).name

    SheetGroup {
        SheetRow(
            title = stringResource(R.string.settings_cat_appearance),
            subtitle = "$themeName · ${settings.appearanceMode.replaceFirstChar { it.uppercase() }}",
            leading = { Icon(TroupeIcons.Sparkles, contentDescription = null, tint = palette.brandText, modifier = Modifier.size(18.dp)) },
            trailing = { Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp)) },
            onClick = { onSelect(SettingsSection.Appearance) },
        )
        SheetRowDivider()
        SheetRow(
            title = stringResource(R.string.settings_cat_terminal),
            subtitle = "${settings.terminalFontSize} px",
            leading = { Icon(TroupeIcons.Terminal, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp)) },
            trailing = { Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp)) },
            onClick = { onSelect(SettingsSection.Terminal) },
        )
        SheetRowDivider()
        SheetRow(
            title = stringResource(R.string.settings_cat_notifications),
            subtitle = stringResource(R.string.settings_cat_notifications_sub),
            leading = { Icon(TroupeIcons.MessageSquare, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp)) },
            trailing = { Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp)) },
            onClick = { onSelect(SettingsSection.Notifications) },
        )
        SheetRowDivider()
        SheetRow(
            title = stringResource(R.string.settings_cat_security),
            subtitle = stringResource(R.string.settings_lock),
            leading = { Icon(TroupeIcons.Shield, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp)) },
            trailing = { Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp)) },
            onClick = { onSelect(SettingsSection.Security) },
        )
        SheetRowDivider()
        SheetRow(
            title = stringResource(R.string.servers_title),
            subtitle = shellState.activeServer?.name ?: stringResource(R.string.servers_empty_title),
            leading = { Icon(TroupeIcons.Server, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(18.dp)) },
            trailing = { Icon(TroupeIcons.ChevronRight, contentDescription = null, tint = palette.mutedForeground, modifier = Modifier.size(16.dp)) },
            onClick = { onSelect(SettingsSection.Servers) },
        )
    }
}

@Composable
private fun AppearanceSettingsDetail(shellViewModel: WorkspaceShellViewModel) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()
    val settings by shellViewModel.appSettings.settings.collectAsStateWithLifecycle()
    val currentTheme = BundledThemes.byId(settings.colorTheme)

    Text(
        text = stringResource(R.string.settings_appearance_mode),
        style = typo.labelMedium,
        color = palette.mutedForeground,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppearanceMode.entries.forEach { mode ->
            val selected = settings.resolvedAppearance() == mode
            TroupeButton(
                text = mode.name,
                variant = if (selected) TroupeButtonVariant.Secondary else TroupeButtonVariant.Outline,
                onClick = {
                    shellViewModel.appSettings.update(scope) { it.copy(appearanceMode = mode.wireValue) }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Text(
        text = stringResource(R.string.settings_color_theme),
        style = typo.labelMedium,
        color = palette.mutedForeground,
    )

    SheetGroup {
        BundledThemes.families.forEachIndexed { idx, family ->
            if (idx > 0) SheetRowDivider()
            val preview = BundledThemes.byId(if (palette.isDark) family.darkThemeId else family.lightThemeId)
            val isPicked = currentTheme.familyId == family.familyId
            SheetRow(
                title = family.name,
                picked = isPicked,
                leading = {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(preview.background)
                                .border(0.5.dp, palette.border, CircleShape),
                        )
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(preview.brand),
                        )
                    }
                },
                onClick = {
                    val targetId = if (settings.resolvedAppearance() == AppearanceMode.Light) {
                        family.lightThemeId
                    } else {
                        family.darkThemeId
                    }
                    shellViewModel.appSettings.update(scope) { it.copy(colorTheme = targetId) }
                },
            )
        }
    }
}

@Composable
private fun TerminalSettingsDetail(shellViewModel: WorkspaceShellViewModel) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()
    val settings by shellViewModel.appSettings.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_terminal_font_size), style = typo.bodyMedium, color = palette.foreground)
            Text("${settings.terminalFontSize} px", style = typo.labelLarge, color = palette.brandText)
        }
        Slider(
            value = settings.terminalFontSize.toFloat(),
            onValueChange = { v ->
                shellViewModel.appSettings.update(scope) { it.copy(terminalFontSize = v.toInt()) }
            },
            valueRange = 10f..22f,
            steps = 11,
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.settings_confirm_terminal_close),
        checked = settings.confirmTerminalClose,
        onCheckedChange = { checked ->
            shellViewModel.appSettings.update(scope) { it.copy(confirmTerminalClose = checked) }
        },
    )
}

@Composable
private fun NotificationSettingsDetail(shellViewModel: WorkspaceShellViewModel) {
    val scope = rememberCoroutineScope()
    val settings by shellViewModel.appSettings.settings.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.settings_notify_needs_you),
        checked = settings.notifyOnAgentChatNeedsYou,
        onCheckedChange = { checked ->
            shellViewModel.appSettings.update(scope) { it.copy(notifyOnAgentChatNeedsYou = checked) }
        },
    )
    SettingsToggleRow(
        title = stringResource(R.string.settings_notify_turn_finished),
        checked = settings.notifyOnAgentChatTurnFinished,
        onCheckedChange = { checked ->
            shellViewModel.appSettings.update(scope) { it.copy(notifyOnAgentChatTurnFinished = checked) }
        },
    )
}

@Composable
private fun SecuritySettingsDetail(lockViewModel: LockViewModel) {
    val state by lockViewModel.state.collectAsStateWithLifecycle()
    val authenticator = LocalAuthenticator.current
    var availability by remember { mutableStateOf(authenticator.availability()) }
    LifecycleResumeEffect(authenticator) {
        availability = authenticator.availability()
        onPauseOrDispose {}
    }
    val available = availability == LockAvailability.Available
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
            .heightIn(min = 64.dp)
            .toggleable(
                value = state.enabled,
                enabled = available || state.enabled,
                role = Role.Switch,
                onValueChange = { lockViewModel.setEnabled(it, authenticator) },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.settings_lock), style = typo.titleSmall, color = palette.foreground)
            Text(
                stringResource(
                    when (availability) {
                        LockAvailability.Available -> R.string.settings_lock_body
                        LockAvailability.NoScreenLock -> R.string.settings_lock_no_screen_lock
                        LockAvailability.Unsupported -> R.string.settings_lock_unsupported
                    },
                ),
                style = typo.bodySmall,
                color = palette.mutedForeground,
            )
        }
        Switch(checked = state.enabled, onCheckedChange = null, enabled = available || state.enabled)
    }

    Text(
        text = stringResource(R.string.settings_lock_delay),
        style = typo.labelMedium,
        color = palette.mutedForeground,
    )
    SheetGroup(modifier = Modifier.selectableGroup()) {
        LockDelay.entries.forEachIndexed { idx, delay ->
            if (idx > 0) SheetRowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = state.delay == delay,
                        enabled = state.enabled,
                        role = Role.RadioButton,
                        onClick = { lockViewModel.setDelay(delay) },
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.delay == delay, onClick = null, enabled = state.enabled)
                Text(
                    stringResource(
                        when (delay) {
                            LockDelay.Immediately -> R.string.settings_lock_delay_immediately
                            LockDelay.OneMinute -> R.string.settings_lock_delay_one_minute
                            LockDelay.FiveMinutes -> R.string.settings_lock_delay_five_minutes
                        },
                    ),
                    style = typo.bodyLarge,
                    color = palette.foreground,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ServersSettingsDetail(
    shellViewModel: WorkspaceShellViewModel,
    onAddServer: () -> Unit,
    onReconfigureServer: (serverId: String) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val scope = rememberCoroutineScope()
    val state by shellViewModel.session.state.collectAsStateWithLifecycle()
    var pendingRemoveServer by remember { mutableStateOf<dev.troupe.android.servers.PairedServer?>(null) }

    pendingRemoveServer?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoveServer = null },
            containerColor = palette.popover,
            titleContentColor = palette.foreground,
            textContentColor = palette.secondary,
            title = { Text(stringResource(R.string.servers_remove_title, target.name)) },
            text = { Text(stringResource(R.string.servers_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveServer = null
                        scope.launch { shellViewModel.repository.remove(target.id) }
                    },
                ) {
                    Text(stringResource(R.string.servers_remove), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveServer = null }) {
                    Text(stringResource(R.string.servers_cancel), color = palette.foreground)
                }
            },
        )
    }

    state.servers.forEach { server ->
        val isActive = server.id == state.activeServer?.id
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.card)
                .border(0.5.dp, if (isActive) palette.brandText.copy(alpha = 0.5f) else palette.border, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(TroupeIcons.Server, contentDescription = null, tint = if (isActive) palette.brandText else palette.mutedForeground, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = typo.titleSmall, color = palette.foreground)
                    Text(server.address.baseUrl.toString(), style = typo.bodySmall, color = palette.mutedForeground)
                }
                if (!isActive) {
                    TroupeButton(
                        text = stringResource(R.string.connect_submit),
                        variant = TroupeButtonVariant.Secondary,
                        minHeight = 34.dp,
                        onClick = { shellViewModel.session.selectServer(server.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.servers_stay_connected), style = typo.bodySmall, color = palette.secondary)
                Switch(
                    checked = server.stayConnected,
                    onCheckedChange = { checked ->
                        scope.launch { shellViewModel.repository.setStayConnected(server.id, checked) }
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TroupeButton(
                    text = stringResource(R.string.setup_reconfigure_title),
                    variant = TroupeButtonVariant.Outline,
                    minHeight = 36.dp,
                    onClick = { onReconfigureServer(server.id) },
                    modifier = Modifier.weight(1f),
                )
                TroupeButton(
                    text = stringResource(R.string.servers_remove),
                    variant = TroupeButtonVariant.Destructive,
                    minHeight = 36.dp,
                    onClick = { pendingRemoveServer = server },
                )
            }
        }
    }

    TroupeButton(
        text = stringResource(R.string.servers_pair_action),
        icon = TroupeIcons.Plus,
        onClick = onAddServer,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = typo.bodyMedium, color = palette.foreground, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
