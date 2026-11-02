package dev.troupe.android.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Dark by default (termul's look); light follows the system setting. */
@Composable
fun TroupeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (darkTheme) TroupeColors.Dark else TroupeColors.Light
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.brand,
        onPrimary = Color.White,
        secondary = p.secondary,
        background = p.background,
        onBackground = p.foreground,
        surface = p.background,
        onSurface = p.foreground,
        surfaceVariant = p.card,
        onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.card,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.elevated,
        surfaceContainerHighest = p.elevated,
        outline = p.border,
        outlineVariant = p.border,
        error = p.error,
        onError = Color.White,
    )
    CompositionLocalProvider(LocalTroupePalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = TroupeTypography, content = content)
    }
}
