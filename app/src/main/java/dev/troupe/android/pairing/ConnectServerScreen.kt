package dev.troupe.android.pairing

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMark
import dev.troupe.android.core.ui.TroupeMobileSheet

/**
 * Troupe V2 universal Connect / Paste-Link screen.
 * Launching with no paired servers lands directly here: paste any Troupe Server link
 * (`https://.../#token=...`, `http://192.168.1.5:8080`, `?setup=...`, or console output)
 * and tap once to connect. QR scanning and manual entry open as native bottom sheets.
 */
@Composable
fun ConnectServerScreen(
    viewModel: ConnectServerViewModel,
    canNavigateBack: Boolean,
    onBack: () -> Unit,
    onConnected: (serverId: String, chatSessionId: String?) -> Unit,
    onNeedsSetup: (address: ServerAddress, setupToken: String?) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val context = LocalContext.current

    // Keep pasted link in memory only (may carry #token= or ?setup=).
    var linkText by remember { mutableStateOf("") }
    var replacementHost by remember { mutableStateOf("") }
    var clipboardEmpty by remember { mutableStateOf(false) }
    var showQrSheet by remember { mutableStateOf(false) }
    var showManualSheet by remember { mutableStateOf(false) }

    LaunchedEffect(ui) {
        when (val state = ui) {
            is ConnectUiState.Connected -> {
                onConnected(state.serverId, state.chatSessionId)
                viewModel.reset()
            }
            is ConnectUiState.NeedsSetup -> {
                onNeedsSetup(state.address, state.setupToken)
                viewModel.reset()
            }
            else -> Unit
        }
    }

    // If server responded 401/unauthorized, switch seamlessly into the 1:1 TokenGateScreen.
    val tokenGate = ui as? ConnectUiState.NeedsToken
    if (tokenGate != null) {
        TokenGateScreen(
            serverHost = tokenGate.address.host,
            rejected = tokenGate.rejected,
            checking = tokenGate.checking,
            onSubmitToken = viewModel::submitGateToken,
            onSwitchServer = viewModel::reset,
        )
        return
    }

    val checking = ui is ConnectUiState.Checking
    val needsHost = ui as? ConnectUiState.NeedsHost

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (canNavigateBack) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TroupeIconButton(
                    icon = TroupeIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TroupeMark(modifier = Modifier.size(44.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.connect_title),
                        style = typo.displayLarge,
                        color = palette.foreground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.connect_subtitle),
                        style = typo.bodyMedium,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.Center,
                    )
                }

                // Primary paste-link card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.surfaceElevated)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = {
                            linkText = it
                            clipboardEmpty = false
                            if (ui is ConnectUiState.Error || ui is ConnectUiState.NeedsHost) {
                                viewModel.reset()
                            }
                        },
                        label = { Text(stringResource(R.string.connect_link_label)) },
                        placeholder = { Text(stringResource(R.string.connect_link_hint)) },
                        singleLine = false,
                        maxLines = 3,
                        enabled = !checking,
                        textStyle = typo.bodyLarge,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { if (linkText.isNotBlank()) viewModel.connectLink(linkText) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = connectFieldColors(),
                    )

                    if (needsHost != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.warningTint)
                                .border(1.dp, palette.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.connect_wildcard_notice),
                                style = typo.bodySmall,
                                color = palette.foreground,
                            )
                            OutlinedTextField(
                                value = replacementHost,
                                onValueChange = { replacementHost = it },
                                label = { Text(stringResource(R.string.connect_wildcard_host_label)) },
                                placeholder = { Text(stringResource(R.string.connect_wildcard_host_hint)) },
                                singleLine = true,
                                textStyle = typo.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                colors = connectFieldColors(),
                            )
                            TroupeButton(
                                text = stringResource(R.string.connect_continue),
                                onClick = { viewModel.submitReplacementHost(replacementHost) },
                                enabled = replacementHost.isNotBlank(),
                                loading = checking,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (clipboardEmpty) {
                        Text(
                            text = stringResource(R.string.pair_paste_empty),
                            style = typo.bodySmall,
                            color = palette.mutedForeground,
                        )
                    }

                    val error = ui as? ConnectUiState.Error
                    if (error != null) {
                        val message = if (error.arg != null) {
                            stringResource(error.message, error.arg)
                        } else {
                            stringResource(error.message)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.destructive.copy(alpha = 0.12f))
                                .border(1.dp, palette.destructive.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = message,
                                style = typo.bodySmall,
                                color = palette.destructive,
                            )
                        }
                    }

                    if (needsHost == null) {
                        if (linkText.isBlank()) {
                            TroupeButton(
                                text = stringResource(R.string.connect_paste_and_connect),
                                onClick = {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                    if (pasted.isNullOrEmpty()) {
                                        clipboardEmpty = true
                                    } else {
                                        clipboardEmpty = false
                                        linkText = pasted
                                        viewModel.connectLink(pasted)
                                    }
                                },
                                icon = TroupeIcons.Clipboard,
                                loading = checking,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            TroupeButton(
                                text = stringResource(R.string.connect_submit),
                                onClick = { viewModel.connectLink(linkText) },
                                loading = checking,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Secondary actions: Scan QR or Manual entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TroupeButton(
                        text = stringResource(R.string.connect_scan_qr),
                        onClick = { showQrSheet = true },
                        variant = TroupeButtonVariant.Outline,
                        icon = TroupeIcons.QrCode,
                        enabled = !checking,
                        modifier = Modifier.weight(1f),
                    )
                    TroupeButton(
                        text = stringResource(R.string.connect_manual),
                        onClick = { showManualSheet = true },
                        variant = TroupeButtonVariant.Outline,
                        icon = TroupeIcons.Server,
                        enabled = !checking,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showQrSheet) {
        TroupeMobileSheet(
            title = stringResource(R.string.connect_scan_qr),
            onDismiss = { showQrSheet = false },
        ) {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                QrScanner(
                    onResult = { scanned ->
                        showQrSheet = false
                        linkText = scanned
                        viewModel.connectLink(scanned)
                    },
                    onUsePaste = { showQrSheet = false },
                )
            }
        }
    }

    if (showManualSheet) {
        ManualConnectSheet(
            checking = checking,
            onDismiss = { showManualSheet = false },
            onSubmit = { url, token, name ->
                showManualSheet = false
                viewModel.connectManual(url, token, name)
            },
        )
    }
}

@Composable
private fun ManualConnectSheet(
    checking: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (url: String, token: String, name: String) -> Unit,
) {
    val typo = TroupeTheme.typography
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    TroupeMobileSheet(
        title = stringResource(R.string.connect_manual),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.pair_address_label)) },
                placeholder = { Text(stringResource(R.string.pair_address_hint)) },
                singleLine = true,
                textStyle = typo.bodyLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                colors = connectFieldColors(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.connect_token_optional_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = typo.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                colors = connectFieldColors(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.pair_name_label)) },
                placeholder = { Text(stringResource(R.string.pair_name_hint)) },
                singleLine = true,
                textStyle = typo.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = connectFieldColors(),
            )
            TroupeButton(
                text = stringResource(R.string.connect_submit),
                onClick = { onSubmit(address, token, name) },
                enabled = address.isNotBlank(),
                loading = checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun connectFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TroupeTheme.palette.ring,
    unfocusedBorderColor = TroupeTheme.palette.border,
    focusedTextColor = TroupeTheme.palette.foreground,
    unfocusedTextColor = TroupeTheme.palette.foreground,
    cursorColor = TroupeTheme.palette.accent,
)
