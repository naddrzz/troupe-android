package dev.troupe.android.setup

import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun reconfigureToNewPortMigratesStayConnectedAndRemovesOldServerId() {
        FakeTroupeServer().use { oldServer ->
            FakeTroupeServer().use { newServer ->
                newServer.token = oldServer.token
                val oldPort = oldServer.address.baseUrl.port
                val newPort = newServer.address.baseUrl.port
                assertNotEquals(oldPort, newPort)

                oldServer.onHttpRoute = { req ->
                    when {
                        req.url.encodedPath == "/server/config" && req.method == "GET" ->
                            MockResponse.Builder()
                                .code(200)
                                .setHeader("Content-Type", "application/json")
                                .body(
                                    """{"success":true,"data":{"config":{"host":"0.0.0.0","port":$oldPort,"sessionsDir":"/tmp/sessions","projectsFile":"/tmp/projects.json","allowRemoteWrites":true,"updateChannel":"stable","updateIntervalSecs":21600}}}""",
                                )
                                .build()
                        req.url.encodedPath == "/server/config" && req.method == "POST" ->
                            MockResponse.Builder()
                                .code(200)
                                .setHeader("Content-Type", "application/json")
                                .body("""{"success":true,"data":{"ok":true,"host":"0.0.0.0","port":$newPort}}""")
                                .build()
                        else -> null
                    }
                }

                TestGraph(temp.newFolder()).use { graph ->
                    val oldId = runBlocking {
                        val added = graph.repository.add("Box", oldServer.pairingInput())
                        graph.repository.setStayConnected(added.id, true)
                        added.id
                    }
                    assertTrue(runBlocking { graph.repository.server(oldId)!!.stayConnected })

                    val vm = ServerSetupViewModel(graph.setupApi, graph.api, graph.repository, graph.viewOnly)
                    vm.loadForReconfigure(oldId)
                    eventually(5_000) { !vm.ui.value.loading }
                    assertEquals(oldPort, vm.ui.value.answers.port)

                    vm.updateAnswers { it.copy(port = newPort) }
                    vm.apply()

                    eventually(10_000) { vm.ui.value.completedServerId != null }
                    val newId = vm.ui.value.completedServerId!!
                    assertNotEquals(oldId, newId)
                    assertNull(runBlocking { graph.repository.server(oldId) })
                    val migrated = runBlocking { graph.repository.server(newId) }!!
                    assertTrue("Expected stayConnected to be preserved on migrated server", migrated.stayConnected)
                    assertEquals(newPort, migrated.address.baseUrl.port)
                }
            }
        }
    }
}
