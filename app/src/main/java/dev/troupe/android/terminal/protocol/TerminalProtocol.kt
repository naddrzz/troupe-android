package dev.troupe.android.terminal.protocol

import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The `/terminal/ws` protocol version this app speaks (ADR-0003). */
const val PROTOCOL_VERSION = 7

/** Client request `{id, type, payload, commandId?}`. Never log one: `authenticate` and `write` carry secrets. */
@Serializable
class TerminalRequest(val id: String, val type: String, val payload: JsonObject, val commandId: String? = null)

/** Server frames, mirroring termul's `WebTerminalFrame`. `gap` and `event` arrive with later slices. */
sealed interface TerminalFrame {
    /** `{id, success: true, data}` or `{id, success: false, error, code}`. */
    data class Reply(
        val id: String,
        val success: Boolean,
        val data: JsonElement?,
        val error: String?,
        val code: String?,
    ) : TerminalFrame

    /** One live output chunk. [data] is raw terminal output; it is never decoded as text in Kotlin. */
    class Data(val terminalId: String, val seq: Long, val data: ByteArray) : TerminalFrame

    /** The unseen output sent on `attach`. [reset] means: discard what is rendered before writing [chunks]. */
    class Replay(
        val terminalId: String,
        val chunks: List<Chunk>,
        val gap: Boolean,
        val reset: Boolean,
        val logEpoch: String,
        val latestSeq: Long,
    ) : TerminalFrame

    class Chunk(val seq: Long, val data: ByteArray)

    /** Anything this app does not understand yet. Never a crash. */
    data class Unknown(val raw: String) : TerminalFrame
}

/** Parses one server frame; never throws. */
fun parseTerminalFrame(text: String): TerminalFrame = runCatching { parse(text) }.getOrNull() ?: TerminalFrame.Unknown(text)

private fun parse(text: String): TerminalFrame? {
    val obj = TroupeJson.parseToJsonElement(text).jsonObject
    val id = obj["id"]?.jsonPrimitive?.contentOrNull
    val success = obj["success"]?.jsonPrimitive?.booleanOrNull
    if (id != null && success != null) {
        return TerminalFrame.Reply(
            id = id,
            success = success,
            data = obj["data"],
            error = obj["error"]?.jsonPrimitive?.contentOrNull,
            code = obj["code"]?.jsonPrimitive?.contentOrNull,
        )
    }
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "data" -> TerminalFrame.Data(
            terminalId = obj.getValue("terminalId").jsonPrimitive.content,
            seq = obj.getValue("seq").jsonPrimitive.long,
            data = obj.getValue("data").bytes(),
        )
        "replay" -> TerminalFrame.Replay(
            terminalId = obj.getValue("terminalId").jsonPrimitive.content,
            chunks = obj.getValue("chunks").jsonArray.map {
                val chunk = it.jsonObject
                TerminalFrame.Chunk(chunk.getValue("seq").jsonPrimitive.long, chunk.getValue("data").bytes())
            },
            gap = obj["gap"]?.jsonPrimitive?.booleanOrNull ?: false,
            reset = obj["reset"]?.jsonPrimitive?.booleanOrNull ?: false,
            logEpoch = obj["logEpoch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            latestSeq = obj.getValue("latestSeq").jsonPrimitive.long,
        )
        else -> null
    }
}

/** Output bytes travel as a JSON array of 0..255 integers. */
private fun JsonElement.bytes(): ByteArray {
    val values = jsonArray
    return ByteArray(values.size) { i -> values[i].jsonPrimitive.int.also { require(it in 0..255) }.toByte() }
}

/** The `spawn` reply: the new Terminal plus its claim, the credential that lets a connection attach to it. */
@Serializable
class SpawnedTerminal(
    val id: String,
    val shell: String,
    val cwd: String,
    val pid: Int,
    val cols: Int,
    val rows: Int,
    val claim: String,
) {
    override fun toString() = "SpawnedTerminal(id=$id, shell=$shell, cwd=$cwd, pid=$pid, cols=$cols, rows=$rows, claim=<redacted>)"
}

/** Reply `code`s of `/terminal/ws` (termul's `WEB_TERMINAL_ERROR_CODES`). */
object TerminalErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PROTOCOL_VERSION_MISMATCH = "PROTOCOL_VERSION_MISMATCH"
    const val INVALID_CLIENT_IDENTITY = "INVALID_CLIENT_IDENTITY"
}
