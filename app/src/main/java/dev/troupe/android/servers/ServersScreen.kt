package dev.troupe.android.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.troupe.android.core.ui.TroupeTopBar

@Composable
fun ServersScreen(
    onPair: () -> Unit,
    onOpenServer: (String) -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TroupeTopBar(
                title = stringResource(R.string.servers_title),
                actions = {
                    if (!servers.isNullOrEmpty()) {
                        IconButton(onClick = onPair) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.servers_pair_action))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val list = servers ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyServers(onPair, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { server ->
                    ServerRow(server, onClick = { onOpenServer(server.id) })
                    HorizontalDivider(color = LocalTroupePalette.current.border)
                }
            }
        }
    }
}

@Composable
private fun EmptyServers(onPair: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.servers_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.servers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalTroupePalette.current.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPair, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.servers_pair_action))
        }
    }
}

@Composable
private fun ServerRow(server: PairedServer, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(server.name, style = MaterialTheme.typography.titleMedium)
        Text(
            server.address.baseUrl.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = LocalTroupePalette.current.muted,
        )
    }
}
