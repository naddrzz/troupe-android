package dev.troupe.android.terminal

import android.content.ClipboardManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar
import dev.troupe.android.terminal.render.KeyBar
import dev.troupe.android.terminal.render.TerminalView
import dev.troupe.android.terminal.render.XtermWebViewRenderer

/** One live Terminal: xterm.js output above the key bar, both lifted by the soft keyboard. */
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = LocalTroupePalette.current
    // A WebView whose renderer process died is replaced by a fresh one, which the log is replayed into.
    var webViews by remember { mutableIntStateOf(0) }
    val renderer = remember(webViews) { XtermWebViewRenderer(WebView(context), onGone = { webViews++ }) }
    DisposableEffect(renderer) {
        viewModel.bind(renderer)
        onDispose {
            viewModel.unbind(renderer)
            renderer.destroy()
        }
    }
    val connecting = stringResource(R.string.terminal_connecting)
    Scaffold(topBar = { TroupeTopBar(
            stringResource(R.string.terminal_title),
            onBack = onBack,
            connection = connection,
            onRetry = viewModel::retryConnection,
            viewOnly = viewOnly,
        ) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            when (val s = state) {
                TerminalUi.Connecting -> LinearProgressIndicator(
                    Modifier.fillMaxWidth().semantics { contentDescription = connecting },
                )
                is TerminalUi.Failed -> Text(
                    stringResource(s.message, s.arg.orEmpty()),
                    color = palette.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 16.dp, vertical = 12.dp),
                )
                is TerminalUi.Exited -> Text(
                    s.exitCode?.let { stringResource(R.string.terminal_exited, it) } ?: stringResource(R.string.terminal_exited_signal),
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(palette.elevated).padding(horizontal = 16.dp, vertical = 12.dp),
                )
                TerminalUi.Live -> Unit
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(palette.background), contentAlignment = Alignment.TopStart) {
                key(renderer) { TerminalView(renderer, Modifier.fillMaxSize()) }
            }
            KeyBar(
                enabled = state == TerminalUi.Live && connection == ConnectionState.Connected && !viewOnly,
                onKey = viewModel::onKey,
                onPaste = {
                    // Plain text only: coerceToText would read a URI-backed clip on the main thread.
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.let { viewModel.paste(it.toString()) }
                },
                modifier = Modifier.background(palette.card),
            )
        }
    }
}
