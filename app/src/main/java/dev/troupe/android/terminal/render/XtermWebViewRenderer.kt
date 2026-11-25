package dev.troupe.android.terminal.render

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * xterm.js in a WebView (ADR-0001). The page and its scripts and font are bundled assets served
 * by [WebViewAssetLoader]; every other request is refused and navigation is blocked.
 *
 * Output is buffered and flushed to xterm at most once per frame (16 ms), as Base64 that the page
 * turns back into bytes for `term.write(Uint8Array)`; it is never decoded to text here.
 * JS calls back through one `TroupeBridge` object: `onData`, `onResize`, `onReady` and `theme`.
 * Create and use it on the main thread; [write] may be called from any thread.
 *
 * If the WebView's renderer process dies, this renderer is dead too: [onGone] runs (on the main
 * thread) so the screen can replace it, and the app survives.
 */
@SuppressLint("SetJavaScriptEnabled")
class XtermWebViewRenderer(
    val webView: WebView,
    initialPalette: AnsiPalette = AnsiPalette.Termul,
    initialFontSizePx: Int = 14,
    private val onGone: () -> Unit = {},
) : TerminalRenderer {
    private val typed = Channel<String>(Channel.UNLIMITED)
    override val input: Flow<String> = typed.receiveAsFlow()

    private val _size = MutableStateFlow<TermSize?>(null)
    override val size: StateFlow<TermSize?> = _size.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = ByteArrayOutputStream() // guarded by lock
    private var flushScheduled = false // guarded by lock
    private var ready = false // guarded by lock
    @Volatile private var palette: AnsiPalette = initialPalette
    @Volatile private var currentFontSizePx: Int = initialFontSizePx
    private val flush = Runnable { flush() }

    init {
        if (webView is TroupeTerminalWebView) {
            webView.onEnterKey = { sendEnter() }
        }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            .build()
        // Debug builds only: lets chrome://inspect attach to the terminal page.
        if (webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        webView.setBackgroundColor(android.graphics.Color.parseColor(palette.background))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assets.shouldInterceptRequest(request.url) ?: refused()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                synchronized(lock) { ready = false }
                onGone()
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "TroupeBridge")
        webView.loadUrl(PAGE)
    }

    /** Dynamically updates xterm's ANSI palette whenever the user switches Troupe themes. */
    fun updateTheme(next: AnsiPalette) {
        if (palette == next) return
        palette = next
        val json = TroupeJson.encodeToString(AnsiPalette.serializer(), next)
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        main.post {
            webView.setBackgroundColor(android.graphics.Color.parseColor(next.background))
            if (isReady()) webView.evaluateJavascript("troupe.setTheme('$escaped')", null)
        }
    }

    /** Dynamically updates xterm's font size from `settings/app` (`terminalFontSize`). */
    fun updateFontSize(fontSizePx: Int) {
        currentFontSizePx = fontSizePx
        main.post {
            if (isReady()) webView.evaluateJavascript("troupe.setFontSize($fontSizePx)", null)
        }
    }

    override fun write(bytes: ByteArray) {
        synchronized(lock) {
            pending.write(bytes)
            // A page that never becomes ready must not hold output without bound: keep the newest.
            if (pending.size() > MAX_PENDING) {
                val kept = pending.toByteArray().copyOfRange(pending.size() - MAX_PENDING, pending.size())
                pending.reset()
                pending.write(kept)
            }
            if (flushScheduled) return
            flushScheduled = true
        }
        main.postDelayed(flush, FRAME_MS)
    }

    override fun reset() {
        synchronized(lock) { pending.reset() }
        main.post { if (isReady()) webView.evaluateJavascript("troupe.reset()", null) }
    }

    override fun focus() {
        main.post {
            webView.requestFocus()
            if (isReady()) webView.evaluateJavascript("troupe.focus()", null)
        }
    }

    /** Moves DOM focus to xterm's textarea when the Android WebView already holds View focus. */
    fun focusDom() {
        main.post {
            if (isReady()) webView.evaluateJavascript("troupe.focus()", null)
        }
    }

    private fun sendEnter() {
        main.post {
            if (isReady()) {
                webView.evaluateJavascript("troupe.sendEnter()", null)
            } else {
                typed.trySend("\r")
            }
        }
    }

    /** Releases the WebView. The renderer is unusable afterwards. */
    fun destroy() {
        main.removeCallbacks(flush)
        if (webView is TroupeTerminalWebView) webView.onEnterKey = null
        typed.close()
        webView.removeJavascriptInterface("TroupeBridge")
        webView.destroy()
    }

    private fun isReady() = synchronized(lock) { ready }

    private fun flush() {
        val bytes = synchronized(lock) {
            flushScheduled = false
            // Not loaded yet: keep the bytes; onReady flushes them.
            if (!ready || pending.size() == 0) return
            pending.toByteArray().also { pending.reset() }
        }
        webView.evaluateJavascript("troupe.write('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')", null)
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Called by the page on a WebView binder thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun theme(): String = TroupeJson.encodeToString(AnsiPalette.serializer(), palette)

        @JavascriptInterface
        fun onData(data: String) {
            typed.trySend(data)
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) {
            if (cols > 0 && rows > 0) _size.value = TermSize(cols, rows)
        }

        @JavascriptInterface
        fun onReady() {
            synchronized(lock) { ready = true }
            main.post {
                val size = currentFontSizePx
                if (size != 14) {
                    webView.evaluateJavascript("troupe.setFontSize($size)", null)
                }
                flush()
            }
        }
    }

    private companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/terminal/index.html"
        const val FRAME_MS = 16L
        const val MAX_PENDING = 4 shl 20
    }
}

