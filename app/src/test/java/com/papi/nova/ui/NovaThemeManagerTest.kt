package com.papi.nova.ui

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
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
    fun portableChromeThemeIsRecognizedStoredAndLabeled() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)

        assertEquals(NovaThemeManager.THEME_PORTABLE_CHROME, NovaThemeManager.getTheme(context))
        assertEquals("Portable Chrome", NovaThemeManager.getThemeLabel(context))
    }

    @Test
    fun unknownThemeFallsBackToPolaris() {
        NovaThemeManager.setTheme(context, "south_beach_laser_flamingo")

        assertEquals(NovaThemeManager.THEME_POLARIS, NovaThemeManager.getTheme(context))
    }

    @Test
    @Config(sdk = [30])
    fun cycleThemeIncludesMiamiAndSkipsUnavailableMaterialYou() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_POLARIS)

        assertEquals(NovaThemeManager.THEME_OLED, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_MIAMI, NovaThemeManager.cycleTheme(context))
        assertEquals(NovaThemeManager.THEME_PORTABLE_CHROME, NovaThemeManager.cycleTheme(context))
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
    fun portableChromeThemeResolvesSemanticColors() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)

        assertEquals(context.getColor(R.color.nova_portable_bg_window), NovaThemeManager.getWindowBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_portable_bg_card), NovaThemeManager.getCardBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_portable_dialog_bg), NovaThemeManager.getDialogBackgroundColor(context))
        assertEquals(context.getColor(R.color.nova_portable_accent), NovaThemeManager.getAccentColor(context))
        assertEquals(context.getColor(R.color.nova_portable_accent_surface), NovaThemeManager.getAccentSurfaceColor(context))
        assertEquals(context.getColor(R.color.nova_portable_text_primary), NovaThemeManager.getTextPrimaryColor(context))
        assertEquals(context.getColor(R.color.nova_portable_text_secondary), NovaThemeManager.getTextSecondaryColor(context))
        assertEquals(context.getColor(R.color.nova_portable_text_muted), NovaThemeManager.getTextMutedColor(context))
        assertEquals(context.getColor(R.color.nova_portable_divider), NovaThemeManager.getDividerColor(context))
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
}
