package dev.troupe.android.terminal

import android.content.ClipboardManager
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.render.KeyBar
import dev.troupe.android.terminal.render.TerminalView
import dev.troupe.android.terminal.render.TroupeTerminalWebView
import dev.troupe.android.terminal.render.XtermWebViewRenderer

/** Standalone Terminal screen (kept for deep links & backward compatibility). */
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = hiltViewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TroupeTopBar(
            stringResource(R.string.terminal_title),
            onBack = onBack,
            connection = connection,
            onRetry = viewModel::retryConnection,
            viewOnly = viewOnly,
        )
    }) { padding ->
        TerminalPaneContent(
            viewModel = viewModel,
            fontSizePx = 14,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        )
    }
}

/**
 * Embeddable V2 Terminal pane for [dev.troupe.android.shell.MobileWorkspaceShellScreen].
 * Uses [TroupeTerminalWebView] (soft keyboard Enter fix #16) and live-syncs the active Troupe
 * theme's ANSI palette + `settings/app` terminal font size into xterm.js 6.1.
 */
@Composable
fun EmbeddedTerminalPane(
    serverId: String,
    terminalId: String,
    fontSizePx: Int,
    connections: TerminalConnections,
    cursors: TerminalCursorStore,
    onMetadataChanged: ((cwd: String?, branch: String?, exitCode: Int?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val storeOwner = remember(serverId, terminalId) {
        object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
    }
    DisposableEffect(storeOwner) {
        onDispose {
            storeOwner.viewModelStore.clear()
        }
    }
    val factory = remember(serverId, terminalId, connections, cursors) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return TerminalViewModel(
                    savedState = SavedStateHandle(mapOf("serverId" to serverId, "terminalId" to terminalId)),
                    connections = connections,
                    cursors = cursors,
                ) as T
            }
        }
    }
    val viewModel: TerminalViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = storeOwner,
        key = "term:$serverId:$terminalId",
        factory = factory,
    )
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    LaunchedEffect(metadata, onMetadataChanged) {
        if (metadata.cwd != null || metadata.gitBranch != null || metadata.lastExitCode != null) {
            onMetadataChanged?.invoke(metadata.cwd, metadata.gitBranch, metadata.lastExitCode)
        }
    }
    TerminalPaneContent(
        viewModel = viewModel,
        fontSizePx = fontSizePx,
        modifier = modifier,
    )
}

@Composable
private fun TerminalPaneContent(
    viewModel: TerminalViewModel,
    fontSizePx: Int,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val viewOnly by viewModel.viewOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = LocalTroupePalette.current

    // A WebView whose renderer process died is replaced by a fresh one, which the log is replayed into.
    // Key on viewModel too so switching Terminal tabs gives each terminal its own WebView.
    var webViews by remember(viewModel) { mutableIntStateOf(0) }
    val renderer = remember(viewModel, webViews) {
        XtermWebViewRenderer(
            webView = TroupeTerminalWebView(context),
            initialPalette = palette.terminal,
            initialFontSizePx = fontSizePx,
            onGone = { webViews++ },
        )
    }
    LaunchedEffect(renderer, palette.terminal) {
        renderer.updateTheme(palette.terminal)
    }
    LaunchedEffect(renderer, fontSizePx) {
        renderer.updateFontSize(fontSizePx)
    }
    DisposableEffect(renderer, viewModel) {
        viewModel.bind(renderer)
        onDispose {
            viewModel.unbind(renderer)
            renderer.destroy()
        }
    }
    val connecting = stringResource(R.string.terminal_connecting)
    Column(modifier.imePadding().fillMaxSize()) {
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
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.let { viewModel.paste(it.toString()) }
            },
        )
    }
}