/**
 * Custom [WebView] fixing troupe-android #16 (Android soft keyboard, Pinyin IME, and hardware/`adb
 * input keyevent` Enter dropped by xterm's hidden `<textarea>`). Intercepts Enter at both
 * [dispatchKeyEvent] and the [android.view.inputmethod.InputConnection] boundary, commits any
 * active composing span first, and forwards `\r` through xterm on the Blink main thread.
 */
class TroupeTerminalWebView(context: android.content.Context) : WebView(context) {
    var onEnterKey: (() -> Unit)? = null
    private var activeInputConnection: java.lang.ref.WeakReference<android.view.inputmethod.InputConnection>? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = TerminalEnterHandler.handleKeyEvent(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            isAltPressed = event.isAltPressed,
            isCtrlPressed = event.isCtrlPressed,
            isMetaPressed = event.isMetaPressed,
            finishComposing = { activeInputConnection?.get()?.finishComposingText() },
            onEnter = { onEnterKey?.invoke() },
        )
        return handled || super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = (outAttrs.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION.inv()) or
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return wrapInputConnection(base).also { activeInputConnection = java.lang.ref.WeakReference(it) }
    }

    internal fun wrapInputConnection(base: android.view.inputmethod.InputConnection): android.view.inputmethod.InputConnection =
        object : android.view.inputmethod.InputConnectionWrapper(base, true) {
            override fun performEditorAction(editorAction: Int): Boolean {
                val handled = TerminalEnterHandler.handleEditorAction(
                    editorAction = editorAction,
                    finishComposing = { finishComposingText() },
                    onEnter = { onEnterKey?.invoke() },
                )
                return handled || super.performEditorAction(editorAction)
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                val handled = TerminalEnterHandler.handleKeyEvent(
                    action = event.action,
                    keyCode = event.keyCode,
                    repeatCount = event.repeatCount,
                    isAltPressed = event.isAltPressed,
                    isCtrlPressed = event.isCtrlPressed,
                    isMetaPressed = event.isMetaPressed,
                    finishComposing = { finishComposingText() },
                    onEnter = { onEnterKey?.invoke() },
                )
                return handled || super.sendKeyEvent(event)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val handled = TerminalEnterHandler.handleCommitText(
                    text = text,
                    commitChunk = { chunk -> super.commitText(chunk, 1) },
                    finishComposing = { finishComposingText() },
                    onEnter = { onEnterKey?.invoke() },
                )
                return handled || super.commitText(text, newCursorPosition)
            }
        }
}
