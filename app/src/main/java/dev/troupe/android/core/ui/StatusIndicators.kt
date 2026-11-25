package dev.troupe.android.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.troupe.android.core.theme.LocalTroupePalette
import dev.troupe.android.core.theme.tabularNums

/**
 * Blue count badge (`primary-fill` + `primary-foreground`), matching `components/ui/count-badge.tsx`.
 * Caps at `max+` (default `99+`, or `9+` on mobile header/drawer tabs).
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    max: Int = 99,
    tone: Color = LocalTroupePalette.current.brand,
) {
    if (count <= 0) return
    val palette = LocalTroupePalette.current
    val label = if (count > max) "$max+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(tone)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = palette.brandForeground,
            style = MaterialTheme.typography.labelSmall.tabularNums().copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/**
 * Amber "Needs you" status pill (`bg-warning/10 text-warning` + 6dp dot + word),
 * matching `docs/design/status.md`.
 */
@Composable
fun NeedsYouPill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    val palette = LocalTroupePalette.current
    val base = modifier
        .clip(RoundedCornerShape(999.dp))
        .background(palette.warning.copy(alpha = if (onClick != null) 0.20f else 0.12f))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .padding(horizontal = 8.dp, vertical = 4.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(palette.warning))
        Text(
            text = label,
            color = palette.warning,
            style = MaterialTheme.typography.labelMedium.tabularNums().copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/** Count convenience overload for the header attention pill (`1 needs you` / `N need you`). */
@Composable
fun NeedsYouPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val text = if (count > 9) "9+" else "$count"
    NeedsYouPill(
        label = text,
        onClick = onClick,
        description = if (count == 1) "1 other chat needs you" else "$count other chats need you",
        modifier = modifier,
    )
}

/** Muted in-progress spinner (`docs/design/status.md`: never warning amber for running). */
@Composable
fun TroupeSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = LocalTroupePalette.current.muted,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 1.75.dp,
        color = color,
    )
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
