package dev.troupe.android.pairing

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar
import kotlinx.coroutines.launch

private enum class PairTab(@StringRes val label: Int) {
    Scan(R.string.pair_tab_scan),
    Paste(R.string.pair_tab_paste),
    Manual(R.string.pair_tab_manual),
}

/** Pairing by scanning the desktop QR code, pasting its link, or typing address and token. */
@Composable
fun PairScreen(
    onPaired: (serverId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: PairViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        (state as? PairUiState.Paired)?.let { onPaired(it.serverId) }
    }
    var tab by rememberSaveable { mutableIntStateOf(PairTab.Scan.ordinal) }
    val checking = state == PairUiState.Checking

    Scaffold(topBar = { TroupeTopBar(stringResource(R.string.pair_title), onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                PairTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry.ordinal,
                        onClick = { tab = entry.ordinal },
                        text = { Text(stringResource(entry.label)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (PairTab.entries[tab]) {
                    PairTab.Scan -> QrScanner(onResult = viewModel::onQrScanned, onUsePaste = { tab = PairTab.Paste.ordinal })
                    PairTab.Paste -> PasteTab(checking, viewModel::onLinkPasted)
                    PairTab.Manual -> ManualTab(checking, viewModel::onManual)
                }
                PairStatus(state)
            }
        }
    }
}

@Composable
private fun PasteTab(checking: Boolean, onPair: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // The link carries the token, so it is not saved into the instance state bundle.
    var link by remember { mutableStateOf("") }
    var clipboardEmpty by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = link,
        onValueChange = { link = it; clipboardEmpty = false },
        label = { Text(stringResource(R.string.pair_paste_label)) },
        placeholder = { Text(stringResource(R.string.pair_paste_hint)) },
        minLines = 3,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (clipboardEmpty) {
        Text(
            stringResource(R.string.pair_paste_empty),
            color = LocalTroupePalette.current.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                val text = clipboard.getClipEntry()?.clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                if (text.isNullOrBlank()) {
                    clipboardEmpty = true
                } else {
                    link = text
                    onPair(text)
                }
            }
        },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.pair_paste_from_clipboard))
    }
    Button(
        onClick = { onPair(link) },
        enabled = !checking && link.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.pair_submit))
    }
}

@Composable
private fun ManualTab(checking: Boolean, onPair: (url: String, token: String, name: String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // The token is not saved into the instance state bundle.
    var token by remember { mutableStateOf("") }
    val submit = { onPair(address, token, name) }

    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(stringResource(R.string.pair_address_label)) },
        placeholder = { Text(stringResource(R.string.pair_address_hint)) },
        singleLine = true,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.pair_token_label)) },
        singleLine = true,
        enabled = !checking,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.pair_name_label)) },
        placeholder = { Text(stringResource(R.string.pair_name_hint)) },
        singleLine = true,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = submit,
        enabled = !checking && address.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.pair_submit))
    }
}

/** The check in progress or why pairing failed, whichever tab started it. */
@Composable
private fun PairStatus(state: PairUiState) {
    (state as? PairUiState.Error)?.let { error ->
        Text(
            stringResource(error.message, error.arg.orEmpty()),
            color = LocalTroupePalette.current.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state == PairUiState.Checking) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.pair_checking), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
