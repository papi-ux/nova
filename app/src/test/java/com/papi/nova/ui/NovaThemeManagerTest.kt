package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
    fun highContrastOnAccentUsesDarkForegroundForReadableLightControls() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_HIGH_CONTRAST)
        val themedContext = ContextThemeWrapper(context, R.style.AppTheme_HighContrast)

        assertEquals(
            themedContext.getColor(R.color.nova_hc_bg_window),
            NovaThemeManager.getOnAccentColor(themedContext),
        )
    }

    @Test
    @Config(sdk = [33], qualifiers = "notnight")
    fun materialYouProfileActivitiesRetainSettingsPreferenceTheme() {
        listOf(
            com.papi.nova.ProfilesActivity::class.java,
            com.papi.nova.EditProfileActivity::class.java,
        ).forEach { activityClass ->
            val controller = Robolectric.buildActivity(activityClass)
            val activity = controller.get()
            NovaThemeManager.setTheme(activity, NovaThemeManager.THEME_MATERIAL_YOU)
            NovaThemeManager.applyTheme(activity)

            val preferenceTheme = TypedValue()
            assertTrue(
                "${activityClass.simpleName} must retain its settings preference overlay",
                activity.theme.resolveAttribute(androidx.preference.R.attr.preferenceTheme, preferenceTheme, true),
            )
            assertEquals(R.style.NovaPreferenceTheme_MaterialYou, preferenceTheme.resourceId)

            val windowSurface = TypedValue()
            assertTrue(
                "${activityClass.simpleName} must resolve a themed window surface",
                activity.theme.resolveAttribute(android.R.attr.colorBackground, windowSurface, true),
            )
            val expectedSurface = if (windowSurface.resourceId != 0) {
                activity.getColor(windowSurface.resourceId)
            } else {
                windowSurface.data
            }
            assertEquals(expectedSurface, NovaThemeManager.getActivityWindowSurfaceColor(activity))
            assertEquals(expectedSurface, activity.window.statusBarColor)
            assertEquals(expectedSurface, activity.window.navigationBarColor)
        }
    }

    @Test
    fun portableChromeGlintsWithAllFourSymbolAccentsAndOtherThemesWithOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_PORTABLE_CHROME)
        val portable = NovaThemeManager.getAmbientAccentColors(context)

        // The four tokens were declared and referenced nowhere. The resource guard passed
        // throughout, because it pinned that the hexes exist -- not that anything reads
        // them. This calls what SpaceParticleView calls, so removing the wiring fails here.
        assertEquals("all four face-button hues reach the ambient field", 4, portable.size)
        assertEquals(
            "and they are four distinct hues, not one repeated",
            4,
            portable.toSet().size
        )
        assertTrue(
            "cross blue leads, because it is also the theme accent",
            portable[0] == NovaThemeManager.getAccentColor(context)
        )

        // Every other theme has one accent, so the cycling must not leak into them.
        for (theme in listOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_MIAMI,
            NovaThemeManager.THEME_HIGH_CONTRAST
        )) {
            NovaThemeManager.setTheme(context, theme)
            val ambient = NovaThemeManager.getAmbientAccentColors(context)
            assertEquals("$theme glints with its one accent", 1, ambient.size)
            assertEquals(theme, NovaThemeManager.getAccentColor(context), ambient[0])
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "notnight")
    fun portableChromeUsesLightIconsOnItsGraphiteSystemBars() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        NovaThemeManager.setTheme(activity, NovaThemeManager.THEME_PORTABLE_CHROME)
        NovaThemeManager.applyTheme(activity)
        controller.setup()

        // configureSystemBars picks icon polarity by contrast against the window, so it
        // flipped on its own when the shell went graphite. This asserts it landed.
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertFalse(
            "graphite bars take light icons",
            insetsController.isAppearanceLightStatusBars
        )
        assertFalse(
            "graphite bars take light icons",
            insetsController.isAppearanceLightNavigationBars
        )

        controller.destroy()
    }

    @Test
    @Config(sdk = [33], qualifiers = "notnight")
    fun materialYouLightModeUsesLightSurfaceAndMatchingSystemBars() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        NovaThemeManager.setTheme(activity, NovaThemeManager.THEME_MATERIAL_YOU)
        NovaThemeManager.applyTheme(activity)
        controller.setup()

        val surface = resolveActivityWindowSurface(activity)
        val systemBars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertTrue("Material You must use a light surface in system light mode", ColorUtils.calculateLuminance(surface) > 0.5)
        assertEquals(surface, activity.window.statusBarColor)
        assertEquals(surface, activity.window.navigationBarColor)
        assertTrue("Light Material You surfaces need dark status-bar icons", systemBars.isAppearanceLightStatusBars)
        assertTrue("Light Material You surfaces need dark navigation-bar icons", systemBars.isAppearanceLightNavigationBars)
        controller.destroy()
    }

    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun materialYouDarkModeUsesDarkSurfaceAndMatchingSystemBars() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        NovaThemeManager.setTheme(activity, NovaThemeManager.THEME_MATERIAL_YOU)
        NovaThemeManager.applyTheme(activity)
        controller.setup()

        val surface = resolveActivityWindowSurface(activity)
        val systemBars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertTrue("Material You must use a dark surface in system dark mode", ColorUtils.calculateLuminance(surface) < 0.5)
        assertEquals(surface, activity.window.statusBarColor)
        assertEquals(surface, activity.window.navigationBarColor)
        assertTrue("Dark Material You surfaces need light status-bar icons", !systemBars.isAppearanceLightStatusBars)
        assertTrue("Dark Material You surfaces need light navigation-bar icons", !systemBars.isAppearanceLightNavigationBars)
        controller.destroy()
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

    @Test
    @Config(sdk = [33])
    fun tonalPillRolesMeetTextContrastAcrossCustomThemes() {
        val themes = listOf(
            "Polaris app" to R.style.AppTheme,
            "Portable app" to R.style.AppTheme_PortableChrome,
            "OLED app" to R.style.AppTheme_OLED,
            "Miami app" to R.style.AppTheme_Miami,
            "High Contrast app" to R.style.AppTheme_HighContrast,
            "Material You app" to R.style.AppTheme_MaterialYou,
            "Polaris settings" to R.style.SettingsTheme,
            "Portable settings" to R.style.SettingsTheme_PortableChrome,
            "OLED settings" to R.style.SettingsTheme_OLED,
            "Miami settings" to R.style.SettingsTheme_Miami,
            "High Contrast settings" to R.style.SettingsTheme_HighContrast,
            "Material You settings" to R.style.SettingsTheme_MaterialYou,
        )

        themes.forEach { (label, themeId) ->
            val themed = ContextThemeWrapper(context, themeId)
            val window = resolveThemeColor(themed, android.R.attr.colorBackground)
            val cardOverlay = (themed.getDrawable(R.drawable.nova_card_bg) as GradientDrawable)
                .color!!.defaultColor
            val card = ColorUtils.compositeColors(cardOverlay, window)
            val containerOverlay = resolveThemeColor(
                themed,
                com.google.android.material.R.attr.colorPrimaryContainer,
            )
            val foreground = resolveThemeColor(
                themed,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
            )
            if (label.startsWith("Polaris")) {
                assertEquals(themed.getColor(R.color.nova_accent_surface), containerOverlay)
                assertEquals(themed.getColor(R.color.nova_text_primary), foreground)
                assertEquals(
                    themed.getColor(R.color.nova_tonal_pill_pressed_polaris),
                    resolveThemeColor(themed, R.attr.novaTonalPillPressedColor),
                )
            }
            val pressedLayer = resolveThemeColor(themed, R.attr.novaTonalPillPressedColor)

            listOf("window" to window, "card" to card).forEach { (backdropName, backdrop) ->
                val container = ColorUtils.compositeColors(containerOverlay, backdrop)
                val textContrast = ColorUtils.calculateContrast(foreground, container)
                assertTrue("$label/$backdropName text contrast=$textContrast", textContrast >= 4.5)

                val pressed = ColorUtils.compositeColors(pressedLayer, container)
                val pressedDelta = ColorUtils.calculateContrast(pressed, container)
                assertTrue("$label/$backdropName pressed delta=$pressedDelta", pressedDelta >= 1.2)
            }
        }
    }

    private fun resolveThemeColor(themedContext: Context, attr: Int): Int {
        val value = TypedValue()
        check(themedContext.theme.resolveAttribute(attr, value, true))
        return if (value.resourceId != 0) themedContext.getColor(value.resourceId) else value.data
    }

    private fun resolveActivityWindowSurface(activity: Activity): Int {
        val value = TypedValue()
        check(activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true))
        return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
    }
}
