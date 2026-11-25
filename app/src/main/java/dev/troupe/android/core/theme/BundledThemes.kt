package dev.troupe.android.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.troupe.android.core.theme.OklchColor.FOCUS_CONTRAST_MIN
import dev.troupe.android.core.theme.OklchColor.TEXT_CONTRAST_MIN
import dev.troupe.android.core.theme.OklchColor.UI_CONTRAST_MIN
import dev.troupe.android.core.theme.OklchColor.darkenHex
import dev.troupe.android.core.theme.OklchColor.fillInkHex
import dev.troupe.android.core.theme.OklchColor.hexToOklch
import dev.troupe.android.core.theme.OklchColor.holdHue
import dev.troupe.android.core.theme.OklchColor.lightenHex
import dev.troupe.android.core.theme.OklchColor.mixHex
import dev.troupe.android.core.theme.OklchColor.oklchToHex
import dev.troupe.android.core.theme.OklchColor.parseHexColor
import dev.troupe.android.core.theme.OklchColor.solidFillHex
import dev.troupe.android.core.theme.OklchColor.solveContrast
import dev.troupe.android.terminal.render.AnsiPalette

/**
 * Appearance mode stored in Troupe's `settings/app` (`light`, `dark`, or device-local `system`).
 */
enum class AppearanceMode(val wireValue: String) {
    System("system"),
    Dark("dark"),
    Light("light");

    companion object {
        fun fromWire(value: String?): AppearanceMode = when (value?.lowercase()) {
            "light" -> Light
            "dark" -> Dark
            else -> System
        }
    }
}

@Immutable
data class ColorThemeFamily(
    val familyId: String,
    val name: String,
    val darkThemeId: String,
    val lightThemeId: String,
)

@Immutable
data class BundledThemeSpec(
    val id: String,
    val name: String,
    val familyId: String,
    val isDark: Boolean,
    val neutral: String,
    val ink: String,
    val primary: String,
    val accent: String,
    val success: String,
    val warning: String,
    val error: String,
    val info: String,
    val overrides: SyntaxOverrides,
    val terminalOverride: AnsiPalette? = null,
    val explicitChrome: ExplicitChrome? = null,
)

@Immutable
data class ExplicitChrome(
    val background: String,
    val card: String,
    val elevated: String,
    val secondary: String,
    val sidebar: String,
    val muted: String,
    val border: String,
    val input: String,
    val foreground: String,
    val secondaryForeground: String,
    val mutedForeground: String,
    val brandFill: String,
    val brandFillHover: String,
    val brandFillActive: String,
    val controlFill: String,
    val keycapFill: String,
)

/**
 * All 20 bundled Troupe themes (10 families x Dark/Light), ported 1:1 from
 * `src/renderer/lib/themes/bundled-themes.ts` & `bundled-light-themes.ts`.
 */
object BundledThemes {
    const val DEFAULT_THEME_ID = "termul"
    const val DEFAULT_FAMILY_ID = "termul"

    private const val NEUTRAL_BRAND_TINT = 0.04
    private const val LIGHT_CANVAS_DARKEN = 0.025

    private val termulDarkChrome: ExplicitChrome by lazy {
        val voidHex = "#08090a"
        val obsidianL = hexToOklch("#161718").l
        val graphiteL = hexToOklch("#23252a").l
        val mutedHex = holdHue(voidHex, (obsidianL + graphiteL) / 2.0)
        val borderHex = holdHue(voidHex, graphiteL)
        ExplicitChrome(
            background = voidHex,
            card = "#0f1011",
            elevated = "#161718",
            secondary = "#161718",
            sidebar = "#161718",
            muted = mutedHex,
            border = borderHex,
            input = borderHex,
            foreground = "#e5e5e6",
            secondaryForeground = "#d0d6e0",
            mutedForeground = "#8a8f98",
            brandFill = "#0d74ce",
            brandFillHover = "#0e6abe",
            brandFillActive = "#0e60ae",
            controlFill = "#1a1b1d",
            keycapFill = mutedHex,
        )
    }

