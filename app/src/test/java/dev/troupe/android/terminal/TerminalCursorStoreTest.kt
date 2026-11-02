package dev.troupe.android.terminal

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import dev.troupe.android.servers.TokenCipher
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.XorTokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

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

    @Test fun aRecordThatNoLongerDecryptsCanStillBeRemoved() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreFactory.create(OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(tmp.root, "c.preferences_pb").toOkioPath() }, scope = scope)
        val broken = TerminalCursorStore(store, object : TokenCipher {
            override fun encrypt(plain: String, associated: String) = plain.toByteArray()
            override fun decrypt(cipher: ByteArray, associated: String): String = throw GeneralSecurityException("keyset lost")
        })
        runBlocking {
            broken.putClaim("s1", "t1", "a")
            broken.remove("s1", "t1")
            assertNull(TerminalCursorStore(store, XorTokenCipher()).get("s1", "t1"))
        }
        scope.cancel()
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
