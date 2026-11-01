package dev.troupe.android.testing

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.lifecycle.SavedStateHandle
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.troupeHttpClient
import dev.troupe.android.pairing.PairViewModel
import dev.troupe.android.projects.ProjectsViewModel
import dev.troupe.android.servers.PairedServerRepository
import dev.troupe.android.servers.ServersViewModel
import dev.troupe.android.terminal.TerminalCursorStore
import dev.troupe.android.terminal.TerminalViewModel
import dev.troupe.android.terminal.TerminalsViewModel
import dev.troupe.android.terminal.net.ClientIdentityStore
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.net.TerminalSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.Closeable
import java.io.File

/**
 * The app's real objects wired the way Hilt wires them, except for the two supporting seams:
 * on-disk storage in [dir] (Okio, so it also works on a Windows JVM) and the deterministic cipher.
 */
class TestGraph(dir: File) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefsFile = File(dir, "troupe.preferences_pb")
    private val dataStore = DataStoreFactory.create(
        OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { prefsFile.toOkioPath() },
        scope = scope,
    )
    private val client = troupeHttpClient()
    val api = ServerApi(client)
    private val cipher = XorTokenCipher()
    val repository = PairedServerRepository(dataStore, cipher)
    val sockets = TerminalSocketFactory(client, ClientIdentityStore(dataStore))
    val connections = TerminalConnections(sockets, repository)
    val cursors = TerminalCursorStore(dataStore, cipher)

    fun pairViewModel() = PairViewModel(api, sockets, repository)

    fun projectsViewModel(serverId: String) =
        ProjectsViewModel(SavedStateHandle(mapOf("serverId" to serverId)), api, repository)

    fun terminalsViewModel(serverId: String, projectId: String) =
        TerminalsViewModel(SavedStateHandle(mapOf("serverId" to serverId, "projectId" to projectId)), connections, cursors)

    fun terminalViewModel(serverId: String, terminalId: String) =
        TerminalViewModel(SavedStateHandle(mapOf("serverId" to serverId, "terminalId" to terminalId)), connections, cursors)

    fun serversViewModel() = ServersViewModel(api, sockets, repository)

    /** Joins the storage scope, so a new graph on the same dir can open the file (an app restart). */
    override fun close() {
        runBlocking {
            connections.background.coroutineContext.job.cancelAndJoin()
            scope.coroutineContext.job.cancelAndJoin()
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
