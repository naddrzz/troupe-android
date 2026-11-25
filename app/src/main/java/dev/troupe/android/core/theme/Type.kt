package dev.troupe.android.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.troupe.android.R

/**
 * Inter Variable, bundled under OFL 1.1 (`licenses/OFL-Inter.txt`).
 * Each weight pins its `wght` axis so Android renders real strokes rather than faking bold.
 */
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val MonoFamily = FontFamily.Monospace

/** Adds `font-variant-numeric: tabular-nums` for counts, times, diff stats, and line numbers. */
fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Troupe V2 phone text scale (`docs/design/typography.md` & `composer-v2-handoff.md`):
 * - Hero start line: 22/28 (`headlineSmall`)
 * - Input fields & primary phone rows: 16/24 (`bodyLarge`)
 * - Phone row title / header title: 15/20 (`titleSmall` / `bodyMedium`)
 * - Subtitles / captions / badges: 14/18 (`labelLarge`) and 12/16 (`bodySmall` / `labelMedium`)
 * Nothing goes below 12sp.
 */
val TroupeTypography = Typography(
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.33).sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
