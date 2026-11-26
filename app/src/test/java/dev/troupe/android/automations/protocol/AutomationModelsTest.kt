package dev.troupe.android.automations.protocol

import dev.troupe.android.automations.AutomationFixtures
import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationModelsTest {
    /** The `data` element of a `{"success":true,"data":...}` route reply fixture. */
    private fun data(name: String) = TroupeJson.parseToJsonElement(AutomationFixtures.load(name)).jsonObject.getValue("data")

    @Test
    fun automationFixtureDecodes() {
        val automation = TroupeJson.decodeFromString<Automation>(AutomationFixtures.load("automation.json"))
        assertEquals("project-1", automation.projectId)
        assertTrue(automation.enabled)
        assertNotNull(automation.trigger)
    }

    @Test
    fun allTenNodeKindsDecode() {
        val automation = TroupeJson.decodeFromString<Automation>(AutomationFixtures.load("automation-kitchen-sink.json"))
        assertEquals(10, automation.graph.nodes.map { it.spec::class }.toSet().size)
    }

    @Test
    fun detailRunsAndStepsFixturesDecode() {
        val detail = TroupeJson.decodeFromJsonElement<AutomationDetail>(data("automation-detail.json"))
        assertEquals("project-1", detail.automation.projectId)
        assertTrue(TroupeJson.decodeFromJsonElement<List<AutomationRun>>(data("runs.json")).isNotEmpty())
        assertTrue(TroupeJson.decodeFromJsonElement<List<AutomationStep>>(data("steps.json")).isNotEmpty())
    }

    @Test
    fun unknownNodeKindFails() {
        val json = AutomationFixtures.load("automation.json").replaceFirst("\"kind\": \"http\"", "\"kind\": \"teleport\"")
        assertTrue(json.contains("teleport"))
        assertThrows(SerializationException::class.java) { TroupeJson.decodeFromString<Automation>(json) }
    }

    @Test
    fun unknownRunStatusFails() {
        val json = AutomationFixtures.load("automation.json")
        val status = Regex("\"status\": \"[a-z]+\"").find(json)
        val broken = if (status != null) json.replaceRange(status.range, "\"status\": \"exploded\"") else error("fixture has no status")
        assertThrows(SerializationException::class.java) { TroupeJson.decodeFromString<Automation>(broken) }
    }

    @Test
    fun missingRequiredFieldFails() {
        val json = AutomationFixtures.load("automation.json").replaceFirst(Regex("\"project_id\"\\s*:\\s*\"[^\"]*\",?"), "")
        assertThrows(SerializationException::class.java) { TroupeJson.decodeFromString<Automation>(json) }
    }

    @Test
    fun unknownIssueCodeIsKept() {
        val issue = TroupeJson.decodeFromString<Issue>("""{"node_id":null,"code":"brand_new_code","message":"m"}""")
        assertEquals("brand_new_code", issue.code)
        assertNull(issue.nodeId)
        val known = TroupeJson.decodeFromString<Issue>("""{"node_id":"fetch","code":"trigger_edge_count","message":"m"}""")
        assertEquals(NodeId("fetch"), known.nodeId)
    }

    @Test
    fun unknownExtraKeysIgnored() {
        val body = AutomationFixtures.load("automation.json").trimEnd().removeSuffix("}") + ""","brand_new_key":{"a":1}}"""
        assertEquals("project-1", TroupeJson.decodeFromString<Automation>(body).projectId)
    }

    @Test
    fun approvalOutputParses() {
        val bare = parseApprovalOutput(TroupeJson.parseToJsonElement("""{"message":"ok?"}"""))
        assertEquals("ok?", bare?.message)
        assertNull(bare?.decision)

        val decided = parseApprovalOutput(TroupeJson.parseToJsonElement("""{"message":"ok?","decision":"approved","decided_at":5}"""))
        assertEquals(ApprovalDecision.Approved, decided?.decision)
        assertEquals(5L, decided?.decidedAt)

        assertNull(parseApprovalOutput(TroupeJson.parseToJsonElement("""{"x":1}""")))
        assertNull(parseApprovalOutput(JsonNull))
        assertNull(parseApprovalOutput(null))
        assertNull(parseApprovalOutput(TroupeJson.parseToJsonElement("""{"message":"ok?","decision":"maybe","decided_at":5}""")))
        assertNull(parseApprovalOutput(TroupeJson.parseToJsonElement("""{"message":"ok?","decision":"approved"}""")))
    }

    @Test
    fun changedEventParses() {
        val changed = TroupeJson.decodeFromJsonElement<AutomationChanged>(
            TroupeJson.parseToJsonElement(AutomationFixtures.load("agents-delta-changed.json")).jsonObject.getValue("payload"),
        )
        assertEquals("p1", changed.projectId)
        assertEquals("a1", changed.automationId)
        assertFalse(changed.proposed)
        assertNull(changed.proposedBy)

        val resync = TroupeJson.parseToJsonElement(AutomationFixtures.load("agents-delta-resync.json")).jsonObject.getValue("payload")
        assertEquals(3, resync.jsonObject.getValue("missed").jsonPrimitive.int)

        val projectWide = TroupeJson.decodeFromString<AutomationChanged>("""{"project_id":"p1","automation_id":null,"proposed":true,"proposed_by":"Claude"}""")
        assertNull(projectWide.automationId)
        assertTrue(projectWide.proposed)
        assertEquals("Claude", projectWide.proposedBy)
    }
}
