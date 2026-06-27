package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import com.papi.nova.ui.NovaSheetChrome
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
        val highContrast = base.librarySurfaces(NovaThemeManager.THEME_HIGH_CONTRAST)

        assertTrue(polaris.particlesEnabled)
        assertTrue(materialYou.particlesEnabled)
        assertFalse(oled.particlesEnabled)
        assertTrue(highContrast.particlesEnabled)
        assertTrue(materialYou.backgroundScrim.alpha < polaris.backgroundScrim.alpha)
        assertTrue(highContrast.backgroundScrim.alpha > polaris.backgroundScrim.alpha)
        assertTrue(highContrast.panelBorder.alpha > polaris.panelBorder.alpha)
        assertTrue(highContrast.tile.alpha > polaris.tile.alpha)
        assertTrue(highContrast.particleAlpha < polaris.particleAlpha)
        assertEquals(0f, oled.backgroundScrim.alpha, 0.001f)
        assertEquals(base.accent, polaris.focusRing)
        assertEquals(Color.White, oled.onMedia)
    }

    @Test
    fun portableChromeLibrarySurfacesUseSmokedGraphiteShellAndSubduedParticles() {
        val portableColors = portableChromeColors()

        val portableChrome = portableColors.librarySurfaces(NovaThemeManager.THEME_PORTABLE_CHROME)

        assertTrue(portableChrome.particlesEnabled)
        assertEquals(portableColors.accent, portableChrome.focusRing)
        assertTrue(portableChrome.backgroundScrim.alpha >= 0.24f)
        assertEquals(NovaSheetChrome.PORTABLE_CHROME_SHEET_GLASS_ALPHA, portableChrome.panel.alpha, 0.001f)
        assertTrue(portableChrome.panelBorder.alpha in 0.40f..0.52f)
        assertTrue(portableChrome.particleAlpha in 0.16f..0.28f)
        assertTrue(portableChrome.focusHalo.alpha < 0.18f)
        assertEquals(Color(0xFFA2ADBA), portableChrome.mediaPlaceholder)
    }

    @Test
    fun portableChromeOnAccentUsesWhiteForReadableBlueControls() {
        assertEquals(Color.White, portableChromeColors().onAccent)
    }
    @Test
    fun miamiLibrarySurfacesUsePlumGlassMagentaFocusAndParticles() {
        val miamiColors = miamiColors()

        val miami = miamiColors.librarySurfaces(NovaThemeManager.THEME_MIAMI)

        assertTrue(miami.particlesEnabled)
        assertEquals(miamiColors.accent, miami.focusRing)
        assertTrue(
            "Miami halo should be stronger than default but not a hot pink foghorn",
            miami.focusHalo.alpha in 0.24f..0.34f
        )
        assertEquals(
            "Miami panels should use shared readable plum glass alpha",
            NovaSheetChrome.MIAMI_SHEET_GLASS_ALPHA,
            miami.panel.alpha,
            0.001f
        )
        assertTrue(
            "Miami focused artwork needs enough scrim for rose text",
            miami.focusedArtworkScrim.alpha >= 0.72f
        )
        assertTrue(
            "Miami particles should be visible but calmer than default",
            miami.particleAlpha in 0.50f..0.85f
        )
    }

    @Test
    fun miamiOnAccentUsesDarkPlumForReadableCtaText() {
        assertEquals(Color(0xFF130817), miamiColors().onAccent)
    }

    private fun portableChromeColors(): NovaComposeColors = NovaComposeColors(
        window = Color(0xFFA2ADBA),
        card = Color(0xE6C4CDD8),
        dialog = Color(0xFFC0CAD5),
        badge = Color(0x334B6686),
        divider = Color(0xFF667484),
        accent = Color(0xFF4B6686),
        accentSurface = Color(0x264B6686),
        warning = Color(0xFFFBBF24),
        textPrimary = Color(0xFF1F2A35),
        textSecondary = Color(0xFF465464),
        textMuted = Color(0xFF5A6877),
        onAccent = Color.White
    )
    private fun miamiColors(): NovaComposeColors = NovaComposeColors(
        window = Color(0xFF130817),
        card = Color(0xE6241429),
        dialog = Color(0xFF241429),
        badge = Color(0x33FFD3E2),
        divider = Color(0xFF6C3C6F),
        accent = Color(0xFFFF5CAB),
        accentSurface = Color(0x1AFF5CAB),
        warning = Color(0xFFFBBF24),
        textPrimary = Color(0xFFFFF1F7),
        textSecondary = Color(0xFFFFD3E2),
        textMuted = Color(0xFFB785A1),
        onAccent = Color(0xFF130817)
    )
}
