package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaPanelMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-game #12: nothing blurs the stream behind the Command Center, and at 90% menu opacity the
 * game's text read through its rows ("Press 90% to Start"). Over the stream the panel's fill keeps
 * a floor at which the brightest game text behind it cannot be told from black, and the panel's
 * own text keeps at least 4.5:1 whatever the game shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamPanelGlassTest {
    private val colors = novaComposeColors(ApplicationProvider.getApplicationContext())

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun theGameCannotReadThroughTheCommandCenter() {
        val themes = listOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_MIAMI,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_HIGH_CONTRAST,
            NovaThemeManager.THEME_MATERIAL_YOU,
            NovaThemeManager.THEME_PORTABLE_CHROME,
        )
        for (theme in themes) {
            for (percent in NovaMenuPreferences.OPACITY_PRESETS) {
                val scale = NovaMenuPreferences.opacityScale(percent)
                val surfaces = colors.librarySurfaces(theme, scale).overStream()
                val scrim = surfaces.backgroundScrim.copy(
                    alpha = NovaMenuPreferences.readabilityScrimAlpha(
                        NovaPanelMetrics.StreamScrimAlpha,
                        scale,
                        usesDarkText = colors.textPrimary.luminance() < 0.5f,
                    ),
                )
                val overWhite = surfaces.panel.compositeOver(scrim.compositeOver(Color.White))
                val overBlack = surfaces.panel.compositeOver(scrim.compositeOver(Color.Black))
                val bleed = contrast(overWhite, overBlack)
                assertTrue("$theme at $percent%: white game text reads through at $bleed:1", bleed <= 1.05f)
                for (text in listOf(colors.textPrimary, colors.textSecondary)) {
                    val worst = minOf(contrast(text, overWhite), contrast(text, overBlack))
                    assertTrue("$theme at $percent%: panel text at $worst:1", worst >= 4.5f)
                }
            }
        }
    }

    @Test
    fun theStreamSurfacesTakeTheFloor() {
        val glass = colors.librarySurfaces(NovaThemeManager.THEME_POLARIS, 0.64f).overStream()
        // A colour keeps its alpha in 8 bits.
        assertTrue(glass.panel.alpha >= STREAM_MIN_PANEL_ALPHA - 1f / 255f)
    }
}
