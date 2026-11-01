package dev.troupe.android.terminal.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** The key bar sends the exact sequences a desktop terminal sends for these keys. */
class KeyBarTest {
    @Test fun everyKeySendsItsExactSequence() {
        assertEquals(
            mapOf(
                TerminalKey.Esc to "\u001b",
                TerminalKey.Tab to "\t",
                TerminalKey.CtrlC to "\u0003",
                TerminalKey.Up to "\u001b[A",
                TerminalKey.Down to "\u001b[B",
                TerminalKey.Right to "\u001b[C",
                TerminalKey.Left to "\u001b[D",
                TerminalKey.PageUp to "\u001b[5~",
                TerminalKey.PageDown to "\u001b[6~",
            ),
            TerminalKey.entries.associateWith { it.sequence },
        )
    }
}
