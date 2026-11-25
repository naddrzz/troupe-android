package dev.troupe.android.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.troupe.android.core.theme.LocalTroupePalette

/** Troupe button variants (`docs/design/buttons.md`). No `primary` variant: `Default` is the fill. */
enum class TroupeButtonVariant {
    Default,
    Composer,
    Secondary,
    Outline,
    Ghost,
    Destructive,
    Link,
}

private val TroupePressEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Troupe V2 button primitive matching `src/renderer/components/ui/button.tsx`:
 * - `Default` & `Composer`: `brand` fill + top/bottom emboss bevel + `scale(0.97f)` press.
 * - `Secondary` & `Outline`: `controlFill` + `0.5.dp` `popoverEdge` ring + `scale(0.97f)` press.
 * - `Destructive`: `destructiveFill` + `scale(0.97f)` press.
 * - `Ghost` & `Link`: fill answer only, never scale.
 * - All touch buttons hold the 44dp phone tap-target floor.
 */
@Composable
fun TroupeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TroupeButtonVariant = TroupeButtonVariant.Default,
    enabled: Boolean = true,
    minHeight: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalTroupePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val shouldPressScale = variant != TroupeButtonVariant.Ghost && variant != TroupeButtonVariant.Link
    val scale by animateFloatAsState(
        targetValue = if (shouldPressScale && pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150, easing = TroupePressEasing),
        label = "troupe-button-scale",
    )

    val containerColor = when (variant) {
        TroupeButtonVariant.Default, TroupeButtonVariant.Composer -> when {
            !enabled -> palette.mutedSurface
            pressed -> palette.brandActive
            else -> palette.brand
        }
        TroupeButtonVariant.Destructive -> when {
            !enabled -> palette.mutedSurface
            pressed -> palette.destructiveFill.copy(alpha = 0.85f)
            else -> palette.destructiveFill
        }
        TroupeButtonVariant.Secondary, TroupeButtonVariant.Outline -> when {
            !enabled -> palette.controlFill.copy(alpha = 0.6f)
            pressed -> palette.popoverHover
            else -> palette.controlFill
        }
        TroupeButtonVariant.Ghost -> if (pressed && enabled) palette.popoverHover else Color.Transparent
        TroupeButtonVariant.Link -> Color.Transparent
    }

    val contentColor = when (variant) {
        TroupeButtonVariant.Default, TroupeButtonVariant.Composer ->
            if (enabled) palette.brandForeground else palette.disabledForeground
        TroupeButtonVariant.Destructive ->
            if (enabled) Color.White else palette.disabledForeground
        TroupeButtonVariant.Secondary, TroupeButtonVariant.Outline ->
            if (enabled) palette.foreground else palette.disabledForeground
        TroupeButtonVariant.Ghost ->
            if (enabled) palette.foreground else palette.disabledForeground
        TroupeButtonVariant.Link ->
            if (enabled) palette.brandText else palette.disabledForeground
    }

    val border: BorderStroke? = when (variant) {
        TroupeButtonVariant.Secondary, TroupeButtonVariant.Outline ->
            BorderStroke(0.5.dp, palette.popoverEdge)
        TroupeButtonVariant.Default, TroupeButtonVariant.Composer ->
            if (enabled) BorderStroke(0.5.dp, Color.Black.copy(alpha = if (palette.isDark) 0.85f else 0.22f)) else null
        else -> null
    }

    val hasEmboss = enabled && (variant == TroupeButtonVariant.Default || variant == TroupeButtonVariant.Composer)

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minWidth = 44.dp, minHeight = minHeight)
            .clip(shape)
            .background(containerColor, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(
                if (hasEmboss) {
                    Modifier.drawBehind {
                        // Subtle top light bevel & bottom dark bevel matching `--shadow-emboss`.
                        drawLine(
                            color = Color.White.copy(alpha = if (palette.isDark) 0.28f else 0.32f),
                            start = Offset(0f, 1f),
                            end = Offset(size.width, 1f),
                            strokeWidth = 1.5f,
                        )
                        drawLine(
                            color = Color.Black.copy(alpha = if (palette.isDark) 0.35f else 0.16f),
                            start = Offset(0f, size.height - 1f),
                            end = Offset(size.width, size.height - 1f),
                            strokeWidth = 2f,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                content()
            }
        }
    }
}

/**
 * 44dp touch-floor icon button matching Troupe's header & toolbar icon controls.
 * Answers with a neutral press fill, never scales (`docs/design/buttons.md`).
 */
@Composable
fun TroupeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalTroupePalette.current.foreground,
    content: @Composable () -> Unit,
) {
    val palette = LocalTroupePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        pressed && enabled -> palette.popoverHover
        else -> containerColor
    }
    Box(
        modifier = modifier
            .size(size)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) contentColor else palette.disabledForeground,
        ) {
            content()
        }
    }
}

/** Convenience overload of [TroupeButton] with label, optional leading icon, and loading spinner. */
@Composable
fun TroupeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TroupeButtonVariant = TroupeButtonVariant.Default,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    minHeight: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    TroupeButton(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled && !loading,
        minHeight = minHeight,
        shape = shape,
    ) {
        if (loading) {
            TroupeSpinner(size = 16.dp, color = LocalContentColor.current)
        } else if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        androidx.compose.material3.Text(
            text = text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Convenience overload of [TroupeIconButton] taking an [ImageVector] and content description. */
@Composable
fun TroupeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalTroupePalette.current.foreground,
) {
    TroupeIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = size,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}
