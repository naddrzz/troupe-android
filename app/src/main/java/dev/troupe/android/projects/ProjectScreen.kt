package dev.troupe.android.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.ui.TroupeTopBar
import dev.troupe.android.terminal.TerminalsTab
import dev.troupe.android.terminal.TerminalsViewModel

/**
 * A Project: its Agent Chats and its Terminals, one tab apart. Opens on Terminals. [terminals] is
 * the same ViewModel the Terminals tab uses; the header shows its connection.
 */
@Composable
fun ProjectScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenTerminal: (terminalId: String) -> Unit,
    terminals: TerminalsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(TERMINALS) }
    val connection by terminals.connection.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TroupeTopBar(projectName, onBack = onBack, connection = connection, onRetry = terminals::retryConnection)
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == CHATS, onClick = { tab = CHATS }, text = { Text(stringResource(R.string.project_tab_chats)) })
                Tab(selected = tab == TERMINALS, onClick = { tab = TERMINALS }, text = { Text(stringResource(R.string.project_tab_terminals)) })
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    CHATS -> Text(
                        stringResource(R.string.project_chats_placeholder),
                        color = LocalTroupePalette.current.muted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> TerminalsTab(onOpenTerminal = onOpenTerminal, viewModel = terminals)
                }
            }
        }
    }
}

private const val CHATS = 0
private const val TERMINALS = 1
