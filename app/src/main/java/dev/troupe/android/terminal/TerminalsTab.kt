package dev.troupe.android.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette

/** A Project's Terminals tab: "New terminal" spawns one and opens it. */
@Composable
fun TerminalsTab(onOpenTerminal: (terminalId: String) -> Unit, viewModel: TerminalsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val palette = LocalTroupePalette.current
    LaunchedEffect(state.openTerminal) {
        state.openTerminal?.let {
            viewModel.onTerminalOpened()
            onOpenTerminal(it)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.terminals_empty),
            color = palette.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = viewModel::spawn, enabled = !state.spawning, modifier = Modifier.heightIn(min = 48.dp)) {
            if (state.spawning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.terminals_starting), Modifier.padding(start = 8.dp))
            } else {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.terminals_new), Modifier.padding(start = 8.dp))
            }
        }
        state.error?.let {
            Text(
                stringResource(it.text, it.arg.orEmpty()),
                color = palette.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
