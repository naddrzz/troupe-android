package dev.troupe.android.terminal.render

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.LocalTroupePalette

/** Keys the soft keyboard can't type, with the exact sequence each sends. */
enum class TerminalKey(val sequence: String, @StringRes val label: Int, @StringRes val description: Int) {
    Esc("\u001b", R.string.key_esc, R.string.key_esc_description),
    Tab("\t", R.string.key_tab, R.string.key_tab_description),
    CtrlC("\u0003", R.string.key_ctrl_c, R.string.key_ctrl_c_description),
    Left("\u001b[D", R.string.key_left, R.string.key_left_description),
    Up("\u001b[A", R.string.key_up, R.string.key_up_description),
    Down("\u001b[B", R.string.key_down, R.string.key_down_description),
    Right("\u001b[C", R.string.key_right, R.string.key_right_description),
    PageUp("\u001b[5~", R.string.key_page_up, R.string.key_page_up_description),
    PageDown("\u001b[6~", R.string.key_page_down, R.string.key_page_down_description),
}

/** Paste, then every [TerminalKey], in one scrollable row of 48dp targets. */
@Composable
fun KeyBar(enabled: Boolean, onKey: (TerminalKey) -> Unit, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        KeyButton(stringResource(R.string.key_paste), stringResource(R.string.key_paste), enabled, onPaste)
        TerminalKey.entries.forEach { key ->
            KeyButton(stringResource(key.label), stringResource(key.description), enabled) { onKey(key) }
        }
    }
}

@Composable
private fun KeyButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description },
    ) {
        val palette = LocalTroupePalette.current
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) palette.foreground else palette.muted)
    }
}
