package dev.troupe.android.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.troupe.android.R
import dev.troupe.android.chat.ChatsTab
import dev.troupe.android.chat.ChatsViewModel
import dev.troupe.android.core.ui.TroupeTopBar
import dev.troupe.android.terminal.TerminalsTab
import dev.troupe.android.terminal.TerminalsViewModel

/**
 * A Project: its Agent Chats and its Terminals, one tab apart. Opens on Terminals. [terminals] and
 * [chats] are the ViewModels the tabs use; the header shows the connection of the tab shown.
 */
@Composable
fun ProjectScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenTerminal: (terminalId: String) -> Unit,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    terminals: TerminalsViewModel = hiltViewModel(),
    chats: ChatsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(TERMINALS) }
    val terminalConnection by terminals.connection.collectAsStateWithLifecycle()
    val chatConnection by chats.connection.collectAsStateWithLifecycle()
    val viewOnly by terminals.viewOnly.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        val onChats = tab == CHATS
        TroupeTopBar(
            projectName,
            onBack = onBack,
            connection = if (onChats) chatConnection else terminalConnection,
            onRetry = if (onChats) chats::retryConnection else terminals::retryConnection,
            viewOnly = viewOnly,
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == CHATS, onClick = { tab = CHATS }, text = { Text(stringResource(R.string.project_tab_chats)) })
                Tab(selected = tab == TERMINALS, onClick = { tab = TERMINALS }, text = { Text(stringResource(R.string.project_tab_terminals)) })
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    CHATS -> ChatsTab(onOpenChat = onOpenChat, viewModel = chats)
                    else -> TerminalsTab(onOpenTerminal = onOpenTerminal, viewModel = terminals)
                }
            }
        }
    }
}

private const val CHATS = 0
private const val TERMINALS = 1
