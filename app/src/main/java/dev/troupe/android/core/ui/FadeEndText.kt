package dev.troupe.android.core.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-line text that fades cleanly over its last [fadeWidth] (default 24dp) only when it
 * overflows horizontally, matching Troupe's `.fade-end` / `useOverflowFade` utility instead of
 * typing an ellipsis (`docs/design/composer-v2-handoff.md`).
 */
@Composable
fun FadeEndText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fadeWidth: Dp = 24.dp,
) {
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result -> overflows = result.hasVisualOverflow },
        modifier = modifier
            .then(
                if (overflows) {
                    Modifier
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            val fadePx = fadeWidth.toPx().coerceAtMost(size.width)
                            if (fadePx > 0f) {
                                val startX = (size.width - fadePx).coerceAtLeast(0f)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startX = startX,
                                        endX = size.width,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                } else {
                    Modifier
                },
            ),
    )
}
