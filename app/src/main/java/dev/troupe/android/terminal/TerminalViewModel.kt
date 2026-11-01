package dev.troupe.android.terminal

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.terminal.net.TerminalClaims
import dev.troupe.android.terminal.net.TerminalConnectFailed
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.net.TerminalSocket
import dev.troupe.android.terminal.protocol.TerminalFrame
import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalKey
import dev.troupe.android.terminal.render.TerminalRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject

sealed interface TerminalUi {
    data object Connecting : TerminalUi
    data object Live : TerminalUi
    data class Failed(@StringRes val message: Int, val arg: String? = null) : TerminalUi
}

/**
 * One open Terminal: attaches with the claim kept at spawn, draws its output into the bound
 * [TerminalRenderer] and sends what the user types as ledgered `write`s. Output bytes go to the
 * renderer untouched. Re-attach after a drop, exit and view-only arrive with later slices.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: TerminalConnections,
    private val claims: TerminalClaims,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val terminalId: String = checkNotNull(savedState["terminalId"])
    private val _ui = MutableStateFlow<TerminalUi>(TerminalUi.Connecting)
    val ui: StateFlow<TerminalUi> = _ui.asStateFlow()

    private val attached = CompletableDeferred<TerminalSocket>()
    private val outgoing = Channel<String>(Channel.UNLIMITED)

    // Touched only on the main thread (viewModelScope and the screen).
    private var renderer: TerminalRenderer? = null
    private var binding: Job? = null
    private val unrendered = mutableListOf<(TerminalRenderer) -> Unit>()

    init {
        viewModelScope.launch { attach() }
    }

    /** Draws into [renderer] from now on (with any output that arrived before) and forwards its input and size. */
    @OptIn(FlowPreview::class)
    fun bind(renderer: TerminalRenderer) {
        binding?.cancel()
        this.renderer = renderer
        unrendered.forEach { it(renderer) }
        unrendered.clear()
        binding = viewModelScope.launch {
            launch { renderer.input.collect { outgoing.send(it) } }
            val socket = attached.await()
            renderer.size.filterNotNull().distinctUntilChanged().debounce(RESIZE_DEBOUNCE_MS).collect { resize(socket, it) }
        }
    }

    fun unbind(renderer: TerminalRenderer) {
        if (this.renderer !== renderer) return
        binding?.cancel()
        this.renderer = null
    }

    fun onKey(key: TerminalKey) {
        outgoing.trySend(key.sequence)
    }

    /** Sends [text] (the clipboard) as typed input in one `write`. */
    fun paste(text: String) {
        if (text.isNotEmpty()) outgoing.trySend(text)
    }

    private suspend fun attach() {
        val claim = claims.claim(serverId, terminalId) ?: return fail(R.string.terminal_unavailable)
        val socket = try {
            connections.socket(serverId)
        } catch (e: TerminalConnectFailed) {
            val message = e.userMessage()
            return fail(message.text, message.arg)
        }
        // Subscribe before attaching: the server sends the replay ahead of the attach reply.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { socket.frames.collect(::onFrame) }
        val payload = buildJsonObject {
            put("terminalId", terminalId)
            put("claim", claim)
            put("lastSeq", 0)
        }
        val reply = try {
            socket.request("attach", payload)
        } catch (e: IOException) {
            return fail(R.string.pair_error_unreachable)
        }
        if (!reply.success) return fail(R.string.terminal_unavailable)
        _ui.value = TerminalUi.Live
        attached.complete(socket)
        sendInput(socket)
    }

    private fun fail(@StringRes message: Int, arg: String? = null) {
        _ui.value = TerminalUi.Failed(message, arg)
    }

    private fun onFrame(frame: TerminalFrame) {
        when (frame) {
            is TerminalFrame.Replay -> if (frame.terminalId == terminalId) {
                if (frame.reset) render { it.reset() }
                frame.chunks.forEach { chunk -> render { it.write(chunk.data) } }
            }
            is TerminalFrame.Data -> if (frame.terminalId == terminalId) render { it.write(frame.data) }
            else -> Unit
        }
    }

    private fun render(op: (TerminalRenderer) -> Unit) {
        renderer?.let(op) ?: unrendered.add(op)
    }

    /** One `write` per burst: input typed while a write is in flight goes out together in the next one. */
    private suspend fun sendInput(socket: TerminalSocket) {
        for (first in outgoing) {
            val data = buildString {
                append(first)
                while (true) append(outgoing.tryReceive().getOrNull() ?: break)
            }
            val payload = buildJsonObject {
                put("terminalId", terminalId)
                put("data", data)
            }
            // A refused or lost write is dropped for now; reconnect and view-only arrive with later slices.
            try {
                socket.request("write", payload, ledgered = true)
            } catch (e: IOException) {
                return
            }
        }
    }

    private suspend fun resize(socket: TerminalSocket, size: TermSize) {
        val payload = buildJsonObject {
            put("terminalId", terminalId)
            put("cols", size.cols)
            put("rows", size.rows)
        }
        try {
            socket.request("resize", payload)
        } catch (e: IOException) {
            // The next size change retries; reconnect arrives with a later slice.
        }
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 150L
    }
}
