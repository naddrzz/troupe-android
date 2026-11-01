package dev.troupe.android.chat.protocol

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
