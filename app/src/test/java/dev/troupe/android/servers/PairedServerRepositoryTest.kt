package dev.troupe.android.servers

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import dev.troupe.android.pairing.PairingLink
import dev.troupe.android.testing.XorTokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Supporting seam: Keystore is unavailable on the JVM, so tokens go through a deterministic cipher. */
class PairedServerRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefsFile: File by lazy { File(tmp.root, "servers.preferences_pb") }
    private val repo by lazy {
        PairedServerRepository(DataStoreFactory.create(OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { prefsFile.toOkioPath() }, scope = scope), XorTokenCipher())
    }

    @After fun tearDown() = scope.cancel()

    private fun input(url: String, token: String) = PairingLink.fromManual(url, token).getOrThrow()

    @Test fun addThenReadBack() = runBlocking {
        val s = repo.add("MacBook", input("http://10.0.0.2:8080", "tok"))
        assertEquals(listOf(s), repo.servers.first())
        assertEquals("MacBook", s.name)
        assertEquals("http://10.0.0.2:8080/", s.address.baseUrl.toString())
        assertEquals("tok", repo.token(s.id))
    }

    @Test fun tokenIsNotStoredInPlaintext() = runBlocking {
        val s = repo.add("A", input("http://10.0.0.2", "plain-token-xyz"))
        assertEquals("plain-token-xyz", repo.token(s.id))
        val onDisk = prefsFile.readText(Charsets.ISO_8859_1)
        assertTrue("servers are on disk", onDisk.contains("10.0.0.2"))
        assertFalse(onDisk.contains("plain-token-xyz"))
    }

    @Test fun sameUrlReplacesToken() = runBlocking {
        val a = repo.add("A", input("http://10.0.0.2", "one"))
        val b = repo.add("B", input("http://10.0.0.2/", "two"))
        assertEquals(a.id, b.id)
        assertEquals("two", repo.token(a.id))
        assertEquals(1, repo.servers.first().size)
    }

    @Test fun removeDeletesToken() = runBlocking {
        val a = repo.add("A", input("http://10.0.0.2", "one"))
        repo.remove(a.id)
        assertNull(repo.token(a.id))
        assertTrue(repo.servers.first().isEmpty())
    }

    @Test fun pairedServerToStringHasNoToken() = runBlocking {
        val s = repo.add("A", input("http://10.0.0.2", "secret-abc"))
        assertFalse(s.toString().contains("secret-abc"))
        assertFalse(repo.servers.first().toString().contains("secret-abc"))
    }

    @Test fun stayConnectedIsOffByDefaultAndKeptPerServer() = runBlocking {
        val a = repo.add("A", input("http://10.0.0.2", "one"))
        val b = repo.add("B", input("http://10.0.0.3", "two"))
        assertFalse(a.stayConnected)

        repo.setStayConnected(a.id, true)

        assertEquals(mapOf(a.id to true, b.id to false), repo.servers.first().associate { it.id to it.stayConnected })
    }

    @Test fun rePairingKeepsStayConnected() = runBlocking {
        val a = repo.add("A", input("http://10.0.0.2", "one"))
        repo.setStayConnected(a.id, true)

        repo.add("A", input("http://10.0.0.2", "two"))

        assertTrue(repo.servers.first().single().stayConnected)
    }

    @Test fun disconnectingTurnsEveryToggleOff() = runBlocking {
        val a = repo.add("A", input("http://10.0.0.2", "one"))
        val b = repo.add("B", input("http://10.0.0.3", "two"))
        repo.setStayConnected(a.id, true)
        repo.setStayConnected(b.id, true)

        repo.stopStayingConnected()

        assertTrue(repo.servers.first().none { it.stayConnected })
    }
}
