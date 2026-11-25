package dev.troupe.android.agents

import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.workspace.shouldShowOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `list_acp_catalog` parsing and the launch config Troupe derives from an entry. */
class AgentCatalogTest {
    // Shapes recorded from a Troupe Server (protocol 7); `status` and `installed` are host-computed.
    private val catalog = parseCatalog(
        TroupeJson.parseToJsonElement(
            """
            {"host":{"os":"linux","arch":"x86_64"},"agents":[
              {"id":"opencode","name":"OpenCode","description":"The open source coding agent","status":"ready",
               "installed":{"command":"/home/u/.opencode/bin/opencode","args":["acp"],"version":"1.0.0"},
               "distribution":{"binary":{"linux-x86_64":{"cmd":"./opencode","archive":"https://example.test/o.tar.gz"}}},
               "runtimeRequirements":[]},
              {"id":"codex-acp","name":"Codex","status":"ready",
               "distribution":{"npx":{"package":"@zed-industries/codex-acp@0.9.0","args":["--x"],"env":{"A":"1"}}},
               "runtimeRequirements":["npx"]},
              {"id":"cursor","name":"Cursor","status":"install-required",
               "distribution":{"binary":{"linux-x86_64":{"cmd":"./cursor"}}},"runtimeRequirements":[]},
              {"id":"fast-agent","name":"fast-agent","status":"needs-runtime",
               "distribution":{"uvx":{"package":"fast-agent-acp"}},"runtimeRequirements":["uvx"]},
              {"id":"future","name":"Future","status":"something-new"},
              {"name":"no id"}
            ]}
            """.trimIndent(),
        ),
    )

    @Test fun parsesEveryEntryWithAnIdAndMapsUnknownStatusToUnavailable() {
        assertEquals(listOf("opencode", "codex-acp", "cursor", "fast-agent", "future"), catalog.map { it.id })
        assertEquals(AgentStatus.Unavailable, catalog.last().status)
        assertTrue(catalog.first { it.id == "fast-agent" }.needsUv)
        assertEquals(emptyList<CatalogAgent>(), parseCatalog(null))
    }

    @Test fun installedAgentDerivesTheHostsCommand() {
        val config = catalog.first { it.id == "opencode" }.deriveConfig()!!
        assertEquals("acp-registry:opencode", config.id)
        assertEquals(config.id, config.configId)
        assertEquals("opencode", config.templateId)
        assertEquals("/home/u/.opencode/bin/opencode", config.command)
        assertEquals(listOf("acp"), config.args)
        assertFalse(config.allowTerminal)
    }

    @Test fun readyNpxAgentRunsItsPackage() {
        val config = catalog.first { it.id == "codex-acp" }.deriveConfig()!!
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@zed-industries/codex-acp@0.9.0", "--x"), config.args)
        assertEquals(mapOf("A" to "1"), config.env)
    }

    @Test fun agentTheHostCannotStartHasNoConfig() {
        assertNull(catalog.first { it.id == "cursor" }.deriveConfig())
        assertNull(catalog.first { it.id == "fast-agent" }.deriveConfig())
        assertNull(catalog.first { it.id == "future" }.deriveConfig())
    }

    @Test fun launchableListsSavedConfigsOnceAndReadyCatalogAgents() {
        // One saved config the catalog cannot derive (cursor), one it also derives (opencode).
        val saved = listOf(
            AgentConfig(id = "acp-registry:cursor", name = "Cursor", command = "/opt/cursor"),
            AgentConfig(id = "acp-registry:opencode", name = "OpenCode", command = "/saved/opencode"),
        )
        val state = AgentSetupState(loaded = true, catalog = catalog, saved = saved)
        assertEquals(
            listOf("acp-registry:cursor", "acp-registry:opencode", "acp-registry:codex-acp"),
            state.launchable.map { it.id },
        )
        // The saved config wins over the one derived from the catalog.
        assertEquals("/saved/opencode", state.launchable.first { it.id == "acp-registry:opencode" }.command)
        // No last pick: Troupe's featured order decides (codex-acp before cursor and opencode).
        assertEquals("acp-registry:codex-acp", state.selected?.id)
        assertEquals("acp-registry:opencode", state.copy(lastSelected = "acp-registry:opencode").selected?.id)
        assertNull(AgentSetupState().selected)
    }

    @Test fun featuredAgentsComeFirstInTroupesOrder() {
        assertEquals(listOf("codex-acp", "cursor", "opencode", "fast-agent", "future"), featuredFirst(catalog).map { it.id })
    }

    @Test fun onboardingShowsOnlyOnAnEmptyWritableServerThatNeverCompletedIt() {
        assertTrue(shouldShowOnboarding(projectsLoaded = true, projectCount = 0, settingsLoaded = true, completedAt = null, viewOnly = false))
        assertFalse(shouldShowOnboarding(false, 0, true, null, false))
        assertFalse(shouldShowOnboarding(true, 1, true, null, false))
        assertFalse(shouldShowOnboarding(true, 0, false, null, false))
        assertFalse(shouldShowOnboarding(true, 0, true, "2026-11-25T08:00:00Z", false))
        assertFalse(shouldShowOnboarding(true, 0, true, null, true))
    }
}
