package dev.troupe.android.pairing

import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectServerViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun pastesGatedLinkAndConnects() = runBlocking {
        FakeTroupeServer().use { server ->
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink("${server.address.baseUrl}#token=${server.token}")
                val state = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                val saved = graph.repository.server(state.serverId)!!
                assertEquals(server.address, saved.address)
                assertTrue(saved.hasToken)
                assertEquals(server.token, graph.repository.token(saved.id))
            }
        }
    }

    @Test
    fun pastesUngatedUrlAndConnectsWithoutToken() = runBlocking {
        FakeTroupeServer().use { server ->
            server.token = ""
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink(server.typedAddress)
                val state = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                val saved = graph.repository.server(state.serverId)!!
                assertFalse(saved.hasToken)
                assertEquals("", graph.repository.token(saved.id))
            }
        }
    }

    @Test
    fun gatedServerWithoutTokenShowsTokenGateThenUnlocks() = runBlocking {
        FakeTroupeServer().use { server ->
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink(server.typedAddress)
                val gate = vm.ui.first { it is ConnectUiState.NeedsToken } as ConnectUiState.NeedsToken
                assertFalse(gate.rejected)

                vm.submitGateToken("wrong-token")
                val rejected = vm.ui.first { it is ConnectUiState.NeedsToken && it.rejected } as ConnectUiState.NeedsToken
                assertTrue(rejected.rejected)

                vm.submitGateToken(server.token)
                val connected = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                assertEquals(server.token, graph.repository.token(connected.serverId))
            }
        }
    }

    @Test
    fun setupRequiredHealthRoutesToFirstRunWizard() = runBlocking {
        FakeTroupeServer().use { server ->
            server.setupRequired = true
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink("${server.address.baseUrl}?setup=${server.setupToken}")
                val needsSetup = vm.ui.first { it is ConnectUiState.NeedsSetup } as ConnectUiState.NeedsSetup
                assertEquals(server.setupToken, needsSetup.setupToken)
            }
        }
    }

    @Test
    fun wildcardHostPromptsForReplacementThenConnects() = runBlocking {
        FakeTroupeServer().use { server ->
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                val port = server.address.baseUrl.port
                vm.connectLink("http://0.0.0.0:$port/#token=${server.token}")
                val needsHost = vm.ui.first { it is ConnectUiState.NeedsHost } as ConnectUiState.NeedsHost
                assertTrue(needsHost.parsed.isWildcardHost)

                vm.submitReplacementHost(server.address.host)
                val connected = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                assertEquals(server.address, graph.repository.server(connected.serverId)?.address)
            }
        }
    }

    @Test
    fun publicCleartextLinkAndManualEntryAreRefusedBeforeAnyRequest() = runBlocking {
        val attemptedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recordingClient = dev.troupe.android.core.net.troupeHttpClient().newBuilder()
            .addInterceptor { chain ->
                attemptedUrls += chain.request().url.toString()
                throw java.io.IOException("Unexpected outbound request to ${chain.request().url}")
            }
            .build()
        try {
            TestGraph(temp.newFolder(), client = recordingClient).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink("http://8.8.8.8:8080/#token=secret")
                assertEquals(
                    ConnectUiState.Error(dev.troupe.android.R.string.pair_error_cleartext, "8.8.8.8"),
                    vm.ui.value,
                )

                vm.reset()
                vm.connectManual("http://example.com:8080", "secret", "Public")
                assertEquals(
                    ConnectUiState.Error(dev.troupe.android.R.string.pair_error_cleartext, "example.com"),
                    vm.ui.value,
                )
                assertTrue("Expected zero outbound requests, saw $attemptedUrls", attemptedUrls.isEmpty())
            }
        } finally {
            recordingClient.dispatcher.executorService.shutdown()
            recordingClient.connectionPool.evictAll()
        }
    }

    @Test
    fun protocolMismatchAndUnreachableSurfaceExplicitErrors() = runBlocking {
        FakeTroupeServer().use { server ->
            server.protocolVersion = 8
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectLink("${server.address.baseUrl}#token=${server.token}")
                val protoErr = vm.ui.first { it is ConnectUiState.Error } as ConnectUiState.Error
                assertEquals(dev.troupe.android.R.string.pair_error_protocol, protoErr.message)

                val addr = "${server.address.baseUrl}#token=${server.token}"
                server.goOffline()
                vm.reset()
                vm.connectLink(addr)
                val offlineErr = vm.ui.first { it is ConnectUiState.Error } as ConnectUiState.Error
                assertEquals(dev.troupe.android.R.string.pair_error_unreachable, offlineErr.message)
            }
        }
    }

    @Test
    fun manualConnectWithCustomNameAndNoisyScannedLinkSucceed() = runBlocking {
        FakeTroupeServer().use { server ->
            TestGraph(temp.newFolder()).use { graph ->
                val vm = graph.connectServerViewModel()
                vm.connectManual(server.typedAddress, server.token, "Studio Mac")
                val manual = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                val savedManual = graph.repository.server(manual.serverId)!!
                assertEquals("Studio Mac", savedManual.name)
                assertEquals(server.token, graph.repository.token(savedManual.id))

                vm.reset()
                vm.connectLink("  \n${server.address.baseUrl}#token=${server.token}\t  ", customName = "Scanned Box")
                val scanned = vm.ui.first { it is ConnectUiState.Connected } as ConnectUiState.Connected
                val savedScanned = graph.repository.server(scanned.serverId)!!
                assertEquals("Scanned Box", savedScanned.name)
                assertEquals(server.token, graph.repository.token(savedScanned.id))
            }
        }
    }
}
