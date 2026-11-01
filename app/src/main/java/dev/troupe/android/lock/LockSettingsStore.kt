package dev.troupe.android.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** How long Troupe may stay in the background before it locks again. */
enum class LockDelay(val millis: Long) { Immediately(0), OneMinute(60_000), FiveMinutes(5 * 60_000) }

data class LockSettings(val enabled: Boolean = false, val delay: LockDelay = LockDelay.OneMinute)

class LockSettingsStore @Inject constructor(private val store: DataStore<Preferences>) {
    val settings: Flow<LockSettings> = store.data.map { prefs ->
        LockSettings(
            enabled = prefs[ENABLED] ?: false,
            delay = LockDelay.entries.firstOrNull { it.name == prefs[DELAY] } ?: LockDelay.OneMinute,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    suspend fun setDelay(delay: LockDelay) {
        store.edit { it[DELAY] = delay.name }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("lock_enabled")
        val DELAY = stringPreferencesKey("lock_delay")
    }
}
