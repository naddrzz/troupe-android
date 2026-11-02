package dev.troupe.android.terminal.render

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.viewinterop.AndroidView

/** Hosts the renderer's WebView in Compose. */
@Composable
fun TerminalView(renderer: XtermWebViewRenderer, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    AndroidView(
        factory = {
            renderer.webView.apply {
                (parent as? ViewGroup)?.removeView(this)
                // AndroidView defaults to WRAP_CONTENT, which makes a WebView size its page to the
                // content: the page's 100% height collapses and xterm fits a single row.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // A tap focuses the WebView but not Compose's focus target for it, so Compose would
                // keep Enter and Space for whatever it had focused. Move Compose focus along.
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focus.requestFocus() }
            }
        },
        modifier = modifier.focusRequester(focus),
    )
}
