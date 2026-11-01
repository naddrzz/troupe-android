package dev.troupe.android.terminal.render

import kotlinx.serialization.Serializable

/**
 * Terminal colors as an xterm.js `ITheme`. [Termul] copies termul's dark terminal theme:
 * the 16 ANSI overrides of `TERMUL_DARK_TERMINAL` (`src/renderer/lib/themes/termul-terminal.ts`)
 * over `paletteToXtermTheme`'s base, where background is the dark chrome's void, foreground,
 * cursor and white are its bone, and the cursor accent is the background.
 */
@Serializable
data class AnsiPalette(
    val background: String,
    val foreground: String,
    val cursor: String,
    val cursorAccent: String,
    val selectionBackground: String,
    val selectionForeground: String,
    val selectionInactiveBackground: String,
    val black: String,
    val red: String,
    val green: String,
    val yellow: String,
    val blue: String,
    val magenta: String,
    val cyan: String,
    val white: String,
    val brightBlack: String,
    val brightRed: String,
    val brightGreen: String,
    val brightYellow: String,
    val brightBlue: String,
    val brightMagenta: String,
    val brightCyan: String,
    val brightWhite: String,
) {
    companion object {
        val Termul = AnsiPalette(
            background = "#08090a",
            foreground = "#e5e5e6",
            cursor = "#e5e5e6",
            cursorAccent = "#08090a",
            selectionBackground = "#2958a3",
            selectionForeground = "#e5e5e6",
            selectionInactiveBackground = "#262727",
            black = "#070809",
            red = "#ef4444",
            green = "#22c55e",
            yellow = "#eab308",
            blue = "#3b82f6",
            magenta = "#3b82f6",
            cyan = "#38bdf8",
            white = "#e5e5e6",
            brightBlack = "#6b6c6d",
            brightRed = "#f16060",
            brightGreen = "#43ce76",
            brightYellow = "#edbe2d",
            brightBlue = "#5895f7",
            brightMagenta = "#5895f7",
            brightCyan = "#56c7f9",
            brightWhite = "#e8e8e9",
        )
    }
}
