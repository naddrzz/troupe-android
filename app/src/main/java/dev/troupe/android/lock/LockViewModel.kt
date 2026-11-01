package dev.troupe.android.lock

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Milliseconds on a monotonic clock, so changing the device time can't skip the lock. */
fun interface Clock {
    fun now(): Long
}

@Module
@InstallIn(SingletonComponent::class)
object LockModule {
    @Provides fun clock(): Clock = Clock { SystemClock.elapsedRealtime() }
}

/** [Loading] until the stored setting is read, so server content never shows before the lock decides. */
enum class Gate { Loading, Locked, Unlocked }

data class LockUiState(val gate: Gate, val enabled: Boolean, val delay: LockDelay)

/**
 * The app lock, scoped to MainActivity: a new activity is a new launch, which locks when the lock is on.
 * MainActivity reports the process going to and coming back from the background.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val store: LockSettingsStore,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(LockUiState(Gate.Loading, enabled = false, delay = LockDelay.OneMinute))
    val state: StateFlow<LockUiState> = _state.asStateFlow()
    private var backgroundedAt: Long? = null

    /** The system's PIN screen sends Troupe to the background while a prompt is open; that must not count. */
    private var prompting = false

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                _state.update {
                    val gate = if (it.gate == Gate.Loading) (if (s.enabled) Gate.Locked else Gate.Unlocked) else it.gate
                    LockUiState(gate, s.enabled, s.delay)
                }
            }
        }
    }

    fun onBackground() {
        if (!prompting) backgroundedAt = clock.now()
    }

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val s = _state.value
        if (s.enabled && s.gate == Gate.Unlocked && clock.now() - since >= s.delay.millis) {
            _state.update { it.copy(gate = Gate.Locked) }
        }
    }

    /**
     * Removing the screen lock takes the old credential, so a device that no longer has one lets the owner in
     * rather than leaving Troupe locked for good.
     */
    fun unlock(authenticator: Authenticator) {
        if (authenticator.availability() == LockAvailability.NoScreenLock) {
            _state.update { it.copy(gate = Gate.Unlocked) }
        } else {
            prompt(authenticator) { _state.update { it.copy(gate = Gate.Unlocked) } }
        }
    }

    /** Turning the lock on needs a successful prompt; turning it off doesn't (the app is already unlocked). */
    fun setEnabled(enabled: Boolean, authenticator: Authenticator) {
        if (!enabled) {
            viewModelScope.launch { store.setEnabled(false) }
        } else if (authenticator.availability() == LockAvailability.Available) {
            prompt(authenticator) { store.setEnabled(true) }
        }
    }

    fun setDelay(delay: LockDelay) {
        viewModelScope.launch { store.setDelay(delay) }
    }

    private fun prompt(authenticator: Authenticator, onSuccess: suspend () -> Unit) {
        if (prompting) return
        prompting = true
        viewModelScope.launch {
            val passed = try {
                authenticator.authenticate()
            } finally {
                prompting = false
            }
            if (passed) onSuccess()
        }
    }
}
