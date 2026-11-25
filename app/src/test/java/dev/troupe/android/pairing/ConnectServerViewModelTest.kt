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
}
