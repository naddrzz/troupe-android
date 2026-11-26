package dev.troupe.android.automations.ui

import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.servers.PairedServer
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ViewModel's rebuild rule: one result per (server, hub) pair, closed when replaced. Plain coroutines rather than
 * `runTest`, which fails on exceptions other test classes' ViewModels leak after their Main dispatcher is reset.
 */
class PerConnectionTest {
    /** A hub stand-in with identity equality, as `WatchHub` has, published with the server it belongs to. */
    private class Hub(val serverId: String = "s1")

    private class Built(val server: String, val hub: Hub) {
        @Volatile var closed = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = MutableStateFlow<String?>(null)
    private val hubs = MutableStateFlow<Hub?>(null)
    private val built = CopyOnWriteArrayList<Built>()

    @After fun tearDown() = scope.cancel()

    private fun results() = perConnection(
        servers,
        hubs,
        sameServer = { server, hub -> server == hub.serverId },
        close = { b: Built -> b.closed = true },
    ) { server, hub ->
        Built(server, hub).also { built += it }
    }

    private fun start(): StateFlow<Built?> = results().stateIn(scope, SharingStarted.Eagerly, null)

    @Test fun nullUntilBothAreKnownThenBuildsOnce() {
        val current = start()
        servers.value = "s1"
        Thread.sleep(200)
        assertNull("built without a hub", current.value)

        val hub = Hub()
        hubs.value = hub

        eventually { current.value != null }
        assertEquals("s1", current.value?.server)
        assertEquals(hub, current.value?.hub)
        assertEquals(1, built.size)
    }

    @Test fun hubClearedClosesAndEmitsNullThenNewHubOnSameServerRebuilds() {
        val current = start()
        servers.value = "s1"
        hubs.value = Hub()
        eventually { current.value != null }
        val first = current.value!!

        hubs.value = null
        eventually { current.value == null }
        assertTrue(first.closed)

        val second = Hub()
        hubs.value = second
        eventually { current.value?.hub === second }
        assertNotSame(first, current.value)
        assertEquals(false, current.value?.closed)
    }

    @Test fun newHubWithoutAGapRebuilds() {
        val current = start()
        servers.value = "s1"
        hubs.value = Hub()
        eventually { current.value != null }
        val first = current.value!!

        val second = Hub()
        hubs.value = second

        eventually { current.value?.hub === second }
        assertTrue(first.closed)
        assertEquals(2, built.size)
    }

    @Test fun serverSwitchRebuildsOnItsOwnHubAndDetachEmitsNull() {
        val current = start()
        servers.value = "s1"
        hubs.value = Hub("s1")
        eventually { current.value != null }
        val first = current.value!!

        servers.value = "s2"
        hubs.value = Hub("s2")
        eventually { current.value?.server == "s2" }
        assertTrue(first.closed)
        assertEquals("s2", current.value?.hub?.serverId)

        servers.value = null
        eventually { current.value == null }
        assertTrue(built.all { it.closed })
    }

    @Test fun aServerIsNeverPairedWithAnotherServersHub() {
        val current = start()
        servers.value = "s1"
        val old = Hub("s1")
        hubs.value = old
        eventually { current.value != null }
        val first = current.value!!

        // The switch publishes the new server while the old server's hub is still out.
        servers.value = "s2"
        eventually { first.closed }
        Thread.sleep(200)
        assertNull("built on the old server's hub", current.value)
        assertEquals("built for a mismatched pair", 1, built.size)

        val fresh = Hub("s2")
        hubs.value = fresh
        eventually { current.value?.hub === fresh }
        assertEquals("s2", current.value?.server)
        assertEquals(2, built.size)
    }

    @Test fun renamingTheServerKeepsTheResultButANewAddressRebuilds() {
        val box = PairedServer("s1", "Box", ServerAddress("http://127.0.0.1:1/".toHttpUrl()))
        val paired = MutableStateFlow<PairedServer?>(box)
        val current = perConnection(
            paired.connectionKeys(),
            hubs,
            sameServer = { key, hub -> key.id == hub.serverId },
            close = { b: Built -> b.closed = true },
        ) { key, hub ->
            Built(key.id, hub).also { built += it }
        }.stateIn(scope, SharingStarted.Eagerly, null)
        hubs.value = Hub()
        eventually { current.value != null }

        paired.value = box.copy(name = "Renamed", stayConnected = true, hasToken = false)
        Thread.sleep(300)
        assertEquals("a rename rebuilt the result", 1, built.size)
        assertEquals(false, current.value?.closed)

        paired.value = box.copy(address = ServerAddress("http://127.0.0.1:2/".toHttpUrl()))
        eventually { built.size == 2 }
        assertTrue(built.first().closed)
    }

    @Test fun endingCollectionClosesTheLast() {
        servers.value = "s1"
        hubs.value = Hub()
        val collector = scope.launch { results().collect {} }
        eventually { built.size == 1 }

        collector.cancel()

        eventually(message = { "the last result was not closed" }) { built.single().closed }
    }
}
