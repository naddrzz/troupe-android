package dev.troupe.android.terminal.render

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The real xterm.js renderer in [TroupeTerminalWebView] on the device: raw UTF-8 split across
 * writes, typed input, and soft-keyboard / hardware Enter delivery (#16).
 */
@RunWith(AndroidJUnit4::class)
class XtermWebViewRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: TroupeTerminalWebView
    private lateinit var renderer: XtermWebViewRenderer

    @Before fun setUp() {
        instrumentation.runOnMainSync {
            webView = TroupeTerminalWebView(instrumentation.targetContext)
            val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
            webView.measure(width, height)
            webView.layout(0, 0, 1080, 1200)
            renderer = XtermWebViewRenderer(webView)
        }
        awaitJs("typeof troupe", "\"object\"")
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { renderer.destroy() }
    }

    @Test fun eAcuteSplitAcrossTwoWritesRendersAsOneCharacter() {
        renderer.write(byteArrayOf(0xC3.toByte()))
        Thread.sleep(300) // past the 16 ms coalescing window: the two bytes reach xterm in separate writes
        assertEquals("\"\"", js(FIRST_LINE))

        renderer.write(byteArrayOf(0xA9.toByte()))

        awaitJs(FIRST_LINE, "\"é\"")
    }

    @Test fun typedTextReachesTheInputStream() {
        js("troupe.term.input('ls', true)")

        val typed = runBlocking { withTimeout(5_000) { renderer.input.first() } }

        assertEquals("ls", typed)
    }

    @Test fun enterFromDispatchKeyEventReachesTheInputStream() {
        instrumentation.runOnMainSync {
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        val entered = runBlocking { withTimeout(5_000) { renderer.input.first() } }

        assertEquals("\r", entered)
    }

    @Test fun enterFromSoftKeyboardEditorActionReachesTheInputStream() {
        instrumentation.runOnMainSync {
            val ic = inputConnection()
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }

        val entered = runBlocking { withTimeout(5_000) { renderer.input.first() } }

        assertEquals("\r", entered)
    }

    @Test fun enterFromSoftKeyboardKeyEventReachesTheInputStream() {
        instrumentation.runOnMainSync {
            val ic = inputConnection()
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        val entered = runBlocking { withTimeout(5_000) { renderer.input.first() } }

        assertEquals("\r", entered)
    }

    @Test fun enterFromSoftKeyboardCommitTextReachesTheInputStream() {
        instrumentation.runOnMainSync {
            val ic = inputConnection()
            ic.commitText("\n", 1)
        }

        val entered = runBlocking { withTimeout(5_000) { renderer.input.first() } }

        assertEquals("\r", entered)
    }

    private fun inputConnection(): InputConnection =
        webView.onCreateInputConnection(EditorInfo())
            ?: webView.wrapInputConnection(BaseInputConnection(webView, false))

    private fun js(script: String): String {
        val result = CompletableFuture<String>()
        instrumentation.runOnMainSync { renderer.webView.evaluateJavascript(script) { result.complete(it) } }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun awaitJs(script: String, expected: String) {
        val deadline = System.currentTimeMillis() + 15_000
        var last = js(script)
        while (last != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            last = js(script)
        }
        assertEquals(expected, last)
    }

    private companion object {
        const val FIRST_LINE = "troupe.term.buffer.active.getLine(0).translateToString(true)"
    }
}
