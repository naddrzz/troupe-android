package dev.troupe.android.lock

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar

/** Uses MainActivity's [LockViewModel], the one that gates the app, not a copy scoped to this screen. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authenticator = LocalAuthenticator.current
    // Checked again on resume: the user may have just set up a screen lock in the device settings.
    var availability by remember { mutableStateOf(authenticator.availability()) }
    LifecycleResumeEffect(authenticator) {
        availability = authenticator.availability()
        onPauseOrDispose {}
    }
    val available = availability == LockAvailability.Available
    val palette = LocalTroupePalette.current

    Scaffold(topBar = { TroupeTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    // An unavailable lock that is already on can still be turned off.
                    .toggleable(
                        value = state.enabled,
                        enabled = available || state.enabled,
                        role = Role.Switch,
                        onValueChange = { viewModel.setEnabled(it, authenticator) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_lock), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when (availability) {
                                LockAvailability.Available -> R.string.settings_lock_body
                                LockAvailability.NoScreenLock -> R.string.settings_lock_no_screen_lock
                                LockAvailability.Unsupported -> R.string.settings_lock_unsupported
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = null, enabled = available || state.enabled)
            }
            HorizontalDivider(color = palette.border)
            Text(
                stringResource(R.string.settings_lock_delay),
                style = MaterialTheme.typography.titleSmall,
                color = palette.muted,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp).semantics { heading() },
            )
            Column(Modifier.selectableGroup()) {
                LockDelay.entries.forEach { delay ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = state.delay == delay,
                                enabled = state.enabled,
                                role = Role.RadioButton,
                                onClick = { viewModel.setDelay(delay) },
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.delay == delay, onClick = null, enabled = state.enabled)
                        Text(
                            stringResource(
                                when (delay) {
                                    LockDelay.Immediately -> R.string.settings_lock_delay_immediately
                                    LockDelay.OneMinute -> R.string.settings_lock_delay_one_minute
                                    LockDelay.FiveMinutes -> R.string.settings_lock_delay_five_minutes
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
