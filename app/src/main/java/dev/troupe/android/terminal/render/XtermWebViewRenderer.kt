package dev.troupe.android.terminal.render

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
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
 */
@SuppressLint("SetJavaScriptEnabled")
class XtermWebViewRenderer(val webView: WebView, private val palette: AnsiPalette = AnsiPalette.Termul) : TerminalRenderer {
    private val typed = Channel<String>(Channel.UNLIMITED)
    override val input: Flow<String> = typed.receiveAsFlow()

    private val _size = MutableStateFlow<TermSize?>(null)
    override val size: StateFlow<TermSize?> = _size.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = ByteArrayOutputStream() // guarded by lock
    private var flushScheduled = false // guarded by lock
    private var ready = false // guarded by lock
    private val flush = Runnable { flush() }

    init {
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
        }
        webView.addJavascriptInterface(Bridge(), "TroupeBridge")
        webView.loadUrl(PAGE)
    }

    override fun write(bytes: ByteArray) {
        synchronized(lock) {
            pending.write(bytes)
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

    /** Releases the WebView. The renderer is unusable afterwards. */
    fun destroy() {
        main.removeCallbacks(flush)
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
            main.post(flush)
        }
    }

    private companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/terminal/index.html"
        const val FRAME_MS = 16L
    }
}
