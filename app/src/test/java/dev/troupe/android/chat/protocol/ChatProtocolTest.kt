package dev.troupe.android.chat.protocol

import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/ws` frames as termul's `web-protocol.types.ts` and `api-contracts.md` show them. */
class ChatProtocolTest {
    @Test fun repliesCarryAPayloadOrAnError() {
        assertEquals(
            ChatFrame.Reply("r1", ok = true, payload = JsonArray(emptyList()), code = null, message = null),
            parseChatFrame("""{"id":"r1","ok":true,"payload":[]}"""),
        )
        assertEquals(
            ChatFrame.Reply("r2", ok = false, payload = null, code = "stale", message = "cursor does not belong to the event log"),
            parseChatFrame("""{"id":"r2","ok":false,"err":{"code":"stale","message":"cursor does not belong to the event log"}}"""),
        )
    }

    @Test fun sessionEventsAndTheHandshakeFrame() {
        val event = parseChatFrame("""{"sid":"s1","seq":4,"type":"prompt_complete","payload":{"sessionId":"s1","stopReason":"end_turn"}}""")
        assertEquals(ChatFrame.Event("s1", 4, "prompt_complete", JsonObject(mapOf("sessionId" to JsonPrimitive("s1"), "stopReason" to JsonPrimitive("end_turn")))), event)
        assertEquals(ChatFrame.Event(null, 0, "auth_required", JsonObject(emptyMap())), parseChatFrame("""{"sid":null,"seq":0,"type":"auth_required","payload":{}}"""))
    }

    @Test fun aPersistedSessionSummaryOfAQuickChat() {
        // Troupe's `PersistedSessionSummary` (crates/troupe-protocol/src/ws/payloads.rs), every field.
        val json = checkNotNull(javaClass.getResource("/fixtures/chat_history_summary.json")).readText()
        val summary = TroupeJson.decodeFromString(SessionSummary.serializer(), json)
        assertEquals("sess-b9ac9edb", summary.sessionId)
        assertEquals("Say hello in one short sentence.", summary.title)
        assertEquals(1795613700000L, summary.createdAt)
        assertEquals(1795613760000L, summary.lastActivityAt)
        assertEquals("closed", summary.status)
        assertEquals(false, summary.turnActive)
        assertEquals("quick", summary.kind)
        assertEquals(UNASSIGNED_PROJECT_ID, summary.projectId)
        assertTrue(summary.isQuick)
        // An older host sends neither `kind` nor `createdAt`: a Project chat, dated by its activity.
        val old = TroupeJson.decodeFromString(SessionSummary.serializer(), """{"sessionId":"s2","projectId":"p1","lastActivityAt":5}""")
        assertEquals(null, old.kind)
        assertTrue(!old.isQuick)
        assertEquals(0L, old.createdAt)
    }

    @Test fun relayFrames() {
        assertEquals(ChatFrame.Gap("s1"), parseChatFrame("""{"type":"gap","sid":"s1"}"""))
        assertEquals(ChatFrame.Heartbeat, parseChatFrame("""{"type":"heartbeat"}"""))
    }

    @Test fun watchStateAndDelta() {
        assertEquals(
            ChatFrame.Watch("chat_history", "", "h1", 1, reset = true, state = JsonArray(emptyList()), delta = null),
            parseChatFrame("""{"type":"watch","watch":"chat_history","scope":"","revision":{"hostEpoch":"h1","n":1},"reset":true,"state":[]}"""),
        )
        val delta = parseChatFrame("""{"type":"watch","watch":"chat_history","scope":"","revision":{"hostEpoch":"h1","n":2},"delta":{"changed":["s1"]}}""")
        assertEquals(2L, (delta as ChatFrame.Watch).n)
        assertEquals(false, delta.reset)
    }

    @Test fun anythingElseIsUnknownAndNeverThrows() {
        listOf("""{"type":"canvas_call","callId":"c"}""", "not json", "[]", """{"type":"gap"}""").forEach {
            assertTrue(it, parseChatFrame(it) is ChatFrame.Unknown)
        }
    }
}