    private val termulLightChrome = ExplicitChrome(
        background = "#fcfcfc",
        card = "#ffffff",
        elevated = "#ffffff",
        secondary = "#f0f0f0",
        sidebar = "#f6f6f6",
        muted = "#f2f2f2",
        border = "#ededed",
        input = "#d9d9d9",
        foreground = "#202020",
        secondaryForeground = "#646464",
        mutedForeground = "#646464",
        brandFill = "#0d74ce",
        brandFillHover = "#0e6abe",
        brandFillActive = "#0e60ae",
        controlFill = "#ffffff",
        keycapFill = "#ffffff",
    )

    private val termulLightTerminal = AnsiPalette(
        background = "#fcfcfc",
        foreground = "#202020",
        cursor = "#202020",
        cursorAccent = "#fcfcfc",
        selectionBackground = "#6ca1f8",
        selectionForeground = "#202020",
        selectionInactiveBackground = "#f0f0f0",
        black = "#e0e0e0",
        red = "#cd3131",
        green = "#098658",
        yellow = "#cd9731",
        blue = "#3b82f6",
        magenta = "#811f3f",
        cyan = "#0598bc",
        white = "#202020",
        brightBlack = "#929292",
        brightRed = "#b92c2c",
        brightGreen = "#08794f",
        brightYellow = "#b9882c",
        brightBlue = "#3575dd",
        brightMagenta = "#741c39",
        brightCyan = "#0589a9",
        brightWhite = "#0b0b0b",
    )

