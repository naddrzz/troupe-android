package dev.troupe.android.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TroupeThemesTest {

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun allTwentyBundledThemesExistAndDeriveCleanly() {
        assertEquals(20, BundledThemes.all.size)
        assertEquals(10, BundledThemes.all.count { it.isDark })
        assertEquals(10, BundledThemes.all.count { !it.isDark })
        assertEquals(10, BundledThemes.families.size)

        for (palette in BundledThemes.all) {
            // Every theme's foreground on background must clear WCAG AA (4.5:1)
            val fgContrast = contrastRatio(palette.foreground, palette.background)
            assertTrue(
                "Theme ${palette.id} foreground contrast was $fgContrast",
                fgContrast >= 4.5,
            )

            // Solid brand button fill must keep AA readable label
            val brandContrast = contrastRatio(palette.brandForeground, palette.brand)
            assertTrue(
                "Theme ${palette.id} brand button contrast was $brandContrast",
                brandContrast >= 4.5,
            )

            // Destructive fill must keep AA readable label
            val destructiveContrast = contrastRatio(palette.brandForeground, palette.destructiveFill)
            assertTrue(
                "Theme ${palette.id} destructive contrast was $destructiveContrast",
                destructiveContrast >= 4.5,
            )
        }
    }

    @Test
    fun appearanceModeResolvesDarkAndLightSiblings() {
        val draculaLight = BundledThemes.resolve(
            themeOrFamilyId = "dracula",
            appearance = AppearanceMode.Light,
            systemDark = true,
        )
        assertEquals("dracula-light", draculaLight.id)
        assertFalse(draculaLight.isDark)

        val defaultDark = BundledThemes.resolve(
            themeOrFamilyId = null,
            appearance = AppearanceMode.System,
            systemDark = true,
        )
        assertEquals(BundledThemes.DEFAULT_THEME_ID, defaultDark.id)
        assertTrue(defaultDark.isDark)
    }
}
