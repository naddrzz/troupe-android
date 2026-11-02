package dev.troupe.android.core.net

enum class ConnectionFailure { Unreachable, Unauthorized, ProtocolMismatch, Refused }

/** Where a connection to a Paired Server stands; shown in the headers. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** The connection dropped; attempt [attempt] starts in [inMillis] ms. */
    data class Reconnecting(val attempt: Int, val inMillis: Long) : ConnectionState
    /** The server stopped on purpose (close code 1001); `/health` is polled until it answers. */
    data object ServerOffline : ConnectionState
    /** Final: nothing reconnects until the user pairs again. */
    data class Failed(val reason: ConnectionFailure, val message: String? = null) : ConnectionState
    data object Closed : ConnectionState
}
