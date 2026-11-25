package dev.troupe.android.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

object TroupeTheme {
    val palette: TroupePalette
        @Composable
        @ReadOnlyComposable
        get() = LocalTroupePalette.current

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography
}

/**
 * Troupe V2 theme: maps any of the 20 bundled [TroupePalette] themes onto Material 3
 * and exposes the full semantic palette via [LocalTroupePalette].
 */
@Composable
fun TroupeTheme(
    themeId: String? = null,
    appearanceMode: AppearanceMode = AppearanceMode.System,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (themeId == null && appearanceMode == AppearanceMode.System) {
        if (darkTheme) TroupeColors.Dark else TroupeColors.Light
    } else {
        BundledThemes.resolve(themeId, appearanceMode, darkTheme)
    }
    val errorHex = String.format(
        java.util.Locale.US,
        "#%02x%02x%02x",
        (palette.error.red * 255f).toInt().coerceIn(0, 255),
        (palette.error.green * 255f).toInt().coerceIn(0, 255),
        (palette.error.blue * 255f).toInt().coerceIn(0, 255),
    )
    val onErrorColor = OklchColor.parseHexColor(OklchColor.fillInkHex(errorHex))
    val colors: ColorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.brand,
            onPrimary = palette.brandForeground,
            secondary = palette.secondarySurface,
            onSecondary = palette.foreground,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.card,
            onSurface = palette.foreground,
            surfaceVariant = palette.mutedSurface,
            onSurfaceVariant = palette.muted,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.card,
            surfaceContainer = palette.popover,
            surfaceContainerHigh = palette.mutedSurface,
            surfaceContainerHighest = palette.secondarySurface,
            outline = palette.fieldBorder,
            outlineVariant = palette.border,
            error = palette.error,
            onError = onErrorColor,
        )
    } else {
        lightColorScheme(
            primary = palette.brand,
            onPrimary = palette.brandForeground,
            secondary = palette.secondarySurface,
            onSecondary = palette.foreground,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.card,
            onSurface = palette.foreground,
            surfaceVariant = palette.mutedSurface,
            onSurfaceVariant = palette.muted,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.card,
            surfaceContainer = palette.popover,
            surfaceContainerHigh = palette.mutedSurface,
            surfaceContainerHighest = palette.secondarySurface,
            outline = palette.fieldBorder,
            outlineVariant = palette.border,
            error = palette.error,
            onError = onErrorColor,
        )
    }
    CompositionLocalProvider(LocalTroupePalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = TroupeTypography, content = content)
    }
}
