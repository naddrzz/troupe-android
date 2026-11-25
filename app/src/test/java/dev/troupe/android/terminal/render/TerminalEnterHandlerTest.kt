package dev.troupe.android.terminal.render

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers every soft-keyboard, Pinyin IME, and hardware/`adb input keyevent` Enter path (#16). */
class TerminalEnterHandlerTest {

    @Test fun editorActionsCommitComposingTextBeforeSendingEnter() {
        val actions = listOf(
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        for (action in actions) {
            val events = mutableListOf<String>()
            val handled = TerminalEnterHandler.handleEditorAction(
                editorAction = action,
                finishComposing = { events += "finish" },
                onEnter = { events += "enter" },
            )
            assertTrue("action $action should be handled", handled)
            assertEquals(listOf("finish", "enter"), events)
        }

        val ignored = mutableListOf<String>()
        assertFalse(
            TerminalEnterHandler.handleEditorAction(
                editorAction = EditorInfo.IME_ACTION_NONE,
                finishComposing = { ignored += "finish" },
                onEnter = { ignored += "enter" },
            ),
        )
        assertTrue(ignored.isEmpty())
    }

    @Test fun keyDownAndUpForEnterDeliverOnceWithoutDoubleSubmit() {
        for (keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            val events = mutableListOf<String>()
            assertTrue(
                TerminalEnterHandler.handleKeyEvent(
                    action = KeyEvent.ACTION_DOWN,
                    keyCode = keyCode,
                    repeatCount = 0,
                    isAltPressed = false,
                    isCtrlPressed = false,
                    isMetaPressed = false,
                    finishComposing = { events += "finish" },
                    onEnter = { events += "enter" },
                ),
            )
            assertTrue(
                TerminalEnterHandler.handleKeyEvent(
                    action = KeyEvent.ACTION_UP,
                    keyCode = keyCode,
                    repeatCount = 0,
                    isAltPressed = false,
                    isCtrlPressed = false,
                    isMetaPressed = false,
                    finishComposing = { events += "finish" },
                    onEnter = { events += "enter" },
                ),
            )
            assertEquals(listOf("finish", "enter"), events)
        }
    }

    @Test fun modifiedEnterAndNonEnterKeysPassThrough() {
        val events = mutableListOf<String>()
        assertFalse(
            TerminalEnterHandler.handleKeyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                isAltPressed = true,
                isCtrlPressed = false,
                isMetaPressed = false,
                finishComposing = { events += "finish" },
                onEnter = { events += "enter" },
            ),
        )
        assertFalse(
            TerminalEnterHandler.handleKeyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                isAltPressed = false,
                isCtrlPressed = true,
                isMetaPressed = false,
                finishComposing = { events += "finish" },
                onEnter = { events += "enter" },
            ),
        )
        assertFalse(
            TerminalEnterHandler.handleKeyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                repeatCount = 0,
                isAltPressed = false,
                isCtrlPressed = false,
                isMetaPressed = false,
                finishComposing = { events += "finish" },
                onEnter = { events += "enter" },
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test fun actionMultipleDeliversEnterAtLeastOnce() {
        val events = mutableListOf<String>()
        assertTrue(
            TerminalEnterHandler.handleKeyEvent(
                action = KeyEvent.ACTION_MULTIPLE,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                isAltPressed = false,
                isCtrlPressed = false,
                isMetaPressed = false,
                finishComposing = { events += "finish" },
                onEnter = { events += "enter" },
            ),
        )
        assertEquals(listOf("finish", "enter"), events)
    }

    @Test fun commitTextHandlesSingleNewlineMixedTextAndMultipleLines() {
        // Pure newline: finish active composing span first, then send Enter.
        for (nl in listOf("\n", "\r", "\r\n")) {
            val events = mutableListOf<String>()
            assertTrue(
                TerminalEnterHandler.handleCommitText(
                    text = nl,
                    commitChunk = { events += "chunk:$it" },
                    finishComposing = { events += "finish" },
                    onEnter = { events += "enter" },
                ),
            )
            assertEquals(listOf("finish", "enter"), events)
        }

        // Mixed word + newline (Gboard/Pinyin commit): commit text first, then Enter.
        val mixed = mutableListOf<String>()
        assertTrue(
            TerminalEnterHandler.handleCommitText(
                text = "echo ok\n",
                commitChunk = { mixed += "chunk:$it" },
                finishComposing = { mixed += "finish" },
                onEnter = { mixed += "enter" },
            ),
        )
        assertEquals(listOf("chunk:echo ok", "enter"), mixed)

        // Multi-line text with CRLF.
        val multi = mutableListOf<String>()
        assertTrue(
            TerminalEnterHandler.handleCommitText(
                text = "ls\r\npwd\n",
                commitChunk = { multi += "chunk:$it" },
                finishComposing = { multi += "finish" },
                onEnter = { multi += "enter" },
            ),
        )
        assertEquals(listOf("chunk:ls", "enter", "chunk:pwd", "enter"), multi)

        // Regular text without newline delegates to super.commitText.
        val plain = mutableListOf<String>()
        assertFalse(
            TerminalEnterHandler.handleCommitText(
                text = "hello",
                commitChunk = { plain += "chunk:$it" },
                finishComposing = { plain += "finish" },
                onEnter = { plain += "enter" },
            ),
        )
        assertTrue(plain.isEmpty())
    }
}
