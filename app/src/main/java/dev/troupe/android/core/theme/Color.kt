package dev.troupe.android.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** termul's design tokens, named after its chrome roles. */
@Immutable
data class TroupePalette(
    val background: Color,
    val card: Color,
    val elevated: Color,
    val border: Color,
    val foreground: Color,
    val secondary: Color,
    val muted: Color,
    val brand: Color,
    val brandText: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
)

object TroupeColors {
    val Dark = TroupePalette(
        background = Color(0xFF08090A),
        card = Color(0xFF0F1011),
        elevated = Color(0xFF161718),
        border = Color(0xFF23252A),
        foreground = Color(0xFFE5E5E6),
        secondary = Color(0xFFD0D6E0),
        muted = Color(0xFF8A8F98),
        brand = Color(0xFF0D74CE),
        brandText = Color(0xFF3B9EFF),
        success = Color(0xFF22C55E),
        warning = Color(0xFFEAB308),
        error = Color(0xFFEF4444),
        info = Color(0xFF38BDF8),
    )

    /** From termul's `termul-light-chrome.ts`; the brand blue doubles as the link color in light. */
    val Light = TroupePalette(
        background = Color(0xFFFCFCFC),
        card = Color(0xFFFFFFFF),
        elevated = Color(0xFFFFFFFF),
        border = Color(0xFFEDEDED),
        foreground = Color(0xFF202020),
        secondary = Color(0xFF646464),
        muted = Color(0xFF646464),
        brand = Color(0xFF0D74CE),
        brandText = Color(0xFF0D74CE),
        success = Color(0xFF22C55E),
        // Warning is text (Reconnecting, View only, Attention); the dark theme's yellow is ~1.9:1 on white, this is ~4.8:1.
        warning = Color(0xFFA16207),
        error = Color(0xFFEF4444),
        info = Color(0xFF38BDF8),
    )

    /** termul's Project colors (`--project-*`, OKLCH converted to sRGB). Unknown names fall back to gray. */
    fun project(name: String?): Color = when (name) {
        "blue" -> Color(0xFF3C83F6)
        "purple" -> Color(0xFF9234EA)
        "green" -> Color(0xFF21C45D)
        "yellow" -> Color(0xFFE7B007)
        "red" -> Color(0xFFEF4343)
        "cyan" -> Color(0xFF21D5ED)
        "pink" -> Color(0xFFEC4699)
        "orange" -> Color(0xFFF97415)
        else -> Color(0xFF6B7280)
    }
}

val LocalTroupePalette = staticCompositionLocalOf { TroupeColors.Dark }
