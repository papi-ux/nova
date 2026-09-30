package com.papi.nova.ui.compose

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Background opacity is literal; readable scrims, text and focus are separate surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamPanelGlassTest {
    @Test fun everyPaletteKeepsTheSelectedGlassWithoutChangingItsFocusRing() {
        val colors = novaComposeColors(ApplicationProvider.getApplicationContext())
        val themes = listOf(NovaThemeManager.THEME_POLARIS, NovaThemeManager.THEME_MIAMI,
            NovaThemeManager.THEME_OLED, NovaThemeManager.THEME_HIGH_CONTRAST,
            NovaThemeManager.THEME_MATERIAL_YOU, NovaThemeManager.THEME_PORTABLE_CHROME)
        for (theme in themes) {
            val full = colors.librarySurfaces(theme, 1f)
            for (percent in NovaMenuPreferences.OPACITY_PRESETS) {
                val surfaces = colors.librarySurfaces(theme, percent / 100f)
                assertEquals("$theme at $percent%", percent / 100f, surfaces.panel.alpha, 0.005f)
                assertEquals(full.focusRing, surfaces.focusRing)
                assertEquals(full.focusHalo, surfaces.focusHalo)
            }
        }
    }
}
