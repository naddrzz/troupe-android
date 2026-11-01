package dev.troupe.android.core.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette

/** The "View only" badge: a button whose label is the badge text; tapping it explains `--allow-remote-writes`. */
@Composable
fun ViewOnlyBadge() {
    var explaining by rememberSaveable { mutableStateOf(false) }
    val palette = LocalTroupePalette.current
    OutlinedButton(onClick = { explaining = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.view_only_badge), color = palette.warning, style = MaterialTheme.typography.labelMedium)
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(R.string.view_only_badge)) },
            text = { Text(stringResource(R.string.view_only_explanation)) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.view_only_ok)) } },
        )
    }
}
