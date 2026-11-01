package dev.troupe.android.integration

import dev.troupe.android.R
import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.core.net.troupeHttpClient
import dev.troupe.android.pairing.PairUiState
import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import dev.troupe.android.projects.ProjectsUi
import dev.troupe.android.terminal.TerminalUi
import dev.troupe.android.testing.RecordingRenderer
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * The pairing, Projects and Terminal flows against a real Troupe Server: `scripts/test-server.ps1 up`,
 * then `gradlew :app:integrationTest`. Skips when the server is not running.
 */
class RealServerTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val address = ServerAddress.of("http://127.0.0.1:18080/")
    private val graph by lazy { TestGraph(tmp.root) }
    private val http = troupeHttpClient()
    private val projectId = "troupe-it-" + UUID.randomUUID()

    @Before fun serverIsUp() {
        val health = runBlocking { graph.api.health(address) }
        assumeTrue("Troupe Server not running on $address; run scripts/test-server.ps1 up", health is ApiResult.Ok)
    }

    @After fun tearDown() {
        // Cleanup only, straight to the server: remove the Project the test added.
        val delete = Request.Builder().url(address.httpUrl("projects/$projectId?cascade=true"))
            .header("Authorization", "Bearer $TOKEN").delete().build()
        runCatching { http.newCall(delete).execute().close() }
        graph.close()
    }

    @Test fun pairThenListProjects() {
        addProject(name = "troupe-it")

        val pair = graph.pairViewModel()
        pair.onManual("127.0.0.1:18080", TOKEN, "Docker")
        val paired = runBlocking { withTimeout(15_000) { pair.ui.first { it is PairUiState.Paired || it is PairUiState.Error } } }
        assertTrue("pairing: $paired", paired is PairUiState.Paired)
        val serverId = (paired as PairUiState.Paired).serverId

        val projects = graph.projectsViewModel(serverId)
        val loaded = runBlocking { withTimeout(15_000) { projects.ui.first { it !is ProjectsUi.Loading } } }
        assertTrue("projects: $loaded", loaded is ProjectsUi.Loaded)
        val project = (loaded as ProjectsUi.Loaded).projects.single { it.id == projectId }
        assertEquals("troupe-it", project.name)
        assertEquals("blue", project.color)
    }

    @Test fun realServerRejectsAWrongToken() {
        val pair = graph.pairViewModel()
        pair.onManual("127.0.0.1:18080", "0".repeat(64), "")
        val state = runBlocking { withTimeout(15_000) { pair.ui.first { it is PairUiState.Paired || it is PairUiState.Error } } }
        assertEquals(PairUiState.Error(R.string.pair_error_unauthorized), state)
    }

    @Test fun spawnTypeReadKill() {
        addProject(name = "troupe-it")
        val serverId = runBlocking { graph.repository.add("Docker", PairingInput(address, TOKEN)).id }

        val terminals = graph.terminalsViewModel(serverId, projectId)
        terminals.spawn()
        val spawned = runBlocking { withTimeout(15_000) { terminals.ui.first { it.openTerminal != null || it.error != null } } }
        val terminalId = checkNotNull(spawned.openTerminal) { "spawn: $spawned" }

        val terminal = graph.terminalViewModel(serverId, terminalId)
        val renderer = RecordingRenderer()
        terminal.bind(renderer)
        assertEquals(TerminalUi.Live, runBlocking { withTimeout(15_000) { terminal.ui.first { it != TerminalUi.Connecting } } })

        renderer.type("echo troupe-ok\r")
        // The echoed command line, then the command's own output line.
        eventually(15_000, { "output: ${renderer.text}" }) { Regex("(?m)^troupe-ok\r?$").containsMatchIn(renderer.text) }

        val kill = runBlocking {
            graph.connections.socket(serverId).request("kill", buildJsonObject { put("terminalId", terminalId) }, ledgered = true)
        }
        assertTrue("kill: ${kill.error}", kill.success)
    }

    /** Setup only: `POST /projects` straight to the server. */
    private fun addProject(name: String) {
        val body = """{"id":"$projectId","name":"$name","path":"/workspace","color":"blue"}"""
        val request = Request.Builder().url(address.httpUrl("projects"))
            .header("Authorization", "Bearer $TOKEN")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            assertTrue("POST /projects: ${response.code} $text", response.isSuccessful && "\"success\":true" in text)
        }
    }

    private companion object {
        /** The fixed test-only token from scripts/test-server.ps1. */
        const val TOKEN = "7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c"
    }
}