    private val specs: List<BundledThemeSpec> by lazy {
        listOf(
            // 1. Troupe
            BundledThemeSpec(
                id = "termul", name = "Troupe", familyId = "termul", isDark = true,
                neutral = "#08090a", ink = "#e5e5e6", primary = "#3b9eff", accent = "#3b9eff",
                success = "#22c55e", warning = "#eab308", error = "#ef4444", info = "#38bdf8",
                overrides = SyntaxOverrides(
                    comment = "#a3a3a3", keyword = "#95d0cd", string = "#d898d8", type = "#e6b387",
                    constant = "#d5cf77", primitive = "#95d0cd", variable = "#94c2fa", definition = "#a9a1f4",
                    function = "#e6b387", operator = "#d6d6dd", punctuation = "#d6d6dd", tag = "#a9a1f4", attribute = "#a9a1f4",
                ),
                terminalOverride = AnsiPalette.Termul,
                explicitChrome = termulDarkChrome,
            ),
            BundledThemeSpec(
                id = "termul-light", name = "Troupe Light", familyId = "termul", isDark = false,
                neutral = "#fcfcfc", ink = "#202020", primary = "#0d74ce", accent = "#0d74ce",
                success = "#098658", warning = "#cd9731", error = "#cd3131", info = "#0598bc",
                overrides = SyntaxOverrides(
                    comment = "#5a5a5a", keyword = "#3e7875", string = "#945995", type = "#94653b",
                    constant = "#77700a", primitive = "#3e7875", variable = "#4570a3", definition = "#6e64b2",
                    function = "#94653b", operator = "#3f4248", punctuation = "#3f4248", tag = "#6e64b2", attribute = "#6e64b2",
                ),
                terminalOverride = termulLightTerminal,
                explicitChrome = termulLightChrome,
            ),
            // 2. Cursor
            BundledThemeSpec(
                id = "cursor", name = "Cursor", familyId = "cursor", isDark = true,
                neutral = "#181818", ink = "#e4e4e4", primary = "#88c0d0", accent = "#f38ba8",
                success = "#3fa266", warning = "#f1b467", error = "#e34671", info = "#81a1c1",
                overrides = SyntaxOverrides(
                    comment = "#e4e4e45e", keyword = "#82d2ce", string = "#e394dc", type = "#efb080",
                    constant = "#f8c762", property = "#81a1c1", function = "#efb080",
                ),
            ),
            BundledThemeSpec(
                id = "cursor-light", name = "Cursor Light", familyId = "cursor", isDark = false,
                neutral = "#fcfcfc", ink = "#141414", primary = "#6f9ba6", accent = "#6f9ba6",
                success = "#1f8a65", warning = "#db704b", error = "#cf2d56", info = "#3c7cab",
                overrides = SyntaxOverrides(
                    comment = "#141414ad", keyword = "#b3003f", string = "#9e94d5", type = "#206595",
                    constant = "#b8448b", function = "#db704b",
                ),
            ),
            // 3. Catppuccin
            BundledThemeSpec(
                id = "catppuccin", name = "Catppuccin", familyId = "catppuccin", isDark = true,
                neutral = "#1e1e2e", ink = "#cdd6f4", primary = "#b4befe", accent = "#f38ba8",
                success = "#a6e3a1", warning = "#fab387", error = "#f38ba8", info = "#89dceb",
                overrides = SyntaxOverrides(
                    comment = "#6c7086", keyword = "#cba6f7", string = "#a6e3a1", primitive = "#89b4fa",
                    constant = "#fab387", type = "#f9e2af", function = "#89b4fa",
                ),
            ),
            BundledThemeSpec(
                id = "catppuccin-light", name = "Catppuccin Light", familyId = "catppuccin", isDark = false,
                neutral = "#f5e0dc", ink = "#4c4f69", primary = "#7287fd", accent = "#d20f39",
                success = "#40a02b", warning = "#df8e1d", error = "#d20f39", info = "#04a5e5",
                overrides = SyntaxOverrides(
                    comment = "#6c7086", keyword = "#8839ef", string = "#40a02b", primitive = "#1e66f5",
                    constant = "#ca6702", type = "#df8e1d", function = "#1e66f5",
                ),
            ),
            // 4. Dracula
            BundledThemeSpec(
                id = "dracula", name = "Dracula", familyId = "dracula", isDark = true,
                neutral = "#1d1e28", ink = "#f8f8f2", primary = "#bd93f9", accent = "#ff79c6",
                success = "#50fa7b", warning = "#ffb86c", error = "#ff5555", info = "#8be9fd",
                overrides = SyntaxOverrides(
                    comment = "#6272a4", keyword = "#ff79c6", string = "#f1fa8c", primitive = "#50fa7b",
                    constant = "#bd93f9", property = "#8be9fd", function = "#50fa7b",
                ),
            ),
            BundledThemeSpec(
                id = "dracula-light", name = "Dracula Light", familyId = "dracula", isDark = false,
                neutral = "#f8f8f2", ink = "#1f1f2f", primary = "#7c6bf5", accent = "#d16090",
                success = "#2fbf71", warning = "#f7a14d", error = "#d9536f", info = "#1d7fc5",
                overrides = SyntaxOverrides(
                    comment = "#7d7f97", keyword = "#d16090", string = "#596600", primitive = "#2f8f57",
                    constant = "#7c6bf5", property = "#1d7fc5", function = "#2f8f57",
                ),
            ),
            // 5. Nord
            BundledThemeSpec(
                id = "nord", name = "Nord", familyId = "nord", isDark = true,
                neutral = "#2e3440", ink = "#eceff4", primary = "#88c0d0", accent = "#bf616a",
                success = "#a3be8c", warning = "#ebcb8b", error = "#bf616a", info = "#81a1c1",
                overrides = SyntaxOverrides(
                    comment = "#616e88", keyword = "#81a1c1", string = "#a3be8c", type = "#8fbcbb",
                    constant = "#b48ead", function = "#88c0d0",
                ),
            ),
            BundledThemeSpec(
                id = "nord-light", name = "Nord Light", familyId = "nord", isDark = false,
                neutral = "#eceff4", ink = "#2e3440", primary = "#5e81ac", accent = "#bf616a",
                success = "#8fbcbb", warning = "#d08770", error = "#bf616a", info = "#81a1c1",
                overrides = SyntaxOverrides(
                    comment = "#6b7282", keyword = "#5e81ac", string = "#6f8758", type = "#8fbcbb",
                    constant = "#8d6886",
                ),
            ),
            // 6. Gruvbox
            BundledThemeSpec(
                id = "gruvbox", name = "Gruvbox", familyId = "gruvbox", isDark = true,
                neutral = "#282828", ink = "#ebdbb2", primary = "#83a598", accent = "#d3869b",
                success = "#b8bb26", warning = "#fabd2f", error = "#fb4934", info = "#8ec07c",
                overrides = SyntaxOverrides(
                    comment = "#928374", keyword = "#fb4934", string = "#b8bb26", type = "#fabd2f",
                    constant = "#d3869b", function = "#83a598",
                ),
            ),
            BundledThemeSpec(
                id = "gruvbox-light", name = "Gruvbox Light", familyId = "gruvbox", isDark = false,
                neutral = "#fbf1c7", ink = "#3c3836", primary = "#076678", accent = "#9d0006",
                success = "#79740e", warning = "#b57614", error = "#9d0006", info = "#8f3f71",
                overrides = SyntaxOverrides(
                    comment = "#928374", keyword = "#9d0006", string = "#79740e", type = "#b57614",
                    constant = "#8f3f71", function = "#076678",
                ),
            ),
            // 7. Tokyo Night
            BundledThemeSpec(
                id = "tokyonight", name = "Tokyo Night", familyId = "tokyonight", isDark = true,
                neutral = "#1a1b26", ink = "#c0caf5", primary = "#7aa2f7", accent = "#bb9af7",
                success = "#9ece6a", warning = "#e0af68", error = "#f7768e", info = "#7dcfff",
                overrides = SyntaxOverrides(
                    comment = "#565f89", keyword = "#bb9af7", string = "#9ece6a", type = "#2ac3de",
                    constant = "#ff9e64", property = "#7dcfff", function = "#7aa2f7",
                ),
            ),
            BundledThemeSpec(
                id = "tokyonight-light", name = "Tokyo Night Light", familyId = "tokyonight", isDark = false,
                neutral = "#e1e2e7", ink = "#273153", primary = "#2e7de9", accent = "#b15c00",
                success = "#587539", warning = "#8c6c3e", error = "#c94060", info = "#007197",
                overrides = SyntaxOverrides(
                    comment = "#6b6f7a", keyword = "#9854f1", string = "#587539", type = "#007197",
                    constant = "#b15c00", property = "#007197", function = "#2e7de9",
                ),
            ),
            // 8. Ayu
            BundledThemeSpec(
                id = "ayu", name = "Ayu", familyId = "ayu", isDark = true,
                neutral = "#0b0e14", ink = "#bfbdb6", primary = "#39bae6", accent = "#ff8f40",
                success = "#7fd962", warning = "#ffb454", error = "#f26d78", info = "#59c2ff",
                overrides = SyntaxOverrides(
                    comment = "#5a6673", keyword = "#ff8f40", string = "#aad94c", type = "#59c2ff",
                    constant = "#d2a6ff", property = "#39bae6", function = "#ffb454",
                ),
            ),
            BundledThemeSpec(
                id = "ayu-light", name = "Ayu Light", familyId = "ayu", isDark = false,
                neutral = "#fdfaf4", ink = "#4f5964", primary = "#4aa8c8", accent = "#ef7d71",
                success = "#5fb978", warning = "#ea9f41", error = "#e6656a", info = "#2f9bce",
                overrides = SyntaxOverrides(
                    comment = "#6e7681", keyword = "#c76a1a", string = "#6f8f00", type = "#227fc0",
                    constant = "#a37acc", property = "#2f86b7", function = "#b87500",
                ),
            ),
            // 9. One Dark
            BundledThemeSpec(
                id = "one-dark", name = "One Dark", familyId = "one-dark", isDark = true,
                neutral = "#282c34", ink = "#abb2bf", primary = "#61afef", accent = "#c678dd",
                success = "#98c379", warning = "#e5c07b", error = "#e06c75", info = "#56b6c2",
                overrides = SyntaxOverrides(
                    comment = "#5c6370", keyword = "#c678dd", string = "#98c379", type = "#e5c07b",
                    constant = "#d19a66", variable = "#e06c75", property = "#56b6c2", function = "#61afef",
                ),
            ),
            BundledThemeSpec(
                id = "one-dark-light", name = "One Dark Light", familyId = "one-dark", isDark = false,
                neutral = "#fafafa", ink = "#383a42", primary = "#4078f2", accent = "#0184bc",
                success = "#50a14f", warning = "#c18401", error = "#e45649", info = "#986801",
                overrides = SyntaxOverrides(
                    comment = "#a0a1a7", keyword = "#a626a4", string = "#50a14f", type = "#c18401",
                    constant = "#986801", variable = "#e45649", property = "#0184bc", function = "#4078f2",
                ),
            ),
            // 10. GitHub
            BundledThemeSpec(
                id = "github", name = "GitHub", familyId = "github", isDark = true,
                neutral = "#0d1117", ink = "#c9d1d9", primary = "#58a6ff", accent = "#f78166",
                success = "#3fb950", warning = "#d29922", error = "#f85149", info = "#79c0ff",
                overrides = SyntaxOverrides(
                    comment = "#8b949e", keyword = "#ff7b72", string = "#39c5cf", type = "#d29922",
                    constant = "#79c0ff", variable = "#d29922", property = "#39c5cf", function = "#bc8cff",
                ),
            ),
            BundledThemeSpec(
                id = "github-light", name = "GitHub Light", familyId = "github", isDark = false,
                neutral = "#ffffff", ink = "#24292f", primary = "#0969da", accent = "#1b7c83",
                success = "#1a7f37", warning = "#9a6700", error = "#cf222e", info = "#bc4c00",
                overrides = SyntaxOverrides(
                    comment = "#57606a", keyword = "#cf222e", string = "#0969da", type = "#bc4c00",
                    constant = "#1b7c83", variable = "#bc4c00", property = "#1b7c83", function = "#8250df",
                ),
            ),
        )
    }

