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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeButton
import dev.troupe.android.core.ui.TroupeButtonVariant
import dev.troupe.android.core.ui.TroupeIcons
import dev.troupe.android.core.ui.TroupeMark

/**
 * 1:1 port of Troupe's `WebTokenGateScreen.tsx`.
 * Shown when a gated Troupe Server rejects a missing, expired, or rotated bearer token.
 * Token text is kept in memory only (never `rememberSaveable`).
 */
@Composable
fun TokenGateScreen(
    serverHost: String,
    rejected: Boolean,
    checking: Boolean,
    onSubmitToken: (String) -> Unit,
    onSwitchServer: (() -> Unit)? = null,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val context = LocalContext.current

    // Never rememberSaveable: tokens must not enter savedInstanceState Bundle.
    var token by remember { mutableStateOf("") }
    var clipboardEmpty by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.surfaceElevated)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TroupeMark(modifier = Modifier.size(34.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.token_gate_title),
                    style = typo.titleLarge,
                    color = palette.foreground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.token_gate_subtitle, serverHost),
                    style = typo.bodyMedium,
                    color = palette.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }

            if (rejected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.destructive.copy(alpha = 0.12f))
                        .border(1.dp, palette.destructive.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.token_gate_rejected),
                        style = typo.bodySmall,
                        color = palette.destructive,
                    )
                }
            }

            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    clipboardEmpty = false
                },
                label = { Text(stringResource(R.string.pair_token_label)) },
                placeholder = { Text(stringResource(R.string.token_gate_placeholder)) },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = typo.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (token.isNotBlank()) onSubmitToken(token) },
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.ring,
                    unfocusedBorderColor = palette.border,
                    focusedTextColor = palette.foreground,
                    unfocusedTextColor = palette.foreground,
                    cursorColor = palette.accent,
                ),
            )

            if (clipboardEmpty) {
                Text(
                    text = stringResource(R.string.pair_paste_empty),
                    style = typo.bodySmall,
                    color = palette.mutedForeground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TroupeButton(
                    text = stringResource(R.string.token_gate_paste),
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        if (pasted.isNullOrEmpty()) {
                            clipboardEmpty = true
                        } else {
                            clipboardEmpty = false
                            token = pasted
                            onSubmitToken(pasted)
                        }
                    },
                    variant = TroupeButtonVariant.Outline,
                    icon = TroupeIcons.Clipboard,
                    enabled = !checking,
                    modifier = Modifier.weight(1f),
                )
                TroupeButton(
                    text = stringResource(R.string.token_gate_unlock),
                    onClick = { onSubmitToken(token) },
                    enabled = token.isNotBlank(),
                    loading = checking,
                    modifier = Modifier.weight(1f),
                )
            }

            if (onSwitchServer != null) {
                Spacer(modifier = Modifier.height(2.dp))
                TroupeButton(
                    text = stringResource(R.string.token_gate_switch_server),
                    onClick = onSwitchServer,
                    variant = TroupeButtonVariant.Ghost,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
