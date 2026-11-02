package dev.troupe.android.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette

/** The activity's [Authenticator]; provided by MainActivity, which owns the BiometricPrompt. */
val LocalAuthenticator = staticCompositionLocalOf<Authenticator> { error("No Authenticator provided") }

/** Opaque and full screen, drawn over the app; opens the prompt once when shown, and again on Unlock. */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalTroupePalette.current.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.lock_unlock))
            }
        }
    }
}
