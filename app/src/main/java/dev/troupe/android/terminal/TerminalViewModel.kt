package dev.troupe.android.terminal

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.troupe.android.R
import dev.troupe.android.terminal.net.TerminalConnectFailed
import dev.troupe.android.terminal.net.TerminalConnections
import dev.troupe.android.terminal.net.TerminalSocket
import dev.troupe.android.terminal.protocol.TerminalErrorCodes
import dev.troupe.android.terminal.protocol.TerminalFrame
import dev.troupe.android.terminal.render.TermSize
import dev.troupe.android.terminal.render.TerminalKey
import dev.troupe.android.terminal.render.TerminalRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** The process ended; its output stays on screen. [exitCode] is null when a signal ended it. */
    data class Exited(val exitCode: Int?) : TerminalUi
}

/**
 * One open Terminal: attaches with its stored claim, draws its output into the bound
 * [TerminalRenderer] and sends what the user types as ledgered `write`s (see [sendInput]).
 *
 * A new screen starts blank, so its first attach asks for the whole retained log. From then on
 * the in-memory cursor `(logEpoch, lastSeq)` says what is on screen: live `data` is drawn only
 * when it is the next seq, and anything else, or a `gap`, re-attaches from the cursor ([reattach]).
 * The cursor is also persisted with the claim, at most once a second.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val connections: TerminalConnections,
    private val cursors: TerminalCursorStore,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedState["serverId"])
    private val terminalId: String = checkNotNull(savedState["terminalId"])
    private val _ui = MutableStateFlow<TerminalUi>(TerminalUi.Connecting)
    val ui: StateFlow<TerminalUi> = _ui.asStateFlow()

    private val live = CompletableDeferred<Unit>()
    private val outgoing = Channel<String>(Channel.UNLIMITED)

    // Touched only on the main thread (viewModelScope and the screen).
    private var renderer: TerminalRenderer? = null
    private var binding: Job? = null
    private val unrendered = mutableListOf<(TerminalRenderer) -> Unit>()
    private var socket: TerminalSocket? = null
    private var framesJob: Job? = null
    private var inputJob: Job? = null
    private var saveJob: Job? = null
    private var cursor = TerminalCursor()
    private var attaching = false
    /** Between sending `attach` and its `replay`: live frames are dropped, the replay covers them. */
    private var awaitingReplay = false

    init {
        reattach()
    }

    /**
     * Attaches again from the cursor of what is on screen, over the Paired Server's current
     * connection. Also the hook for a reconnect. Does nothing while an attach is in flight.
     */
    fun reattach() {
        if (attaching) return
        attaching = true
        awaitingReplay = true
        viewModelScope.launch {
            try {
                attach()
            } finally {
                attaching = false
            }
        }
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
            live.await()
            renderer.size.filterNotNull().distinctUntilChanged().debounce(RESIZE_DEBOUNCE_MS).collect(::resize)
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

    override fun onCleared() {
        saveJob?.cancel()
        val last = cursor
        connections.background.launch { cursors.putCursor(serverId, terminalId, last) }
        connections.detach(serverId, terminalId)
    }

    private suspend fun attach() {
        val claim = cursors.get(serverId, terminalId)?.claim ?: return fail(R.string.terminal_unavailable)
        val current = try {
            connections.socket(serverId)
        } catch (e: TerminalConnectFailed) {
            val message = e.userMessage()
            return fail(message.text, message.arg)
        }
        if (current !== socket) {
            framesJob?.cancel()
            socket = current
            // Subscribe before attaching: the server sends the replay ahead of the attach reply.
            framesJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { current.frames.collect(::onFrame) }
        }
        val reply = try {
            current.request("attach", attachPayload(claim))
        } catch (e: IOException) {
            return fail(R.string.pair_error_unreachable)
        }
        if (!reply.success) {
            if (reply.code == TerminalErrorCodes.UNAUTHORIZED) {
                cursors.remove(serverId, terminalId)
                return fail(R.string.terminal_unavailable)
            }
            return fail(R.string.pair_error_server, reply.error)
        }
        if (_ui.value !is TerminalUi.Exited) _ui.value = TerminalUi.Live
        live.complete(Unit)
        if (inputJob == null) inputJob = viewModelScope.launch { sendInput() }
    }

    /** A nonzero cursor goes out with its epoch, or with `""` when none is known (which forces a full replay). */
    private fun attachPayload(claim: String) = buildJsonObject {
        put("terminalId", terminalId)
        put("claim", claim)
        put("lastSeq", cursor.lastSeq)
        if (cursor.logEpoch.isNotEmpty() || cursor.lastSeq > 0) put("logEpoch", cursor.logEpoch)
    }

    private fun fail(@StringRes message: Int, arg: String? = null) {
        _ui.value = TerminalUi.Failed(message, arg)
    }

    private fun onFrame(frame: TerminalFrame) {
        when (frame) {
            is TerminalFrame.Replay -> if (frame.terminalId == terminalId) onReplay(frame)
            is TerminalFrame.Data -> if (frame.terminalId == terminalId && !awaitingReplay) {
                if (frame.seq == cursor.lastSeq + 1) {
                    render { it.write(frame.data) }
                    advance(cursor.copy(lastSeq = frame.seq))
                } else {
                    reattach()
                }
            }
            is TerminalFrame.Gap -> if (frame.terminalId == terminalId && !awaitingReplay) reattach()
            is TerminalFrame.Exit -> if (frame.terminalId == terminalId) _ui.value = TerminalUi.Exited(frame.exitCode)
            else -> Unit
        }
    }

    /** Draws what the screen lacks: everything after a `reset`, otherwise only chunks past the cursor. */
    private fun onReplay(frame: TerminalFrame.Replay) {
        awaitingReplay = false
        val after = if (frame.reset) 0 else cursor.lastSeq
        if (frame.reset) render { it.reset() }
        frame.chunks.filter { it.seq > after }.forEach { chunk -> render { it.write(chunk.data) } }
        advance(TerminalCursor(frame.logEpoch, maxOf(frame.latestSeq, after)))
        frame.exit?.let { _ui.value = TerminalUi.Exited(it.exitCode) }
    }

    private fun advance(next: TerminalCursor) {
        cursor = next
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            delay(CURSOR_SAVE_MS)
            cursors.putCursor(serverId, terminalId, cursor)
        }
    }

    private fun render(op: (TerminalRenderer) -> Unit) {
        renderer?.let(op) ?: unrendered.add(op)
    }

    /**
     * Every write to the Terminal goes out here: one ledgered `write` per burst, so input typed
     * while a write is in flight goes out together in the next one.
     */
    private suspend fun sendInput() {
        for (first in outgoing) {
            val data = buildString {
                append(first)
                while (true) append(outgoing.tryReceive().getOrNull() ?: break)
            }
            val payload = buildJsonObject {
                put("terminalId", terminalId)
                put("data", data)
            }
            // A refused or lost write is dropped; retrying it is the reconnect's job.
            try {
                socket?.request("write", payload, ledgered = true)
            } catch (e: IOException) {
                continue
            }
        }
    }

    private suspend fun resize(size: TermSize) {
        val payload = buildJsonObject {
            put("terminalId", terminalId)
            put("cols", size.cols)
            put("rows", size.rows)
        }
        try {
            socket?.request("resize", payload)
        } catch (e: IOException) {
            // The next size change retries.
        }
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 150L
        const val CURSOR_SAVE_MS = 1_000L
    }
}