    val families: List<ColorThemeFamily> by lazy {
        specs.filter { it.isDark }.map {
            ColorThemeFamily(
                familyId = it.familyId,
                name = it.name,
                darkThemeId = it.id,
                lightThemeId = "${it.familyId}-light",
            )
        }
    }

    val all: List<TroupePalette> by lazy { specs.map(::buildPalette) }

    private val byId: Map<String, TroupePalette> by lazy { all.associateBy { it.id } }

    fun resolve(themeOrFamilyId: String?, appearance: AppearanceMode, systemDark: Boolean): TroupePalette {
        val wantDark = when (appearance) {
            AppearanceMode.Dark -> true
            AppearanceMode.Light -> false
            AppearanceMode.System -> systemDark
        }
        val clean = themeOrFamilyId?.trim().orEmpty().ifEmpty { DEFAULT_FAMILY_ID }
        val family = clean.removeSuffix("-light")
        val targetId = if (wantDark) family else "$family-light"
        return byId[targetId] ?: byId[if (wantDark) DEFAULT_THEME_ID else "$DEFAULT_THEME_ID-light"]!!
    }

    fun byId(id: String): TroupePalette = byId[id] ?: byId.getValue(DEFAULT_THEME_ID)

    private fun buildPalette(spec: BundledThemeSpec): TroupePalette {
        val chrome = spec.explicitChrome
        val baseBg = chrome?.background ?: run {
            val tinted = mixHex(spec.neutral, spec.primary, NEUTRAL_BRAND_TINT)
            if (!spec.isDark) darkenHex(tinted, LIGHT_CANVAS_DARKEN) else tinted
        }
        val fgHex = chrome?.foreground ?: mixHex(spec.ink, spec.primary, NEUTRAL_BRAND_TINT)
        val cardHex = chrome?.card ?: if (spec.isDark) lightenHex(baseBg, 0.06) else darkenHex(baseBg, 0.02)
        val secondaryHex = chrome?.secondary ?: if (spec.isDark) lightenHex(baseBg, 0.08) else darkenHex(baseBg, 0.04)
        val popoverHex = chrome?.elevated ?: cardHex
        val sidebarHex = chrome?.sidebar ?: if (spec.isDark) lightenHex(baseBg, 0.04) else mixHex(baseBg, "#000000", 0.03)
        val mutedHex = chrome?.muted ?: if (spec.isDark) lightenHex(baseBg, 0.11) else darkenHex(baseBg, 0.06)
        val borderHex = chrome?.border ?: if (spec.isDark) lightenHex(baseBg, 0.14) else darkenHex(baseBg, 0.12)
        val inputHex = chrome?.input ?: borderHex
        val fieldBorderHex = solveContrast(borderHex, listOf(cardHex, baseBg, popoverHex), UI_CONTRAST_MIN)
        val popoverEdgeHex = mixHex(popoverHex, fgHex, 0.195)

        val readableSurfaces = listOf(cardHex, secondaryHex)
        val mutedFgHex = solveContrast(
            chrome?.mutedForeground ?: mixHex(fgHex, baseBg, 0.5),
            readableSurfaces,
            TEXT_CONTRAST_MIN,
        )
        val secondaryFgHex = if (chrome != null) {
            solveContrast(chrome.secondaryForeground, readableSurfaces, TEXT_CONTRAST_MIN)
        } else {
            mixHex(fgHex, baseBg, 0.35)
        }
        val disabledFgHex = solveContrast(mixHex(fgHex, baseBg, 0.45), listOf(mutedHex, cardHex, secondaryHex), UI_CONTRAST_MIN)

        val primaryTextHex = solveContrast(spec.primary, readableSurfaces, TEXT_CONTRAST_MIN)
        val successTextHex = solveContrast(spec.success, readableSurfaces, TEXT_CONTRAST_MIN)
        val warningTextHex = solveContrast(spec.warning, readableSurfaces, TEXT_CONTRAST_MIN)
        val errorTextHex = solveContrast(spec.error, readableSurfaces, TEXT_CONTRAST_MIN)

        val primaryFillHex = chrome?.brandFill ?: solidFillHex(spec.primary)
        val primaryFillHoverHex = chrome?.brandFillHover ?: mixHex(primaryFillHex, "#000000", 0.10)
        val primaryFillActiveHex = chrome?.brandFillActive ?: mixHex(primaryFillHex, "#000000", 0.18)
        val primaryFgHex = if (chrome != null) "#ffffff" else fillInkHex(primaryFillHex)

        val destructiveFillHex = solidFillHex(spec.error)
        val successFillHex = solidFillHex(spec.success)
        val warningFgHex = fillInkHex(warningTextHex)
        val focusRingHex = solveContrast(mixHex(cardHex, fgHex, 0.2), listOf(cardHex, baseBg, popoverHex), FOCUS_CONTRAST_MIN)
        val controlFillHex = chrome?.controlFill ?: if (spec.isDark) lightenHex(popoverHex, 0.04) else cardHex
        val keycapFillHex = chrome?.keycapFill ?: if (spec.isDark) mutedHex else cardHex

        val diffAddedHex = oklchToHex(OklchColor.Oklch(0.72, 0.192, 149.5))
        val diffAddedFgHex = if (spec.isDark) {
            oklchToHex(OklchColor.Oklch(0.786, 0.138, 154.0))
        } else {
            oklchToHex(OklchColor.Oklch(0.507, 0.103, 153.0))
        }

        val syntax = resolveSyntax(spec)
        val terminal = spec.terminalOverride ?: deriveTerminalPalette(spec, baseBg, fgHex, secondaryHex)

        return TroupePalette(
            id = spec.id,
            name = spec.name,
            familyId = spec.familyId,
            isDark = spec.isDark,
            background = parseHexColor(baseBg),
            card = parseHexColor(cardHex),
            elevated = parseHexColor(popoverHex),
            popover = parseHexColor(popoverHex),
            sidebarBackground = parseHexColor(sidebarHex),
            secondarySurface = parseHexColor(secondaryHex),
            mutedSurface = parseHexColor(mutedHex),
            border = parseHexColor(borderHex),
            input = parseHexColor(inputHex),
            fieldBorder = parseHexColor(fieldBorderHex),
            popoverEdge = parseHexColor(popoverEdgeHex),
            foreground = parseHexColor(fgHex),
            secondary = parseHexColor(secondaryFgHex),
            muted = parseHexColor(mutedFgHex),
            disabledForeground = parseHexColor(disabledFgHex),
            brand = parseHexColor(primaryFillHex),
            brandHover = parseHexColor(primaryFillHoverHex),
            brandActive = parseHexColor(primaryFillActiveHex),
            brandForeground = parseHexColor(primaryFgHex),
            brandText = parseHexColor(primaryTextHex),
            success = parseHexColor(successTextHex),
            successFill = parseHexColor(successFillHex),
            warning = parseHexColor(warningTextHex),
            warningForeground = parseHexColor(warningFgHex),
            error = parseHexColor(errorTextHex),
            destructiveFill = parseHexColor(destructiveFillHex),
            info = parseHexColor(spec.info),
            connection = parseHexColor(spec.info),
            diffAdded = parseHexColor(diffAddedHex),
            diffAddedForeground = parseHexColor(diffAddedFgHex),
            diffModified = parseHexColor(spec.warning),
            overlay = Color(0xFF000000),
            controlFill = parseHexColor(controlFillHex),
            keycapFill = parseHexColor(keycapFillHex),
            focusRing = parseHexColor(focusRingHex),
            terminal = terminal,
            syntax = syntax,
        )
    }

