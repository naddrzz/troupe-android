package dev.troupe.android.servers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

/**
 * A Troupe Server the app remembers. Holds no token: that lives encrypted in [PairedServerRepository].
 * [stayConnected]: its connection is held in the background, so it still notifies with the app closed.
 * [hasToken]: true for gated servers (token stored encrypted), false for ungated local servers.
 */
data class PairedServer(
    val id: String,
    val name: String,
    val address: ServerAddress,
    val stayConnected: Boolean = false,
    val hasToken: Boolean = true,
)

@Serializable
private data class StoredServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val stayConnected: Boolean = false,
    val hasToken: Boolean = true,
)

/**
 * Paired Servers in one JSON preference; each token is AEAD ciphertext under `token_<id>`,
 * with the server id as associated data.
 */
class PairedServerRepository(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    private val _tokenUpdated = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Emits a Paired Server's id whenever its credentials are added or updated. */
    val tokenUpdated: SharedFlow<String> = _tokenUpdated.asSharedFlow()

    val servers: Flow<List<PairedServer>> = store.data.map { prefs ->
        stored(prefs).map { PairedServer(it.id, it.name, ServerAddress.of(it.baseUrl), it.stayConnected, it.hasToken) }
    }

    /** Currently active Paired Server ID for the mobile workspace shell, falling back to the first server. */
    val activeServerId: Flow<String?> = store.data.map { prefs ->
        val list = stored(prefs)
        val saved = prefs[ACTIVE_SERVER_ID]
        when {
            saved != null && list.any { it.id == saved } -> saved
            else -> list.firstOrNull()?.id
        }
    }

    suspend fun server(id: String): PairedServer? = servers.first().firstOrNull { it.id == id }

    /** Adds a server, or replaces the token of the one already paired at the same base URL (keeping its settings). */
    suspend fun add(name: String, input: PairingInput): PairedServer =
        upsert(name, input.address, input.token.takeIf { it.isNotEmpty() })

    /** Adds or updates a server (gated when [token] is non-blank, ungated when null/blank) and sets it active. */
    suspend fun upsert(name: String, address: ServerAddress, token: String?, makeActive: Boolean = true): PairedServer {
        val baseUrl = address.baseUrl.toString()
        val cleanToken = token?.trim()?.takeIf { it.isNotEmpty() }
        lateinit var saved: StoredServer
        store.edit { prefs ->
            val list = stored(prefs)
            val existing = list.firstOrNull { it.baseUrl == baseUrl }
            val id = existing?.id ?: UUID.randomUUID().toString()
            saved = StoredServer(
                id = id,
                name = name,
                baseUrl = baseUrl,
                stayConnected = existing?.stayConnected ?: false,
                hasToken = cleanToken != null,
            )
            prefs[SERVERS] = TroupeJson.encodeToString(list.filterNot { it.id == id } + saved)
            if (cleanToken != null) {
                prefs[tokenKey(id)] = Base64.getEncoder().encodeToString(cipher.encrypt(cleanToken, id))
            } else {
                prefs.remove(tokenKey(id))
            }
            if (makeActive || prefs[ACTIVE_SERVER_ID] == null) {
                prefs[ACTIVE_SERVER_ID] = id
            }
        }
        _tokenUpdated.tryEmit(saved.id)
        return PairedServer(saved.id, saved.name, address, saved.stayConnected, saved.hasToken)
    }

    suspend fun setActiveServer(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_SERVER_ID) else prefs[ACTIVE_SERVER_ID] = id
        }
    }

    /** Updates a Paired Server's token (used by TokenGateScreen after a mid-session 401/unauthorized). */
    suspend fun updateToken(serverId: String, token: String?) {
        val cleanToken = token?.trim()?.takeIf { it.isNotEmpty() }
        var updated = false
        store.edit { prefs ->
            val list = stored(prefs)
            if (list.none { it.id == serverId }) return@edit
            updated = true
            prefs[SERVERS] = TroupeJson.encodeToString(
                list.map { if (it.id == serverId) it.copy(hasToken = cleanToken != null) else it },
            )
            if (cleanToken != null) {
                prefs[tokenKey(serverId)] = Base64.getEncoder().encodeToString(cipher.encrypt(cleanToken, serverId))
            } else {
                prefs.remove(tokenKey(serverId))
            }
        }
        if (updated) _tokenUpdated.tryEmit(serverId)
    }

    /**
     * The server's token; `""` for an ungated server (`hasToken == false`), or `null` when the
     * server is unknown or a stored token can no longer be decrypted (pair again).
     */
    suspend fun token(serverId: String): String? {
        val prefs = store.data.first()
        val entry = stored(prefs).firstOrNull { it.id == serverId } ?: return null
        if (!entry.hasToken) return ""
        val encoded = prefs[tokenKey(serverId)] ?: return null
        return try {
            cipher.decrypt(Base64.getDecoder().decode(encoded), serverId)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    suspend fun rename(id: String, name: String) {
        store.edit { prefs ->
            prefs[SERVERS] = TroupeJson.encodeToString(stored(prefs).map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    suspend fun setStayConnected(id: String, value: Boolean) {
        store.edit { prefs ->
            prefs[SERVERS] = TroupeJson.encodeToString(stored(prefs).map { if (it.id == id) it.copy(stayConnected = value) else it })
        }
    }

    /** The background connection's Disconnect: every "Stay connected in background" goes off. */
    suspend fun stopStayingConnected() {
        store.edit { prefs ->
            prefs[SERVERS] = TroupeJson.encodeToString(stored(prefs).map { it.copy(stayConnected = false) })
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            val remaining = stored(prefs).filterNot { it.id == id }
            prefs[SERVERS] = TroupeJson.encodeToString(remaining)
            prefs.remove(tokenKey(id))
            if (prefs[ACTIVE_SERVER_ID] == id) {
                val next = remaining.firstOrNull()?.id
                if (next != null) prefs[ACTIVE_SERVER_ID] = next else prefs.remove(ACTIVE_SERVER_ID)
            }
        }
    }

    private fun stored(prefs: Preferences): List<StoredServer> =
        prefs[SERVERS]?.let { TroupeJson.decodeFromString<List<StoredServer>>(it) }.orEmpty()

    private fun tokenKey(id: String) = stringPreferencesKey("token_$id")

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
    }
}
