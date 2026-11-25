package dev.troupe.android.terminal.render

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * Normalizes Android soft-keyboard, Pinyin IME, and hardware/`adb input keyevent` Enter delivery
 * for [TroupeTerminalWebView] (fixes #16). Pure primitive inputs allow exhaustive JVM unit tests.
 */
internal object TerminalEnterHandler {

    fun isTerminalEnterAction(editorAction: Int): Boolean = when (editorAction) {
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_PREVIOUS,
        EditorInfo.IME_ACTION_UNSPECIFIED -> true
        else -> false
    }

    fun isEnterKeyCode(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun isUnmodifiedEnterKey(
        keyCode: Int,
        isAltPressed: Boolean,
        isCtrlPressed: Boolean,
        isMetaPressed: Boolean,
    ): Boolean = isEnterKeyCode(keyCode) && !isAltPressed && !isCtrlPressed && !isMetaPressed

    @Suppress("DEPRECATION")
    fun handleKeyEvent(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        isAltPressed: Boolean,
        isCtrlPressed: Boolean,
        isMetaPressed: Boolean,
        finishComposing: () -> Unit,
        onEnter: () -> Unit,
    ): Boolean {
        if (!isUnmodifiedEnterKey(keyCode, isAltPressed, isCtrlPressed, isMetaPressed)) return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                finishComposing()
                onEnter()
                true
            }
            KeyEvent.ACTION_UP -> true
            KeyEvent.ACTION_MULTIPLE -> {
                finishComposing()
                repeat(maxOf(1, repeatCount)) { onEnter() }
                true
            }
            else -> false
        }
    }

    fun handleEditorAction(
        editorAction: Int,
        finishComposing: () -> Unit,
        onEnter: () -> Unit,
    ): Boolean {
        if (!isTerminalEnterAction(editorAction)) return false
        finishComposing()
        onEnter()
        return true
    }

    /**
     * Handles `InputConnection.commitText` when [text] contains a newline (`\n`, `\r`, or `\r\n`),
     * including multi-character IME commits such as `"echo hi\n"`. Returns `false` when [text] has
     * no newline so callers delegate directly to `super.commitText`.
     */
    fun handleCommitText(
        text: CharSequence?,
        commitChunk: (String) -> Unit,
        finishComposing: () -> Unit,
        onEnter: () -> Unit,
    ): Boolean {
        if (text == null) return false
        val raw = text.toString()
        if ('\n' !in raw && '\r' !in raw) return false

        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val segments = normalized.split('\n')
        if (segments.first().isEmpty()) {
            finishComposing()
        }
        segments.forEachIndexed { index, segment ->
            if (segment.isNotEmpty()) {
                commitChunk(segment)
            }
            if (index < segments.lastIndex) {
                onEnter()
            }
        }
        return true
    }
}
