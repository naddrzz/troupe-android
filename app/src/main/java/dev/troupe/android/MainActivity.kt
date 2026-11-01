package dev.troupe.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.troupe.android.core.nav.TroupeNavHost
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.lock.BiometricAuthenticator
import dev.troupe.android.lock.Gate
import dev.troupe.android.lock.LocalAuthenticator
import dev.troupe.android.lock.LockScreen
import dev.troupe.android.lock.LockViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val lock: LockViewModel by viewModels()
    private val authenticator by lazy { BiometricAuthenticator(this) }

    /** The whole app leaving or returning to the foreground, not this activity being covered by a dialog. */
    private val process = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> lock.onForeground()
            Lifecycle.Event.ON_STOP -> lock.onBackground()
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(process)
        // Collected from onCreate on Main.immediate, so the flag is set before the first frame.
        // Kept on for as long as the lock is on: the recent-apps thumbnail is taken while still unlocked.
        lifecycleScope.launch {
            lock.state.map { it.enabled || it.gate != Gate.Unlocked }.distinctUntilChanged().collect { hidden ->
                if (hidden) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            CompositionLocalProvider(LocalAuthenticator provides authenticator) {
                TroupeTheme {
                    val state by lock.state.collectAsStateWithLifecycle()
                    val hidden = state.gate != Gate.Unlocked
                    val focus = LocalFocusManager.current
                    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
                    // The app stays composed under the lock so navigation and open Terminals survive it,
                    // but it is covered, silent to TalkBack and gets no keys.
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (hidden) Modifier.clearAndSetSemantics {}.onPreviewKeyEvent { true } else Modifier),
                    ) {
                        TroupeNavHost()
                    }
                    when (state.gate) {
                        Gate.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                        Gate.Locked -> LockScreen(onUnlock = { lock.unlock(authenticator) })
                        Gate.Unlocked -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(process)
        super.onDestroy()
    }
}
