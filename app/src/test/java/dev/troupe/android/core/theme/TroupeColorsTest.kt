package dev.troupe.android.core.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TroupeColorsTest {
    @Test fun darkPaletteMatchesTermul() {
        val p = TroupeColors.Dark
        assertEquals(Color(0xFF08090A), p.background)
        assertEquals(Color(0xFF0F1011), p.card)
        assertEquals(Color(0xFF161718), p.elevated)
        assertEquals(Color(0xFF23252A), p.border)
        assertEquals(Color(0xFFE5E5E6), p.foreground)
        assertEquals(Color(0xFFD0D6E0), p.secondary)
        assertEquals(Color(0xFF8A8F98), p.muted)
        assertEquals(Color(0xFF0D74CE), p.brand)
        assertEquals(Color(0xFF3B9EFF), p.brandText)
        assertEquals(Color(0xFF22C55E), p.success)
        assertEquals(Color(0xFFEAB308), p.warning)
        assertEquals(Color(0xFFEF4444), p.error)
        assertEquals(Color(0xFF38BDF8), p.info)
    }

    @Test fun lightPaletteMatchesTermul() {
        val p = TroupeColors.Light
        assertEquals(Color(0xFFFCFCFC), p.background)
        assertEquals(Color(0xFFFFFFFF), p.card)
        assertEquals(Color(0xFFEDEDED), p.border)
        assertEquals(Color(0xFF202020), p.foreground)
    }
}
