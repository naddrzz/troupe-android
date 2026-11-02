package dev.troupe.android.servers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.troupe.android.core.protocol.TroupeJson
import dev.troupe.android.pairing.PairingInput
import dev.troupe.android.pairing.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

/**
 * A Troupe Server the app remembers. Holds no token: that lives encrypted in [PairedServerRepository].
 * [stayConnected]: its connection is held in the background, so it still notifies with the app closed.
 */
data class PairedServer(val id: String, val name: String, val address: ServerAddress, val stayConnected: Boolean = false)

@Serializable
private data class StoredServer(val id: String, val name: String, val baseUrl: String, val stayConnected: Boolean = false)

/**
 * Paired Servers in one JSON preference; each token is AEAD ciphertext under `token_<id>`,
 * with the server id as associated data.
 */
class PairedServerRepository(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    val servers: Flow<List<PairedServer>> = store.data.map { prefs ->
        stored(prefs).map { PairedServer(it.id, it.name, ServerAddress.of(it.baseUrl), it.stayConnected) }
    }

    suspend fun server(id: String): PairedServer? = servers.first().firstOrNull { it.id == id }

    /** Adds a server, or replaces the token of the one already paired at the same base URL (keeping its settings). */
    suspend fun add(name: String, input: PairingInput): PairedServer {
        val baseUrl = input.address.baseUrl.toString()
        lateinit var saved: StoredServer
        store.edit { prefs ->
            val list = stored(prefs)
            val existing = list.firstOrNull { it.baseUrl == baseUrl }
            val id = existing?.id ?: UUID.randomUUID().toString()
            saved = StoredServer(id, name, baseUrl, existing?.stayConnected ?: false)
            prefs[SERVERS] = TroupeJson.encodeToString(list.filterNot { it.id == id } + saved)
            prefs[tokenKey(id)] = Base64.getEncoder().encodeToString(cipher.encrypt(input.token, id))
        }
        return PairedServer(saved.id, saved.name, input.address, saved.stayConnected)
    }

    /** The server's token; null when there is none or it can no longer be decrypted (pair again). */
    suspend fun token(serverId: String): String? {
        val encoded = store.data.first()[tokenKey(serverId)] ?: return null
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
            prefs[SERVERS] = TroupeJson.encodeToString(stored(prefs).filterNot { it.id == id })
            prefs.remove(tokenKey(id))
        }
    }

    private fun stored(prefs: Preferences): List<StoredServer> =
        prefs[SERVERS]?.let { TroupeJson.decodeFromString<List<StoredServer>>(it) }.orEmpty()

    private fun tokenKey(id: String) = stringPreferencesKey("token_$id")

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
    }
}
