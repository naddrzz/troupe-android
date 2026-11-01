package dev.troupe.android.terminal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.servers.TokenCipher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.util.Base64

/** Where output of a Terminal was read up to: `lastSeq` only means something within the log `logEpoch` names. */
data class TerminalCursor(val logEpoch: String = "", val lastSeq: Long = 0)

/** A Terminal's claim (the credential to attach) and its replay cursor. */
@Serializable
class StoredTerminal(val claim: String, val logEpoch: String = "", val lastSeq: Long = 0) {
    val cursor get() = TerminalCursor(logEpoch, lastSeq)

    override fun toString() = "StoredTerminal(logEpoch=$logEpoch, lastSeq=$lastSeq, claim=<redacted>)"
}

/**
 * Claims and cursors per (Paired Server, Terminal), so a Terminal can be re-attached after
 * process death. Each record is AEAD ciphertext bound to its server and Terminal ids.
 */
class TerminalCursorStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    suspend fun get(serverId: String, terminalId: String): StoredTerminal? =
        read(store.data.first(), serverId, terminalId)

    /** Keeps a newly issued [claim]. A claim for the same log keeps its cursor. */
    suspend fun putClaim(serverId: String, terminalId: String, claim: String) = edit(serverId, terminalId) {
        StoredTerminal(claim, it?.logEpoch.orEmpty(), it?.lastSeq ?: 0)
    }

    /** Moves the cursor; a Terminal without a stored claim stays absent. */
    suspend fun putCursor(serverId: String, terminalId: String, cursor: TerminalCursor) = edit(serverId, terminalId) {
        it?.let { StoredTerminal(it.claim, cursor.logEpoch, cursor.lastSeq) }
    }

    suspend fun remove(serverId: String, terminalId: String) = edit(serverId, terminalId) { null }

    private suspend fun edit(serverId: String, terminalId: String, change: (StoredTerminal?) -> StoredTerminal?) {
        store.edit { prefs ->
            val key = key(serverId, terminalId)
            when (val next = change(read(prefs, serverId, terminalId))) {
                null -> prefs.remove(key)
                else -> prefs[key] = Base64.getEncoder().encodeToString(
                    cipher.encrypt(TroupeJson.encodeToString(StoredTerminal.serializer(), next), associated(serverId, terminalId)),
                )
            }
        }
    }

    private fun read(prefs: Preferences, serverId: String, terminalId: String): StoredTerminal? {
        val encoded = prefs[key(serverId, terminalId)] ?: return null
        val json = cipher.decrypt(Base64.getDecoder().decode(encoded), associated(serverId, terminalId))
        return TroupeJson.decodeFromString(StoredTerminal.serializer(), json)
    }

    private fun key(serverId: String, terminalId: String) = stringPreferencesKey("terminal_${serverId}_$terminalId")

    private fun associated(serverId: String, terminalId: String) = "$serverId/$terminalId"
}
