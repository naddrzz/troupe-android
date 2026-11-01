package dev.troupe.android.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.troupe.android.R
import dev.troupe.android.core.net.ConnectionState
import dev.troupe.android.core.theme.LocalTroupePalette

/**
 * The screen header. With a [connection], a line under the title says whether what is on screen is
 * live, and "Server offline" comes with a Retry action ([onRetry]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroupeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    connection: ConnectionState? = null,
    onRetry: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                connection?.let { ConnectionLine(it) }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            if (connection == ConnectionState.ServerOffline) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.connection_retry)) }
            }
            actions()
        },
    )
}

@Composable
private fun ConnectionLine(state: ConnectionState) {
    val palette = LocalTroupePalette.current
    val (text, color) = when (state) {
        ConnectionState.Idle, ConnectionState.Connecting -> stringResource(R.string.connection_connecting) to palette.muted
        ConnectionState.Connected -> stringResource(R.string.connection_connected) to palette.success
        is ConnectionState.Reconnecting -> stringResource(R.string.connection_reconnecting, state.attempt) to palette.warning
        ConnectionState.ServerOffline -> stringResource(R.string.connection_offline) to palette.error
        is ConnectionState.Failed, ConnectionState.Closed -> stringResource(R.string.connection_disconnected) to palette.error
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
