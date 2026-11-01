package dev.troupe.android.terminal

import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Claims and cursors on disk, through the app's real DataStore file and the test cipher. */
class TerminalCursorStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun claimAndCursorSurviveARestartEncrypted() {
        TestGraph(tmp.root).use { graph ->
            runBlocking {
                graph.cursors.putClaim("s1", "t1", "secret-claim")
                graph.cursors.putCursor("s1", "t1", TerminalCursor("e1", 41))
            }
            assertFalse(graph.prefsFile.readText(Charsets.ISO_8859_1).contains("secret-claim"))
        }
        TestGraph(tmp.root).use { graph ->
            val stored = runBlocking { graph.cursors.get("s1", "t1") }!!
            assertEquals("secret-claim", stored.claim)
            assertEquals(TerminalCursor("e1", 41), stored.cursor)
            assertFalse(stored.toString().contains("secret-claim"))
        }
    }

    @Test fun aReissuedClaimKeepsTheCursorAndACursorNeverCreatesAClaim() = TestGraph(tmp.root).use { graph ->
        runBlocking {
            graph.cursors.putClaim("s1", "t1", "old")
            graph.cursors.putCursor("s1", "t1", TerminalCursor("e1", 5))
            graph.cursors.putClaim("s1", "t1", "new")
            graph.cursors.putCursor("s1", "t2", TerminalCursor("e1", 5))

            assertEquals("new", graph.cursors.get("s1", "t1")!!.claim)
            assertEquals(TerminalCursor("e1", 5), graph.cursors.get("s1", "t1")!!.cursor)
            assertNull(graph.cursors.get("s1", "t2"))
        }
    }

    @Test fun recordsArePerServerAndRemovable() = TestGraph(tmp.root).use { graph ->
        runBlocking {
            graph.cursors.putClaim("s1", "t1", "a")
            graph.cursors.putClaim("s2", "t1", "b")
            graph.cursors.remove("s1", "t1")

            assertNull(graph.cursors.get("s1", "t1"))
            assertEquals("b", graph.cursors.get("s2", "t1")!!.claim)
        }
    }
}
