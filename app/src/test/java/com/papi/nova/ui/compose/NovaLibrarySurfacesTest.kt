package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.papi.nova.ui.NovaMenuPreferences
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
    fun menuOpacityUsesAbsoluteOuterPanelWhileScalingNestedChromeAndPreservingFocus() {
        // A dark-text palette, which is what these floors are about. This used to borrow
        // Portable Chrome's, and kept passing after Portable Chrome went graphite only
        // because the fixture was a hardcoded copy -- it was exercising a theme that no
        // longer exists. Material You in light mode is the real dark-text case.
        val colors = darkTextColors()
        val full = colors.librarySurfaces(NovaThemeManager.THEME_MATERIAL_YOU, menuOpacityScale = 1f)
        val half = colors.librarySurfaces(NovaThemeManager.THEME_MATERIAL_YOU, menuOpacityScale = 0.5f)
        val zero = colors.librarySurfaces(NovaThemeManager.THEME_MATERIAL_YOU, menuOpacityScale = 0f)

        assertEquals(
            NovaMenuPreferences.readabilityScrimAlpha(
                baseAlpha = full.backgroundScrim.alpha,
                opacityScale = 0.5f,
                usesDarkText = true
            ),
            half.backgroundScrim.alpha,
            0.005f
        )
        assertEquals(1f, full.panel.alpha, 0.005f)
        assertEquals(NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA, half.panel.alpha, 0.005f)
        assertEquals(full.panelBorder.alpha * 0.5f, half.panelBorder.alpha, 0.005f)
        assertEquals(full.tile.alpha * 0.5f, half.tile.alpha, 0.005f)
        assertEquals(full.tileBorder.alpha * 0.5f, half.tileBorder.alpha, 0.005f)
        assertEquals(full.control.alpha * 0.5f, half.control.alpha, 0.005f)
        assertEquals(full.selectedControl.alpha * 0.5f, half.selectedControl.alpha, 0.005f)
        assertEquals(NovaMenuPreferences.MIN_DARK_TEXT_SCRIM_ALPHA, zero.backgroundScrim.alpha, 0.005f)
        assertEquals(Color.White, zero.backgroundScrim.copy(alpha = 1f))
        assertEquals(NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA, zero.panel.alpha, 0.001f)
        assertEquals(0f, zero.control.alpha, 0.001f)
        assertEquals(full.focusRing, zero.focusRing)
        assertEquals(full.focusHalo, zero.focusHalo)
        assertEquals(full.onMedia, zero.onMedia)
        assertEquals(full.onMediaSecondary, zero.onMediaSecondary)
    }

    @Test
    fun portableChromeLibrarySurfacesUseSmokedGraphiteShellAndSubduedParticles() {
        val portableColors = portableChromeColors()

        val portableChrome = portableColors.librarySurfaces(NovaThemeManager.THEME_PORTABLE_CHROME)

        assertTrue(portableChrome.particlesEnabled)
        assertEquals(portableColors.accent, portableChrome.focusRing)
        assertTrue(portableChrome.backgroundScrim.alpha >= 0.24f)
        assertTrue(portableChrome.panelBorder.alpha in 0.40f..0.52f)

        // These were the lowest values of any theme, each tuned down because a bright shell
        // shows everything. On graphite they would read as invisible.
        assertTrue(
            "particles have to be visible against graphite",
            portableChrome.particleAlpha in 0.34f..0.54f
        )
        assertTrue(
            "the focus halo is no longer the faintest in the app",
            portableChrome.focusHalo.alpha >= 0.20f
        )
        // At full opacity every theme's outer panel is absolute, so the light/dark-text
        // split only shows once the user turns menus down. Dark text floored Portable at
        // MIN_DARK_TEXT_SURFACE_ALPHA; silver text is free to go further, which is the
        // contract rule about not becoming an opaque slab that it had been breaking.
        val dimmed = portableColors.librarySurfaces(
            NovaThemeManager.THEME_PORTABLE_CHROME,
            menuOpacityScale = 0.5f,
        )
        assertTrue(
            "a turned-down Portable Chrome panel is no longer held above the dark-text floor",
            dimmed.panel.alpha < NovaMenuPreferences.MIN_DARK_TEXT_SURFACE_ALPHA
        )
        assertEquals(
            "missing artwork sits on graphite. Portable was the only theme putting its " +
                "window colour here, because its window used to be light",
            Color(0xFF101216),
            portableChrome.mediaPlaceholder
        )
    }

    @Test
    fun portableChromeOnAccentUsesWhiteForReadableBlueControls() {
        // This used to assert Color.White against a fixture that sets onAccent to
        // Color.White -- a constant compared with itself, which passes no matter what the
        // accent becomes. What must actually hold is that the label on an accent-filled
        // control is readable against it.
        val colors = portableChromeColors()
        assertTrue(
            "a label on an accent fill has to be readable against it",
            contrastRatio(colors.accent, colors.onAccent) >= 4.5
        )
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
            "Miami outer panels should be fully opaque at the 100% endpoint",
            1f,
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

    /** The light palette the menu-opacity floors are written for. */
    private fun darkTextColors(): NovaComposeColors = NovaComposeColors(
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

    /** WCAG contrast, from the relative luminance Compose already computes. */
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun portableChromeColors(): NovaComposeColors = NovaComposeColors(
        window = Color(0xFF14161A),
        card = Color(0xFF1E2228),
        dialog = Color(0xFF1E2228),
        badge = Color(0x334B6686),
        divider = Color(0xFF3A424C),
        accent = Color(0xFF5A93D6),
        accentSurface = Color(0x264B6686),
        warning = Color(0xFFFBBF24),
        textPrimary = Color(0xFFC9D1D9),
        textSecondary = Color(0xFF9AA4AF),
        textMuted = Color(0xFF838D9C),
        onAccent = Color(0xFF14161A)
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
