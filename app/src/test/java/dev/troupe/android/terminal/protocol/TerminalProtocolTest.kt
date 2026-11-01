package dev.troupe.android.terminal.protocol

import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.testing.FakeTroupeServer.Companion.fixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Output frames as termul's `web-terminal-protocol.types.ts` defines them: bytes are JSON int arrays. */
class TerminalProtocolTest {
    @Test fun dataFrameBytes() {
        val frame = parseTerminalFrame(fixture("terminal_data.json")) as TerminalFrame.Data
        assertEquals("t1", frame.terminalId)
        assertEquals(12L, frame.seq)
        assertArrayEquals(byteArrayOf(104, 105), frame.data)
    }

    @Test fun replayFrameKeepsBytesAboveAscii() {
        val frame = parseTerminalFrame(fixture("terminal_replay.json")) as TerminalFrame.Replay
        assertEquals("t1", frame.terminalId)
        assertTrue(frame.reset)
        assertEquals("ep-1", frame.logEpoch)
        assertEquals(2L, frame.latestSeq)
        assertEquals(listOf(1L, 2L), frame.chunks.map { it.seq })
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), frame.chunks[1].data)
    }

    @Test fun malformedDataFrameIsUnknown() =
        assertTrue(parseTerminalFrame("""{"type":"data","terminalId":"t1","seq":"x","data":[1]}""") is TerminalFrame.Unknown)

    @Test fun unknownTypeIsUnknown() = assertTrue(parseTerminalFrame("""{"type":"nope"}""") is TerminalFrame.Unknown)

    @Test fun notJsonIsUnknown() = assertTrue(parseTerminalFrame("<<<") is TerminalFrame.Unknown)

    @Test fun spawnReplyDecodesAndHidesTheClaim() {
        val reply = parseTerminalFrame(fixture("terminal_reply_spawn.json")) as TerminalFrame.Reply
        val spawned = TroupeJson.decodeFromJsonElement(SpawnedTerminal.serializer(), reply.data!!)
        assertEquals("t1", spawned.id)
        assertEquals("/bin/bash", spawned.shell)
        assertTrue(spawned.claim.startsWith("c1a1m"))
        assertFalse(spawned.toString().contains("c1a1m"))
    }
}
