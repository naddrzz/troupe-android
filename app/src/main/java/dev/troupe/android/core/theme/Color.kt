package dev.troupe.android.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.troupe.android.terminal.render.AnsiPalette

/**
 * Troupe V2 semantic design tokens, matching `DESIGN.md` and `docs/design/colors.md`.
 * Retains the MVP field names (`background`, `card`, `elevated`, `border`, `foreground`,
 * `secondary`, `muted`, `brand`, `brandText`, `success`, `warning`, `error`, `info`) while adding
 * the full V2 surface, control, diff, syntax, and terminal token set.
 */
@Immutable
data class TroupePalette(
    val id: String,
    val name: String,
    val familyId: String,
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val elevated: Color,
    val popover: Color,
    val sidebarBackground: Color,
    val secondarySurface: Color,
    val mutedSurface: Color,
    val border: Color,
    val input: Color,
    val fieldBorder: Color,
    val popoverEdge: Color,
    val foreground: Color,
    val secondary: Color,
    val muted: Color,
    val disabledForeground: Color,
    val brand: Color,
    val brandHover: Color,
    val brandActive: Color,
    val brandForeground: Color,
    val brandText: Color,
    val success: Color,
    val successFill: Color,
    val warning: Color,
    val warningForeground: Color,
    val error: Color,
    val destructiveFill: Color,
    val info: Color,
    val connection: Color,
    val diffAdded: Color,
    val diffAddedForeground: Color,
    val diffModified: Color,
    val overlay: Color,
    val controlFill: Color,
    val keycapFill: Color,
    val focusRing: Color,
    val terminal: AnsiPalette,
    val syntax: SyntaxPalette,
) {
    /** Neutral hover/press wash on page or card (`3%` foreground). */
    val rowHover: Color get() = foreground.copy(alpha = 0.03f)
    /** Neutral press wash on popovers/sheets (`6%` foreground). */
    val popoverHover: Color get() = foreground.copy(alpha = 0.06f)
    /** Picked/selected row fill (`10%` foreground — stronger than hover, no tick on single-pick). */
    val pickedFill: Color get() = foreground.copy(alpha = 0.10f)
    /** Subtle hairline divider (`border` at 60%). */
    val hairline: Color get() = border.copy(alpha = 0.60f)
    /** Semantic aliases matching Troupe CSS token names. */
    val surfaceElevated: Color get() = elevated
    val mutedForeground: Color get() = muted
    val accent: Color get() = brand
    val accentForeground: Color get() = brandForeground
    val destructive: Color get() = error
    val borderSubtle: Color get() = hairline
    val ring: Color get() = focusRing
    val warningTint: Color get() = warning.copy(alpha = 0.14f)
}

object TroupeColors {
    val Dark: TroupePalette by lazy { BundledThemes.byId("termul") }
    val Light: TroupePalette by lazy { BundledThemes.byId("termul-light") }

    /** Troupe's Project colors (`--project-*`, OKLCH converted to sRGB). Unknown names fall back to gray. */
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

    val projectColorNames: List<String> = listOf(
        "blue", "purple", "green", "yellow", "red", "cyan", "pink", "orange", "gray",
    )
}

val LocalTroupePalette = staticCompositionLocalOf { TroupeColors.Dark }
