package dev.troupe.android.terminal.render

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

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
                // keep Enter and Space for whatever it had focused. Move Compose focus along and
                // ensure xterm's hidden textarea also holds DOM focus.
                // Inside Compose a tap does not always give the WebView View focus, and without it
                // neither the soft keyboard nor a hardware key reaches xterm. The touch itself is
                // not consumed, so selection and scrolling work as before.
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!view.hasFocus()) view.requestFocus()
                            // A tap only: not the end of a scroll, nor of a long press that selects.
                            val moved = abs(event.x - downX) > slop || abs(event.y - downY) > slop
                            val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                            if (!moved && !held) {
                                view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
                            }
                        }
                    }
                    false
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        focus.requestFocus()
                        renderer.focusDom()
                    }
                }
            }
        },
        modifier = modifier.focusRequester(focus),
    )
}
