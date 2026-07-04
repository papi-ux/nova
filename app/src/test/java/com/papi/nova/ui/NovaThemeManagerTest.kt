package com.papi.nova.ui

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NovaThemeManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun miamiThemeIsRecognizedStoredAndLabeled() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_MIAMI)

        assertEquals(NovaThemeManager.THEME_MIAMI, NovaThemeManager.getTheme(context))
        assertEquals("Miami Nebula", NovaThemeManager.getThemeLabel(context))
    }

    @Test
    fun unknownThemeFallsBackToPolaris() {
        NovaThemeManager.setTheme(context, "south_beach_laser_flamingo")

        assertEquals(NovaThemeManager.THEME_POLARIS, NovaThemeManager.getTheme(context))
    }

    @Test
    @Config(sdk = [30])
    fun cycleThemeIncludesPortableChromeMiamiAndSkipsUnavailableMaterialYou() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_POLARIS)

        assertEquals(NovaThemeManager.THEME_PORTABLE_CHROME, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_OLED, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_MIAMI, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_HIGH_CONTRAST, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_POLARIS, NovaThemeManager.cycleTheme(context))
    }

    @Test
    @Config(sdk = [31])
    fun cycleThemeKeepsMaterialYouAfterAccessibilityThemesWhenAvailable() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_HIGH_CONTRAST)

        assertEquals(NovaThemeManager.THEME_MATERIAL_YOU, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_POLARIS, NovaThemeManager.cycleTheme(context))
    }

    @Test
    fun miamiThemeResolvesSemanticColors() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_MIAMI)

        assertEquals(context.getColor(R.color.nova_miami_bg_window), NovaThemeManager.getWindowBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_miami_bg_card), NovaThemeManager.getCardBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_miami_dialog_bg), NovaThemeManager.getDialogBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_miami_accent), NovaThemeManager.getAccentColor(context))
        assertEquals(context.getColor(R.color.nova_miami_accent_surface), NovaThemeManager.getAccentSurfaceColor(context))
        assertEquals(context.getColor(R.color.nova_miami_text_primary), NovaThemeManager.getTextPrimaryColor(context))
        assertEquals(context.getColor(R.color.nova_miami_text_secondary), NovaThemeManager.getTextSecondaryColor(context))
        assertEquals(context.getColor(R.color.nova_miami_text_muted), NovaThemeManager.getTextMutedColor(context))
        assertEquals(context.getColor(R.color.nova_miami_divider), NovaThemeManager.getDividerColor(context))
    }

    @Test
    fun portableChromeAndPolarisHaveSeparateAccentTokens() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)
        val portableAccent = NovaThemeManager.getAccentColor(context)
        val portableSurface = NovaThemeManager.getAccentSurfaceColor(context)

        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_POLARIS)
        val polarisAccent = NovaThemeManager.getAccentColor(context)
        val polarisSurface = NovaThemeManager.getAccentSurfaceColor(context)

        assertEquals(context.getColor(R.color.nova_portable_accent), portableAccent)
        assertEquals(context.getColor(R.color.nova_portable_accent_surface), portableSurface)
        assertEquals(context.getColor(R.color.nova_polaris_accent), polarisAccent)
        assertEquals(context.getColor(R.color.nova_polaris_accent_surface), polarisSurface)
        assertTrue("Polaris Aurora must not inherit PSP green", polarisAccent != portableAccent)
    }



    @Test
    @Config(sdk = [31])
    fun materialYouHudTokensDoNotFallBackToPolarisInsideStreamTheme() {
        context.setTheme(R.style.StreamTheme)
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_MATERIAL_YOU)

        val accent = NovaThemeManager.getAccentColor(context)
        val accentSurface = NovaThemeManager.getAccentSurfaceColor(context)

        assertNotEquals(context.getColor(R.color.nova_polaris_accent), accent)
        assertNotEquals(context.getColor(R.color.nova_polaris_accent_surface), accentSurface)
    }
}
