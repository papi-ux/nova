package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import com.papi.nova.ui.NovaThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibrarySurfacesTest {
    @Test
    fun librarySurfacesAdaptParticlesAndScrimByTheme() {
        val base = NovaComposeColors(
            window = Color(0xFF101018),
            card = Color(0xCC202030),
            dialog = Color(0xFF242438),
            badge = Color(0x33111122),
            divider = Color(0xFF444455),
            accent = Color(0xFF7C73FF),
            accentSurface = Color(0x227C73FF),
            warning = Color(0xFFFFCC00),
            textPrimary = Color(0xFFE8EEF5),
            textSecondary = Color(0xFFB8C0CC),
            textMuted = Color(0xFF77808C),
            onAccent = Color(0xFFFFFFFF)
        )

        val polaris = base.librarySurfaces(NovaThemeManager.THEME_POLARIS)
        val materialYou = base.librarySurfaces(NovaThemeManager.THEME_MATERIAL_YOU)
        val oled = base.librarySurfaces(NovaThemeManager.THEME_OLED)

        assertTrue(polaris.particlesEnabled)
        assertTrue(materialYou.particlesEnabled)
        assertFalse(oled.particlesEnabled)
        assertTrue(materialYou.backgroundScrim.alpha < polaris.backgroundScrim.alpha)
        assertEquals(0f, oled.backgroundScrim.alpha, 0.001f)
        assertEquals(base.accent, polaris.focusRing)
        assertEquals(Color.White, oled.onMedia)
    }
}