    private fun resolveSyntax(spec: BundledThemeSpec): SyntaxPalette {
        val o = spec.overrides
        val muted = mixHex(spec.ink, spec.neutral, 0.45)
        return SyntaxPalette(
            keyword = parseHexColor(o.keyword ?: spec.primary),
            comment = parseHexColor(o.comment ?: muted),
            string = parseHexColor(o.string ?: spec.success),
            number = parseHexColor(o.constant ?: spec.warning),
            bool = parseHexColor(o.primitive ?: spec.info),
            variable = parseHexColor(o.variable ?: spec.ink),
            definition = parseHexColor(o.definition ?: o.variable ?: spec.ink),
            function = parseHexColor(o.function ?: spec.accent),
            type = parseHexColor(o.type ?: spec.info),
            property = parseHexColor(o.property ?: spec.ink),
            operator = parseHexColor(o.operator ?: spec.ink),
            punctuation = parseHexColor(o.punctuation ?: mixHex(spec.ink, spec.neutral, 0.2)),
            tag = parseHexColor(o.tag ?: o.keyword ?: spec.primary),
            attributeName = parseHexColor(o.attribute ?: o.property ?: spec.info),
            attributeValue = parseHexColor(o.string ?: spec.success),
            heading = parseHexColor(o.type ?: spec.primary),
            link = parseHexColor(spec.primary),
        )
    }

