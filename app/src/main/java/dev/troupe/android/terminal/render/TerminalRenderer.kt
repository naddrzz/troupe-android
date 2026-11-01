package dev.troupe.android.terminal.render

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A Terminal's size in character cells. */
data class TermSize(val cols: Int, val rows: Int)

/**
 * Draws a Terminal and reports what the user types (ADR-0001). The real one is xterm.js in a
 * WebView ([XtermWebViewRenderer]); tests use a recording one.
 */
interface TerminalRenderer {
    /** Text the user typed into the Terminal, already encoded as terminal input sequences. */
    val input: Flow<String>

    /** The current size in cells, or null until the renderer has measured itself. */
    val size: StateFlow<TermSize?>

    /** Writes raw output bytes. They are never decoded here, so UTF-8 split across writes stays intact. */
    fun write(bytes: ByteArray)

    /** Clears the screen and scrollback. */
    fun reset()

    /** Gives the Terminal input focus. */
    fun focus()
}
