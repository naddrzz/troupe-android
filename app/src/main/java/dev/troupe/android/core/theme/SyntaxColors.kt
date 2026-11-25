package dev.troupe.android.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Syntax highlighting colors resolved per theme, matching Troupe's `resolveSyntaxColors`
 * (`src/renderer/lib/themes/resolve-syntax.ts` and `termul-syntax.ts`).
 */
@Immutable
data class SyntaxPalette(
    val keyword: Color,
    val comment: Color,
    val string: Color,
    val number: Color,
    val bool: Color,
    val variable: Color,
    val definition: Color,
    val function: Color,
    val type: Color,
    val property: Color,
    val operator: Color,
    val punctuation: Color,
    val tag: Color,
    val attributeName: Color,
    val attributeValue: Color,
    val heading: Color,
    val link: Color,
)

data class SyntaxOverrides(
    val comment: String? = null,
    val keyword: String? = null,
    val string: String? = null,
    val type: String? = null,
    val constant: String? = null,
    val primitive: String? = null,
    val variable: String? = null,
    val definition: String? = null,
    val property: String? = null,
    val function: String? = null,
    val operator: String? = null,
    val punctuation: String? = null,
    val tag: String? = null,
    val attribute: String? = null,
)
