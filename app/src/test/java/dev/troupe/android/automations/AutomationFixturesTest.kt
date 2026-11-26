package dev.troupe.android.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFixturesTest {
    private val names = listOf(
        "automation.json",
        "automation-detail.json",
        "runs.json",
        "steps.json",
        "agents-delta-changed.json",
        "agents-delta-resync.json",
        "automation-kitchen-sink.json",
    )

    @Test fun everyFixtureIsValidJsonWithASourceNote() {
        for (name in names) {
            val root = Json.parseToJsonElement(AutomationFixtures.load(name))
            assertTrue("$name must have an object root", root is JsonObject)
            val source = root.jsonObject["_source"]?.jsonPrimitive?.content
            assertTrue("$name must carry a non-blank _source", !source.isNullOrBlank())
        }
    }

    @Test fun theKitchenSinkGraphHoldsAllTenNodeKinds() {
        val graph = Json.parseToJsonElement(AutomationFixtures.load("automation-kitchen-sink.json")).jsonObject.getValue("graph").jsonObject
        val kinds = graph.getValue("nodes").jsonArray.map { it.jsonObject.getValue("spec").jsonObject.getValue("kind").jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("http", "agent", "mcp_tool", "transform", "condition", "fail", "for_each", "merge", "wait", "approval"),
            kinds,
        )
    }
}
