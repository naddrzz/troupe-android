package dev.troupe.android.testing

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.lifecycle.SavedStateHandle
import dev.troupe.android.chat.ChatViewModel
import dev.troupe.android.chat.ChatsViewModel
import dev.troupe.android.chat.net.ChatConnections
import dev.troupe.android.chat.net.ChatSocketFactory
import dev.troupe.android.core.net.Backoff
import dev.troupe.android.core.net.NetworkMonitor
import dev.troupe.android.core.net.ServerApi
import dev.troupe.android.core.net.ViewOnlyRegistry
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.Closeable
import java.io.File

/**
 * The app's real objects wired the way Hilt wires them, except for the two supporting seams:
 * on-disk storage in [dir] (Okio, so it also works on a Windows JVM) and the deterministic cipher.
 * Connection lifecycles run on [lifecycleDispatcher] (a test dispatcher for virtual time), without
 * backoff jitter; [network] stands in for the system's network callback.
 */
class TestGraph(dir: File, lifecycleDispatcher: CoroutineDispatcher = Dispatchers.IO) : Closeable {
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
    val network = FakeNetworkMonitor()
    val viewOnly = ViewOnlyRegistry()
    val connections = TerminalConnections(sockets, repository, api, network, viewOnly, lifecycleDispatcher) { Backoff(jitter = 0.0) }
    val cursors = TerminalCursorStore(dataStore, cipher)
    private val identity = ClientIdentityStore(dataStore)
    val chatConnections = ChatConnections(ChatSocketFactory(client, identity), repository, api, network, lifecycleDispatcher) { Backoff(jitter = 0.0) }

    fun pairViewModel() = PairViewModel(api, sockets, repository, viewOnly)

    fun projectsViewModel(serverId: String) =
        ProjectsViewModel(SavedStateHandle(mapOf("serverId" to serverId)), api, repository, connections)

    fun terminalsViewModel(serverId: String, projectId: String) =
        TerminalsViewModel(SavedStateHandle(mapOf("serverId" to serverId, "projectId" to projectId)), connections, cursors)

    fun terminalViewModel(serverId: String, terminalId: String) =
        TerminalViewModel(SavedStateHandle(mapOf("serverId" to serverId, "terminalId" to terminalId)), connections, cursors)

    fun chatsViewModel(serverId: String, projectId: String) =
        ChatsViewModel(SavedStateHandle(mapOf("serverId" to serverId, "projectId" to projectId)), chatConnections)

    fun chatViewModel(serverId: String, sessionId: String) =
        ChatViewModel(SavedStateHandle(mapOf("serverId" to serverId, "sessionId" to sessionId)), chatConnections)

    fun serversViewModel() = ServersViewModel(api, sockets, repository)

    /** Joins the storage scope, so a new graph on the same dir can open the file (an app restart). */
    override fun close() {
        connections.closeAll()
        chatConnections.closeAll()
        runBlocking {
            connections.background.coroutineContext.job.cancelAndJoin()
            scope.coroutineContext.job.cancelAndJoin()
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

class FakeNetworkMonitor : NetworkMonitor {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val available = events

    fun becameAvailable() {
        events.tryEmit(Unit)
    }
}
