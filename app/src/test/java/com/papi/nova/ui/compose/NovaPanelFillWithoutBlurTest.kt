package com.papi.nova.ui.compose

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shield runs API 30, where no panel blurs the window behind it, and at the default menu
 * opacity poster art and host buttons read sharp through every panel's rows (R13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelFillWithoutBlurTest {
    private val defaultScale = NovaMenuPreferences.opacityScale(NovaMenuPreferences.DEFAULT_OPACITY_PERCENT)
    private fun colors() = novaComposeColors(ApplicationProvider.getApplicationContext())

    @Test
    fun withoutBlurAPanelIsNearlyOpaque() {
        for (theme in listOf(NovaThemeManager.THEME_POLARIS, NovaThemeManager.THEME_MIAMI, NovaThemeManager.THEME_OLED)) {
            val panel = colors().librarySurfaces(theme, defaultScale, blurAvailable = false).panel
            assertTrue("$theme panel alpha ${panel.alpha}", panel.alpha >= NO_BLUR_MIN_PANEL_ALPHA)
        }
    }

    @Test
    fun withBlurTheGlassKeepsThePlayersOpacity() {
        val panel = colors().librarySurfaces(NovaThemeManager.THEME_POLARIS, defaultScale, blurAvailable = true).panel
        assertEquals(defaultScale, panel.alpha, 0.01f)
    }
}
