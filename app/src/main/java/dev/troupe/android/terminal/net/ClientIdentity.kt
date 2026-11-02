package dev.troupe.android.terminal.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Who is connecting: [deviceId] is minted once per install, [clientInstanceId] once per process. */
data class ClientIdentity(val deviceId: String, val clientInstanceId: String)

@Singleton
class ClientIdentityStore @Inject constructor(private val store: DataStore<Preferences>) {
    private val clientInstanceId = UUID.randomUUID().toString()

    suspend fun get(): ClientIdentity {
        var deviceId = ""
        store.edit { prefs ->
            deviceId = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it }
        }
        return ClientIdentity(deviceId, clientInstanceId)
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
