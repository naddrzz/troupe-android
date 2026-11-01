package dev.troupe.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.troupe.android.attention.AttentionNotifier
import dev.troupe.android.core.nav.ChatLink
import dev.troupe.android.core.nav.Screens
import dev.troupe.android.core.nav.TroupeNavHost
import dev.troupe.android.core.nav.chatLink
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.lock.BiometricAuthenticator
import dev.troupe.android.lock.Gate
import dev.troupe.android.lock.LocalAuthenticator
import dev.troupe.android.lock.LockScreen
import dev.troupe.android.lock.LockViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A FragmentActivity because BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val lock: LockViewModel by viewModels()
    private val authenticator by lazy { BiometricAuthenticator(this) }
    @Inject lateinit var screens: Screens
    @Inject lateinit var notifier: AttentionNotifier
    /** The Agent Chat a tapped notification leads to, until the navigation has opened it. */
    private val link = MutableStateFlow<ChatLink?>(null)

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
        if (savedInstanceState == null) link.value = intent.chatLink()
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
                        val opening by link.collectAsStateWithLifecycle()
                        TroupeNavHost(screens, opening, onLinkHandled = { link.value = null })
                    }
                    val askNotifications by notifier.permissionWanted.collectAsStateWithLifecycle()
                    if (askNotifications && !hidden) NotificationRationale(notifier)
                    when (state.gate) {
                        Gate.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                        Gate.Locked -> LockScreen(onUnlock = { lock.unlock(authenticator) })
                        Gate.Unlocked -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.chatLink()?.let { link.value = it }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(process)
        super.onDestroy()
    }
}

/** Android 13+: why Troupe notifies, then the system prompt. Asked once, whatever the answer. */
@androidx.compose.runtime.Composable
private fun NotificationRationale(notifier: AttentionNotifier) {
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifier.onPermissionResult(it) }
    AlertDialog(
        onDismissRequest = { notifier.onPermissionResult(false) },
        title = { Text(stringResource(R.string.notifications_rationale_title)) },
        text = { Text(stringResource(R.string.notifications_rationale_body)) },
        confirmButton = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                else notifier.onPermissionResult(true)
            }) { Text(stringResource(R.string.notifications_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { notifier.onPermissionResult(false) }) { Text(stringResource(R.string.notifications_not_now)) }
        },
    )
}
