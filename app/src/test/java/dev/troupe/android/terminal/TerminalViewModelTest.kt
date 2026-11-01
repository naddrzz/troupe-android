package dev.troupe.android.terminal

import dev.troupe.android.R
import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalKey
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.FakeTroupeServer.Companion.dataFrame
import dev.troupe.android.testing.FakeTroupeServer.Companion.replayFrame
import dev.troupe.android.testing.MainDispatcherRule
import dev.troupe.android.testing.RecordingRenderer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.testing.eventually
import dev.troupe.android.testing.payloadInt
import dev.troupe.android.testing.payloadString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        terminalIds.forEach { graph.claims.put(serverId, it, "claim-$it") }
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

    @Test fun refusedAttachSaysTheTerminalIsGone() {
        server.onTerminalRequest = { _, _ -> throw FakeTroupeServer.Refused("UNAUTHORIZED", "Not authorized for terminal t1") }

        assertEquals(TerminalUi.Failed(R.string.terminal_unavailable), openTerminal(pairedServerHolding("t1")).ui.value)
    }

    @Test fun aTerminalWithoutAClaimIsNeverAttached() {
        assertEquals(TerminalUi.Failed(R.string.terminal_unavailable), openTerminal(pairedServerHolding()).ui.value)
        assertTrue(server.terminalRequests("attach").isEmpty())
    }
}
