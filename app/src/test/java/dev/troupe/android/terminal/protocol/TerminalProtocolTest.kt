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

    @Test fun replaySnapshotSaysWhetherTheProcessExited() {
        assertEquals(null, (parseTerminalFrame(fixture("terminal_replay.json")) as TerminalFrame.Replay).exit)
        val exited = """{"type":"replay","terminalId":"t1","chunks":[],"gap":false,"reset":false,"logEpoch":"e","latestSeq":0,""" +
            """"snapshot":{"exitCode":2,"exited":true}}"""
        assertEquals(TerminalFrame.Exit("t1", 2), (parseTerminalFrame(exited) as TerminalFrame.Replay).exit)
    }

    @Test fun aReplyNeverPrintsItsData() {
        val reply = parseTerminalFrame("""{"id":"r1","success":true,"data":{"id":"t1","claim":"secret-claim"}}""")
        assertFalse(reply.toString().contains("secret-claim"))
    }

    @Test fun gapFrame() {
        val frame = parseTerminalFrame(fixture("terminal_gap.json")) as TerminalFrame.Gap
        assertEquals("t1", frame.terminalId)
        assertEquals(17L, frame.lastSeq)
    }

    @Test fun exitEventHasSnakeCaseKeys() =
        assertEquals(TerminalFrame.Exit("t1", 3), parseTerminalFrame(fixture("terminal_event_exit.json")))

    @Test fun exitBySignalHasNoCode() = assertEquals(
        TerminalFrame.Exit("t1", null),
        parseTerminalFrame("""{"type":"event","payload":{"type":"exit","terminal_id":"t1","exit_code":null,"signal":9}}"""),
    )

    @Test fun otherEventsAreUnknown() = assertTrue(
        parseTerminalFrame("""{"type":"event","payload":{"type":"cwd_changed","terminal_id":"t1","cwd":"/"}}""") is TerminalFrame.Unknown,
    )

    @Test fun listPreservedReplyDecodesAndHidesClaims() {
        val reply = parseTerminalFrame(fixture("terminal_reply_list_preserved.json")) as TerminalFrame.Reply
        val list = TroupeJson.decodeFromJsonElement(PreservedTerminals.serializer(), reply.data!!).terminals
        assertEquals(listOf("t1", "t2"), list.map { it.id })
        assertEquals("/workspace", list[0].cwd)
        assertEquals("/bin/sh", list[0].shell)
        assertEquals("c1a1m-one", list[0].claim)
        assertFalse(list[0].hasLiveAttachment)
        assertEquals(null, list[1].claim)
        assertTrue(list[1].hasLiveAttachment)
        assertFalse(list.toString().contains("c1a1m"))
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
