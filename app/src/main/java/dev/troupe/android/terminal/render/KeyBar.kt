package dev.troupe.android.terminal.render

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.troupe.android.R
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeIcons

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

/**
 * 1:1 port of Troupe's `MobileTerminalControls.tsx`:
 * Single 52dp bar (`card` surface, 0.5dp top border) with fixed Keyboard toggle + fixed Paste
 * + hairline divider + horizontally scrollable 9 keys with a 24dp right-edge fade when more keys
 * sit off-screen. Every control preserves the 44dp touch floor.
 */
@Composable
fun KeyBar(
    enabled: Boolean,
    onKey: (TerminalKey) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    var expanded by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val hasMoreRight = scrollState.value < scrollState.maxValue

    Box(modifier = modifier.fillMaxWidth().background(palette.card)) {
        HorizontalDivider(
            color = palette.border,
            thickness = 0.5.dp,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Fixed Slot 1: Show/hide key lane toggle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TroupeIcons.Keyboard,
                    contentDescription = stringResource(R.string.terminal_toggle_keys),
                    tint = if (expanded) palette.foreground else palette.mutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Fixed Slot 2: Paste button
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onPaste)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = TroupeIcons.ClipboardPaste,
                    contentDescription = null,
                    tint = if (enabled) palette.foreground else palette.disabledForeground,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(R.string.key_paste),
                    style = typo.labelLarge,
                    color = if (enabled) palette.foreground else palette.disabledForeground,
                )
            }

            // Vertical hairline separator
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(24.dp)
                    .background(palette.border),
            )

            // Scrollable 9-key lane with 24dp end-fade when more keys lie off-screen
            if (expanded) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            if (hasMoreRight && size.width > 0f) {
                                val fadePx = 24.dp.toPx().coerceAtMost(size.width)
                                val startFrac = ((size.width - fadePx) / size.width).coerceIn(0f, 1f)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Black,
                                            startFrac to Color.Black,
                                            1f to Color.Transparent,
                                        ),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .horizontalScroll(scrollState)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TerminalKey.entries.forEach { key ->
                        KeyCapButton(
                            label = stringResource(key.label),
                            description = stringResource(key.description),
                            enabled = enabled,
                            onClick = { onKey(key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCapButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.keycapFill)
            .border(0.5.dp, palette.popoverEdge, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typo.labelLarge,
            color = if (enabled) palette.foreground else palette.disabledForeground,
        )
    }
}
