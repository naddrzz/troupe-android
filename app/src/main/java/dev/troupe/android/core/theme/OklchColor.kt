package dev.troupe.android.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OKLCH + WCAG contrast math ported 1:1 from Troupe's `src/renderer/lib/themes/color-utils.ts`
 * and `solid-fills.ts`, so every bundled theme produces the same semantic tokens on Android.
 */
object OklchColor {
    const val TEXT_CONTRAST_MIN = 4.5
    const val UI_CONTRAST_MIN = 3.0
    const val FOCUS_CONTRAST_MIN = 4.0
    const val SOLID_FILL_MAX_L = 0.55
    const val FILL_INK_L = 0.218

    data class Rgb(val r: Int, val g: Int, val b: Int)
    data class Oklch(val l: Double, val c: Double, val h: Double)

    fun parseHexRgb(hex: String): Rgb {
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            3 -> Rgb(
                r = "${clean[0]}${clean[0]}".toInt(16),
                g = "${clean[1]}${clean[1]}".toInt(16),
                b = "${clean[2]}${clean[2]}".toInt(16),
            )
            6 -> Rgb(
                r = clean.substring(0, 2).toInt(16),
                g = clean.substring(2, 4).toInt(16),
                b = clean.substring(4, 6).toInt(16),
            )
            8 -> Rgb(
                r = clean.substring(0, 2).toInt(16),
                g = clean.substring(2, 4).toInt(16),
                b = clean.substring(4, 6).toInt(16),
            )
            else -> error("Invalid hex color: $hex")
        }
    }

    /** Parses `#rgb`, `#rrggbb`, or `#rrggbbaa` (CSS hex with trailing alpha) to Compose [Color]. */
    fun parseHexColor(hex: String): Color {
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            3 -> {
                val rgb = parseHexRgb(hex)
                Color(rgb.r, rgb.g, rgb.b)
            }
            6 -> {
                val rgb = parseHexRgb(hex)
                Color(rgb.r, rgb.g, rgb.b)
            }
            8 -> {
                val r = clean.substring(0, 2).toInt(16)
                val g = clean.substring(2, 4).toInt(16)
                val b = clean.substring(4, 6).toInt(16)
                val a = clean.substring(6, 8).toInt(16)
                Color(r, g, b, a)
            }
            else -> error("Invalid hex color: $hex")
        }
    }

    fun normalizeHex(hex: String): String {
        val rgb = parseHexRgb(hex)
        return "#%02x%02x%02x".format(rgb.r, rgb.g, rgb.b)
    }

    fun mixHex(colorA: String, colorB: String, weightB: Double): String {
        val ca = parseHexRgb(colorA)
        val cb = parseHexRgb(colorB)
        val w = weightB.coerceIn(0.0, 1.0)
        val r = (ca.r * (1.0 - w) + cb.r * w).roundToInt().coerceIn(0, 255)
        val g = (ca.g * (1.0 - w) + cb.g * w).roundToInt().coerceIn(0, 255)
        val b = (ca.b * (1.0 - w) + cb.b * w).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(r, g, b)
    }

    fun lightenHex(hex: String, amount: Double): String = mixHex(hex, "#ffffff", amount)

    fun darkenHex(hex: String, amount: Double): String = mixHex(hex, "#000000", amount)

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.coerceAtLeast(0.0).pow(1.0 / 2.4) - 0.055

    fun relativeLuminance(hex: String): Double {
        val (r, g, b) = parseHexRgb(hex)
        return 0.2126 * srgbToLinear(r / 255.0) +
            0.7152 * srgbToLinear(g / 255.0) +
            0.0722 * srgbToLinear(b / 255.0)
    }

    fun contrastRatio(hexA: String, hexB: String): Double {
        val a = relativeLuminance(hexA)
        val b = relativeLuminance(hexB)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    fun hexToOklch(hex: String): Oklch {
        val (r, g, b) = parseHexRgb(hex)
        val lr = srgbToLinear(r / 255.0)
        val lg = srgbToLinear(g / 255.0)
        val lb = srgbToLinear(b / 255.0)
        val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        val L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
        val A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
        val B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
        val hDeg = atan2(B, A) * 180.0 / PI
        return Oklch(L, hypot(A, B), if (hDeg < 0) hDeg + 360.0 else hDeg)
    }

    private fun oklchToLinearRgb(color: Oklch): Triple<Double, Double, Double> {
        val rad = color.h * PI / 180.0
        val a = color.c * cos(rad)
        val b = color.c * sin(rad)
        val lCube = (color.l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val mCube = (color.l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val sCube = (color.l - 0.0894841775 * a - 1.291485548 * b).pow(3)
        return Triple(
            4.0767416621 * lCube - 3.3077115913 * mCube + 0.2309699292 * sCube,
            -1.2684380046 * lCube + 2.6097574011 * mCube - 0.3413193965 * sCube,
            -0.0041960863 * lCube - 0.7034186147 * mCube + 1.707614701 * sCube,
        )
    }

    fun oklchToHex(color: Oklch): String {
        var c = color.c
        var rgb = oklchToLinearRgb(color.copy(c = c))
        while (c > 0.0 && (rgb.first < -1e-6 || rgb.first > 1.0 + 1e-6 ||
                rgb.second < -1e-6 || rgb.second > 1.0 + 1e-6 ||
                rgb.third < -1e-6 || rgb.third > 1.0 + 1e-6)
        ) {
            c = max(0.0, c - 0.002)
            rgb = oklchToLinearRgb(color.copy(c = c))
        }
        val r = (linearToSrgb(rgb.first).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val g = (linearToSrgb(rgb.second).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val b = (linearToSrgb(rgb.third).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return "#%02x%02x%02x".format(r, g, b)
    }

    fun holdHue(sourceHex: String, lightness: Double): String {
        val src = hexToOklch(sourceHex)
        return oklchToHex(Oklch(lightness, src.c, src.h))
    }

    fun ensureContrast(fg: String, bg: String, minRatio: Double): String {
        if (contrastRatio(fg, bg) >= minRatio) return normalizeHex(fg)
        val color = hexToOklch(fg)
        val step = if (hexToOklch(bg).l < 0.6) 0.005 else -0.005
        var candidate = normalizeHex(fg)
        var l = color.l + step
        while (l in 0.0..1.0) {
            candidate = oklchToHex(color.copy(l = l))
            if (contrastRatio(candidate, bg) >= minRatio) return candidate
            l += step
        }
        return candidate
    }

    fun solveContrast(color: String, surfaces: List<String>, minRatio: Double): String {
        if (surfaces.all { contrastRatio(color, it) >= minRatio }) return normalizeHex(color)
        var target = minRatio
        while (target <= 21.0) {
            var candidate = color
            for (bg in surfaces) {
                candidate = ensureContrast(candidate, bg, target)
            }
            if (surfaces.all { contrastRatio(candidate, it) >= minRatio }) return candidate
            target += 0.05
        }
        return normalizeHex(color)
    }

    fun solidFillHex(hex: String): String {
        val (l, c, h) = hexToOklch(hex)
        val clamped = oklchToHex(Oklch(min(l, SOLID_FILL_MAX_L), c, h))
        return ensureContrast(clamped, "#ffffff", TEXT_CONTRAST_MIN)
    }

    fun fillInkHex(fillHex: String): String {
        val fill = hexToOklch(fillHex)
        val seedL = if (fill.l > 0.6) FILL_INK_L else 0.98
        return ensureContrast(oklchToHex(Oklch(seedL, fill.c, fill.h)), fillHex, TEXT_CONTRAST_MIN)
    }
}
