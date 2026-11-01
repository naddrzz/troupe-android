package dev.troupe.android.terminal.render

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Hosts the renderer's WebView in Compose. */
@Composable
fun TerminalView(renderer: XtermWebViewRenderer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { renderer.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = modifier,
    )
}
