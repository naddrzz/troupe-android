package dev.troupe.android.terminal.protocol

import dev.troupe.android.core.protocol.TroupeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `/terminal/ws` protocol version this app speaks (ADR-0003). */
const val PROTOCOL_VERSION = 7

/** Client request `{id, type, payload, commandId?}`. Never log one: `authenticate` and `write` carry secrets. */
@Serializable
class TerminalRequest(val id: String, val type: String, val payload: JsonObject, val commandId: String? = null)

/** Server frames, mirroring termul's `WebTerminalFrame`. Later slices add `data`, `replay`, `gap` and `event`. */
sealed interface TerminalFrame {
    /** `{id, success: true, data}` or `{id, success: false, error, code}`. */
    data class Reply(
        val id: String,
        val success: Boolean,
        val data: JsonElement?,
        val error: String?,
        val code: String?,
    ) : TerminalFrame

    /** Anything this app does not understand yet. Never a crash. */
    data class Unknown(val raw: String) : TerminalFrame
}

/** Parses one server frame; never throws. */
fun parseTerminalFrame(text: String): TerminalFrame {
    val obj = runCatching { TroupeJson.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return TerminalFrame.Unknown(text)
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
    return TerminalFrame.Unknown(text)
}

/** Reply `code`s of the `/terminal/ws` handshake (termul's `WEB_TERMINAL_ERROR_CODES`). */
object TerminalErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PROTOCOL_VERSION_MISMATCH = "PROTOCOL_VERSION_MISMATCH"
    const val INVALID_CLIENT_IDENTITY = "INVALID_CLIENT_IDENTITY"
}