    private fun deriveTerminalPalette(
        spec: BundledThemeSpec,
        bgHex: String,
        fgHex: String,
        secondaryHex: String,
    ): AnsiPalette {
        val bright = { hex: String -> if (spec.isDark) lightenHex(hex, 0.14) else darkenHex(hex, 0.12) }
        return AnsiPalette(
            background = bgHex,
            foreground = fgHex,
            cursor = fgHex,
            cursorAccent = bgHex,
            selectionBackground = mixHex(bgHex, spec.primary, if (spec.isDark) 0.35 else 0.25),
            selectionForeground = fgHex,
            selectionInactiveBackground = secondaryHex,
            black = if (spec.isDark) darkenHex(bgHex, 0.2) else darkenHex(bgHex, 0.12),
            red = spec.error,
            green = spec.success,
            yellow = spec.warning,
            blue = spec.primary,
            magenta = spec.accent,
            cyan = spec.info,
            white = fgHex,
            brightBlack = mixHex(bgHex, fgHex, 0.45),
            brightRed = bright(spec.error),
            brightGreen = bright(spec.success),
            brightYellow = bright(spec.warning),
            brightBlue = bright(spec.primary),
            brightMagenta = bright(spec.accent),
            brightCyan = bright(spec.info),
            brightWhite = if (spec.isDark) lightenHex(fgHex, 0.12) else darkenHex(fgHex, 0.12),
        )
    }
}
