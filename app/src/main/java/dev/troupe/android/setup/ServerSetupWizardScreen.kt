package dev.troupe.android.setup

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMark
import dev.troupe.android.core.ui.TroupeSpinner

/**
 * Full-screen first-run server setup wizard (mode = Setup) and server reconfigure view
 * (mode = Reconfigure). Mirrors `components/setup/ServerSetupWizard.tsx`.
 */
@Composable
fun ServerSetupWizardScreen(
    viewModel: ServerSetupViewModel,
    onCompleted: (serverId: String) -> Unit,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography

    LaunchedEffect(ui.completedServerId) {
        ui.completedServerId?.let(onCompleted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header bar
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
                onClick = onBack,
            )
            Text(
                text = stringResource(
                    if (ui.mode == SetupWizardMode.Setup) R.string.setup_title else R.string.setup_reconfigure_title,
                ),
                style = typo.titleMedium,
                color = palette.foreground,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                ui.loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TroupeSpinner(size = 24.dp)
                        Text(
                            text = stringResource(R.string.setup_loading),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                        )
                    }
                }
                ui.restarting -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TroupeMark(modifier = Modifier.size(36.dp))
                        TroupeSpinner(size = 22.dp)
                        Text(
                            text = stringResource(R.string.setup_restarting),
                            style = typo.titleMedium,
                            color = palette.foreground,
                        )
                        Text(
                            text = stringResource(R.string.setup_restarting_body),
                            style = typo.bodyMedium,
                            color = palette.mutedForeground,
                        )
                    }
                }
                ui.needsSetupToken -> {
                    SetupTokenEntry(
                        error = ui.error,
                        onSubmit = viewModel::submitSetupToken,
                    )
                }
                else -> {
                    SetupFormContent(
                        ui = ui,
                        onUpdate = viewModel::updateAnswers,
                        onApply = viewModel::apply,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupTokenEntry(
    error: String?,
    onSubmit: (String) -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    // Never rememberSaveable for tokens.
    var tokenInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TroupeMark(modifier = Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.setup_token_title),
            style = typo.displayLarge,
            color = palette.foreground,
        )
        Text(
            text = stringResource(R.string.setup_token_body),
            style = typo.bodyMedium,
            color = palette.mutedForeground,
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text(stringResource(R.string.setup_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = setupTextFieldColors(),
        )
        if (error != null) {
            Text(text = error, style = typo.bodySmall, color = palette.destructive)
        }
        TroupeButton(
            text = stringResource(R.string.setup_token_continue),
            onClick = { onSubmit(tokenInput) },
            enabled = tokenInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupFormContent(
    ui: ServerSetupUiState,
    onUpdate: ((ServerSetupAnswers) -> ServerSetupAnswers) -> Unit,
    onApply: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var portText by remember(ui.answers.port) { mutableStateOf(ui.answers.port.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ui.configError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.warningTint)
                    .border(1.dp, palette.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = ui.configError,
                    style = typo.bodySmall,
                    color = palette.foreground,
                )
            }
        }

        // Bind address selector
        Text(
            text = stringResource(R.string.setup_bind_label),
            style = typo.labelMedium,
            color = palette.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BindOptionCard(
                title = stringResource(R.string.setup_bind_all),
                subtitle = "0.0.0.0",
                selected = ui.answers.host == "0.0.0.0",
                onClick = { onUpdate { it.copy(host = "0.0.0.0") } },
                modifier = Modifier.weight(1f),
            )
            BindOptionCard(
                title = stringResource(R.string.setup_bind_local),
                subtitle = "127.0.0.1",
                selected = ui.answers.host == "127.0.0.1",
                onClick = { onUpdate { it.copy(host = "127.0.0.1") } },
                modifier = Modifier.weight(1f),
            )
        }
        ui.fieldErrors.host?.let {
            Text(text = it, style = typo.bodySmall, color = palette.destructive)
        }

        // Port
        val parsedPort = portText.toIntOrNull()
        val portValid = parsedPort != null && parsedPort in 1..65535
        val portErrorText = ui.fieldErrors.port ?: if (!portValid) "Enter a port between 1 and 65535" else null
        OutlinedTextField(
            value = portText,
            onValueChange = { next ->
                val digits = next.filter(Char::isDigit).take(5)
                portText = digits
                val parsed = digits.toIntOrNull()
                if (parsed != null && parsed in 1..65535) {
                    onUpdate { it.copy(port = parsed) }
                }
            },
            label = { Text(stringResource(R.string.setup_port_label)) },
            singleLine = true,
            isError = portErrorText != null,
            supportingText = portErrorText?.let { err -> { Text(err) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = setupTextFieldColors(),
        )

        // Sessions dir
        OutlinedTextField(
            value = ui.answers.sessionsDir,
            onValueChange = { v -> onUpdate { it.copy(sessionsDir = v) } },
            label = { Text(stringResource(R.string.setup_sessions_dir_label)) },
            singleLine = true,
            isError = ui.fieldErrors.sessionsDir != null,
            supportingText = ui.fieldErrors.sessionsDir?.let { err -> { Text(err) } },
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = setupTextFieldColors(),
        )

        // Projects file
        OutlinedTextField(
            value = ui.answers.projectsFile,
            onValueChange = { v -> onUpdate { it.copy(projectsFile = v) } },
            label = { Text(stringResource(R.string.setup_projects_file_label)) },
            singleLine = true,
            isError = ui.fieldErrors.projectsFile != null,
            supportingText = ui.fieldErrors.projectsFile?.let { err -> { Text(err) } },
            textStyle = typo.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = setupTextFieldColors(),
        )

        // Allow remote writes toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surfaceElevated)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.setup_allow_remote_writes),
                    style = typo.bodyMedium,
                    color = palette.foreground,
                )
                Text(
                    text = stringResource(R.string.setup_allow_remote_writes_body),
                    style = typo.bodySmall,
                    color = palette.mutedForeground,
                )
            }
            Switch(
                checked = ui.answers.allowRemoteWrites,
                onCheckedChange = { checked -> onUpdate { it.copy(allowRemoteWrites = checked) } },
            )
        }

        // Update channel pills
        Text(
            text = stringResource(R.string.setup_update_channel_label),
            style = typo.labelMedium,
            color = palette.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "stable" to stringResource(R.string.setup_channel_stable),
                "insider" to stringResource(R.string.setup_channel_insider),
                "nightly" to stringResource(R.string.setup_channel_nightly),
                null to stringResource(R.string.setup_channel_off),
            ).forEach { (value, label) ->
                val selected = ui.answers.updateChannel == value
                TroupeButton(
                    text = label,
                    onClick = { onUpdate { it.copy(updateChannel = value) } },
                    variant = if (selected) TroupeButtonVariant.Secondary else TroupeButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (ui.error != null) {
            Text(text = ui.error, style = typo.bodySmall, color = palette.destructive)
        }

        Spacer(modifier = Modifier.height(4.dp))

        TroupeButton(
            text = stringResource(
                if (ui.mode == SetupWizardMode.Setup) R.string.setup_apply_action else R.string.setup_save_and_restart,
            ),
            onClick = onApply,
            enabled = portValid && !ui.submitting,
            loading = ui.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BindOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.12f) else palette.surfaceElevated)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.borderSubtle,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = typo.labelLarge, color = palette.foreground)
        Text(text = subtitle, style = typo.bodySmall, color = palette.mutedForeground)
    }
}

@Composable
private fun setupTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TroupeTheme.palette.ring,
    unfocusedBorderColor = TroupeTheme.palette.border,
    focusedTextColor = TroupeTheme.palette.foreground,
    unfocusedTextColor = TroupeTheme.palette.foreground,
    cursorColor = TroupeTheme.palette.accent,
)
