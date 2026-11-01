package dev.troupe.android.projects

import dev.troupe.android.R
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A Paired Server's Projects, loaded from the fake Troupe Server's `/projects`. */
class ProjectsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    private fun pairedServerId(token: String = server.token): String =
        runBlocking { graph.repository.add("Box", server.pairingInput(token)).id }

    private fun load(serverId: String): ProjectsUi {
        val vm = graph.projectsViewModel(serverId)
        return runBlocking { withTimeout(10_000) { vm.ui.first { it !is ProjectsUi.Loading } } }
    }

    @Test fun defaultProjectFirstThenByNameArchivedHidden() {
        val loaded = load(pairedServerId()) as ProjectsUi.Loaded

        assertEquals("Box", loaded.serverName)
        assertEquals(listOf("troupe", "Alpha", "zeta"), loaded.projects.map { it.name })
        assertEquals(listOf("blue", "purple", "green"), loaded.projects.map { it.color })
        assertTrue(loaded.projects.first().isDefault)
    }

    @Test fun defaultComesFromDefaultProjectIdToo() {
        server.projectsBody = """{"success":true,"data":{"projects":[
            {"id":"a","name":"a","color":"red","isArchived":false,"isDefault":false},
            {"id":"b","name":"b","color":"cyan","isArchived":false,"isDefault":false}
        ],"defaultProjectId":"b"}}"""

        val loaded = load(pairedServerId()) as ProjectsUi.Loaded
        assertEquals(listOf("b", "a"), loaded.projects.map { it.id })
    }

    @Test fun sendsTheStoredTokenAsBearer() {
        load(pairedServerId())

        val request = server.httpRequests.single { it.url.encodedPath == "/projects" }
        assertEquals("Bearer ${server.token}", request.headers["Authorization"])
        assertEquals(null, request.headers["Origin"])
    }

    @Test fun emptyProjectList() {
        server.projectsBody = """{"success":true,"data":{"projects":[],"defaultProjectId":null}}"""
        assertEquals(emptyList<Any>(), (load(pairedServerId()) as ProjectsUi.Loaded).projects)
    }

    @Test fun rejectedToken() {
        assertEquals(ProjectsUi.Error(R.string.pair_error_unauthorized), load(pairedServerId(token = "stale")))
    }

    @Test fun unreachableServer() {
        val id = pairedServerId()
        server.goOffline()
        assertEquals(ProjectsUi.Error(R.string.pair_error_unreachable), load(id))
    }

    @Test fun serverError() {
        server.projectsBody = """{"success":false,"error":"registry unavailable","code":"INTERNAL"}"""
        assertEquals(ProjectsUi.Error(R.string.pair_error_server, "registry unavailable"), load(pairedServerId()))
    }
}
