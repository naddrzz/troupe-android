package dev.troupe.android.testing

import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalRenderer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** The supporting seam for Terminal tests: records what the app drew and lets the test type and resize. */
class RecordingRenderer : TerminalRenderer {
    private val typed = Channel<String>(Channel.UNLIMITED)
    override val input: Flow<String> = typed.receiveAsFlow()
    override val size = MutableStateFlow<TermSize?>(null)

    /** Every call in order: a [ByteArray] per write, [RESET] per reset. */
    val calls: MutableList<Any> = CopyOnWriteArrayList()

    override fun write(bytes: ByteArray) {
        calls += bytes.copyOf()
    }

    override fun reset() {
        calls += RESET
    }

    override fun focus() = Unit

    /** The user types [text] into the Terminal. */
    fun type(text: String) {
        typed.trySend(text)
    }

    /** The raw bytes on screen: every write since the last reset. */
    val output: ByteArray
        get() {
            val out = ByteArrayOutputStream()
            calls.toList().let { all -> all.drop(all.lastIndexOf(RESET) + 1) }.forEach { out.write(it as ByteArray) }
            return out.toByteArray()
        }

    /** The screen decoded as UTF-8, for assertions only. */
    val text: String get() = output.toString(Charsets.UTF_8)

    companion object {
        const val RESET = "reset"
    }
}

/** Polls [condition] until it holds, failing after [timeoutMs]. ViewModels here run on real threads. */
fun eventually(timeoutMs: Long = 10_000, message: () -> String = { "condition not met" }, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError(message())
        Thread.sleep(10)
    }
}
