package com.papi.nova.ui.compose

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A missing blur implementation must not override the player's opacity choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33])
class NovaPanelFillWithoutBlurTest {
    @Test fun everyPercentageControlsThePanelOnOlderAndNewerAndroid() {
        val colors = novaComposeColors(ApplicationProvider.getApplicationContext())
        for (theme in listOf(NovaThemeManager.THEME_POLARIS, NovaThemeManager.THEME_MIAMI, NovaThemeManager.THEME_OLED)) {
            for (percent in NovaMenuPreferences.OPACITY_PRESETS) {
                val panel = colors.librarySurfaces(theme, percent / 100f).panel
                assertEquals("$theme at $percent%", percent / 100f, panel.alpha, 0.005f)
            }
        }
    }
}
