package dev.troupe.android.terminal

import dev.troupe.android.R
import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalKey
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.FakeTroupeServer.Companion.dataFrame
import dev.troupe.android.testing.FakeTroupeServer.Companion.exitEvent
import dev.troupe.android.testing.FakeTroupeServer.Companion.gapFrame
import dev.troupe.android.testing.FakeTroupeServer.Companion.replayFrame
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.RecordingRenderer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Terminal screen's ViewModel against the fake Troupe Server, drawing into a recording
 * renderer: what the server sends reaches the renderer raw; what the user types reaches the server.
 */
class TerminalViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeTroupeServer()
    private val graph by lazy { TestGraph(tmp.root) }
    private val renderer = RecordingRenderer()

    @After fun tearDown() {
        graph.close()
        server.close()
    }

    /** A Paired Server holding Terminal t1, spawned earlier in this process; attaching replays [prompt]. */
    private fun serverWithTerminal(prompt: String = "$ ", reset: Boolean = false): String {
        server.onTerminalRequest = { type, _ ->
            if (type == "attach") server.sendTerminalFrame(replayFrame("t1", prompt.toByteArray(), reset = reset))
            JsonObject(emptyMap())
        }
        return pairedServerHolding("t1")
    }

    private fun pairedServerHolding(vararg terminalIds: String): String {
        val serverId = runBlocking { graph.repository.add("Box", server.pairingInput()).id }
        runBlocking { terminalIds.forEach { graph.cursors.putClaim(serverId, it, "claim-$it") } }
        return serverId
    }

    private fun openTerminal(serverId: String = serverWithTerminal()): TerminalViewModel {
        val vm = graph.terminalViewModel(serverId, "t1")
        runBlocking { withTimeout(10_000) { vm.ui.first { it != TerminalUi.Connecting } } }
        return vm
    }

    private fun writes(): List<String> = server.terminalRequests("write").map { it.payloadString("data")!! }

    @Test fun attachesWithTheClaimThenShowsTheReplayedPrompt() {
        val vm = openTerminal()
        vm.bind(renderer)

        eventually { renderer.text == "$ " }
        assertEquals(TerminalUi.Live, vm.ui.value)
        val attach = server.terminalRequests("attach").single()
        assertEquals("t1", attach.payloadString("terminalId"))
        assertEquals("claim-t1", attach.payloadString("claim"))
        assertEquals(0, attach.payloadInt("lastSeq"))
        assertNull(attach.payloadString("logEpoch"))
    }

    @Test fun liveOutputReachesTheRendererAsRawBytes() {
        val vm = openTerminal()
        vm.bind(renderer)
        eventually { renderer.text == "$ " }

        // "café" with the two bytes of "é" split across frames, plus another Terminal's output.
        server.sendTerminalFrame(dataFrame("t1", 2, "caf".toByteArray() + 0xC3.toByte()))
        server.sendTerminalFrame(dataFrame("t1", 3, byteArrayOf(0xA9.toByte())))
        server.sendTerminalFrame(dataFrame("other", 1, "not mine".toByteArray()))

        eventually { renderer.calls.size == 3 }
        Thread.sleep(200)
        assertArrayEquals("$ café".toByteArray(), renderer.output)
        assertArrayEquals(byteArrayOf(0xA9.toByte()), renderer.calls.last() as ByteArray)
    }

    @Test fun outputBeforeTheRendererIsBoundIsKept() {
        val vm = openTerminal()
        server.sendTerminalFrame(dataFrame("t1", 2, "hi".toByteArray()))
        Thread.sleep(200)

        vm.bind(renderer)

        eventually { renderer.text == "$ hi" }
    }

    @Test fun resetReplayClearsTheRendererFirst() {
        val vm = openTerminal(serverWithTerminal(reset = true))
        vm.bind(renderer)

        eventually { renderer.calls.size == 2 }
        assertEquals(RecordingRenderer.RESET, renderer.calls.first())
        assertEquals("$ ", renderer.text)
    }

    @Test fun typingSendsALedgeredWrite() {
        val vm = openTerminal()
        vm.bind(renderer)

        renderer.type("ls\r")

        eventually { writes() == listOf("ls\r") }
        val write = server.terminalRequests("write").single()
        assertEquals("t1", write.payloadString("terminalId"))
        assertTrue(Regex("[A-Za-z0-9_-]{1,128}").matches(write.getValue("commandId").jsonPrimitive.content))
    }

    @Test fun theKeyBarSendsExactSequences() {
        val vm = openTerminal()
        vm.bind(renderer)

        TerminalKey.entries.forEachIndexed { i, key ->
            vm.onKey(key)
            eventually { writes().size == i + 1 }
        }

        assertEquals(
            listOf("\u001b", "\t", "\u0003", "\u001b[D", "\u001b[A", "\u001b[B", "\u001b[C", "\u001b[5~", "\u001b[6~"),
            writes(),
        )
    }

    @Test fun pasteSendsTheClipboardAsOneWrite() {
        val vm = openTerminal()
        vm.bind(renderer)

        vm.paste("echo one\necho two")

        eventually { writes().isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf("echo one\necho two"), writes())
    }

    @Test fun resizeIsDebouncedToTheLastSize() {
        val vm = openTerminal()
        vm.bind(renderer)

        renderer.size.value = TermSize(100, 30)
        renderer.size.value = TermSize(60, 20)
        Thread.sleep(50)
        renderer.size.value = TermSize(48, 30)

        eventually { server.terminalRequests("resize").isNotEmpty() }
        Thread.sleep(400)
        val resize = server.terminalRequests("resize").single()
        assertEquals("t1", resize.payloadString("terminalId"))
        assertEquals(48, resize.payloadInt("cols"))
        assertEquals(30, resize.payloadInt("rows"))
    }

    @Test fun unauthorizedAttachSaysTheTerminalIsGoneAndDropsTheClaim() {
        server.onTerminalRequest = { _, _ -> throw FakeTroupeServer.Refused("UNAUTHORIZED", "Unauthorized") }
        val serverId = pairedServerHolding("t1")

        assertEquals(TerminalUi.Failed(R.string.terminal_unavailable), openTerminal(serverId).ui.value)
        assertNull(runBlocking { graph.cursors.get(serverId, "t1") })
    }

    @Test fun aTerminalWithoutAClaimIsNeverAttached() {
        assertEquals(TerminalUi.Failed(R.string.terminal_unavailable), openTerminal(pairedServerHolding()).ui.value)
        assertTrue(server.terminalRequests("attach").isEmpty())
    }

    /** Attach #1 replays `$ ` (seq 1); later attaches answer with [replays] in order. */
    private fun serverReplaying(vararg replays: (attach: JsonObject) -> JsonObject): String {
        var attaches = 0
        server.onTerminalRequest = { type, payload ->
            if (type == "attach") {
                val frame = if (attaches == 0) replayFrame("t1", "$ ".toByteArray()) else replays[attaches - 1](payload)
                attaches++
                server.sendTerminalFrame(frame)
            }
            JsonObject(emptyMap())
        }
        return pairedServerHolding("t1")
    }

    private fun attaches() = server.terminalRequests("attach")

    /** The ViewModel's own state lives on the main thread, like a reconnect's call would. */
    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    @Test fun outOfOrderDataIsNotDrawnAndReattachesFromTheCursor() {
        val vm = openTerminal(serverReplaying({ replayFrame("t1", "c".toByteArray(), "d".toByteArray(), firstSeq = 3) }))
        vm.bind(renderer)
        server.sendTerminalFrame(dataFrame("t1", 2, "b".toByteArray()))
        eventually { renderer.text == "$ b" }

        server.sendTerminalFrame(dataFrame("t1", 4, "d".toByteArray()))

        eventually { attaches().size == 2 }
        assertEquals(2, attaches()[1].payloadInt("lastSeq"))
        assertEquals("ep-1", attaches()[1].payloadString("logEpoch"))
        assertEquals("claim-t1", attaches()[1].payloadString("claim"))
        eventually { renderer.text == "$ bcd" }
        Thread.sleep(200)
        assertEquals("$ bcd", renderer.text)
        assertFalse(renderer.calls.contains(RecordingRenderer.RESET))
    }

    @Test fun aGapReattachesFromTheCursorWithoutDuplicates() {
        val vm = openTerminal(serverReplaying({ replayFrame("t1", "b".toByteArray(), firstSeq = 2) }))
        vm.bind(renderer)
        eventually { renderer.text == "$ " }

        server.sendTerminalFrame(gapFrame("t1", lastSeq = 1))

        eventually { attaches().size == 2 }
        assertEquals(1, attaches()[1].payloadInt("lastSeq"))
        eventually { renderer.text == "$ b" }
        server.sendTerminalFrame(dataFrame("t1", 3, "c".toByteArray()))
        eventually { renderer.text == "$ bc" }
    }

    @Test fun aResetReplayOnReattachClearsTheScreenFirst() {
        val vm = openTerminal(serverReplaying({ replayFrame("t1", "fresh".toByteArray(), reset = true, logEpoch = "ep-2") }))
        vm.bind(renderer)
        eventually { renderer.text == "$ " }

        onMain { vm.reattach() }

        eventually { renderer.text == "fresh" }
        assertEquals(RecordingRenderer.RESET, renderer.calls[renderer.calls.size - 2])
        // The cursor moved to the new log: live output continues from it.
        server.sendTerminalFrame(dataFrame("t1", 2, "!".toByteArray()))
        eventually { renderer.text == "fresh!" }
    }

    @Test fun reattachSendsTheCurrentCursor() {
        val vm = openTerminal(serverReplaying({ replayFrame("t1", firstSeq = 3) }))
        vm.bind(renderer)
        server.sendTerminalFrame(dataFrame("t1", 2, "x".toByteArray()))
        eventually { renderer.text == "$ x" }

        onMain { vm.reattach() }

        eventually { attaches().size == 2 }
        assertEquals(2, attaches()[1].payloadInt("lastSeq"))
        assertEquals("ep-1", attaches()[1].payloadString("logEpoch"))
    }

    @Test fun theCursorIsStoredWithTheClaim() {
        val serverId = serverWithTerminal()
        val vm = openTerminal(serverId)
        vm.bind(renderer)
        server.sendTerminalFrame(dataFrame("t1", 2, "x".toByteArray()))

        eventually(5_000) { runBlocking { graph.cursors.get(serverId, "t1") }?.cursor == TerminalCursor("ep-1", 2) }
        assertEquals("claim-t1", runBlocking { graph.cursors.get(serverId, "t1") }!!.claim)
    }

    @Test fun aRestartedAppAttachesWithTheStoredClaimAndReplaysTheWholeLog() {
        val serverId = serverWithTerminal()
        runBlocking { graph.cursors.putCursor(serverId, "t1", TerminalCursor("ep-1", 41)) }
        graph.close()
        TestGraph(tmp.root).use { restarted ->
            val vm = restarted.terminalViewModel(serverId, "t1")
            vm.bind(renderer)

            eventually { renderer.text == "$ " }
            val attach = attaches().single()
            assertEquals("claim-t1", attach.payloadString("claim"))
            // The new screen is blank, so it asks for everything the server kept.
            assertEquals(0, attach.payloadInt("lastSeq"))
        }
    }

    @Test fun anExitEventShowsTheExitCode() {
        val vm = openTerminal()
        vm.bind(renderer)

        server.sendTerminalFrame(exitEvent("t1", 3))

        eventually { vm.ui.value == TerminalUi.Exited(3) }
    }

    @Test fun attachingToAnExitedProcessShowsItsExitCode() {
        server.onTerminalRequest = { type, _ ->
            if (type == "attach") server.sendTerminalFrame(replayFrame("t1", "bye".toByteArray(), exitCode = 0))
            JsonObject(emptyMap())
        }
        assertEquals(TerminalUi.Exited(0), openTerminal(pairedServerHolding("t1")).ui.value)
    }
}
