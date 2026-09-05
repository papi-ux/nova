package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.util.Xml
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.utils.AndroidStreamDisplayTarget
import com.papi.nova.utils.DualScreenQuickMenuPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import org.xmlpull.v1.XmlPullParser

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaSettingsDefinitionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDefaultPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun definitionsCoverEveryPreferenceKeyFromLegacyXml() {
        val xmlKeys = legacyPreferenceKeys()
        val definitions = NovaSettingDefinitions.load(context)
        val definitionKeys = definitions.settings.map { it.key }.toSet()

        assertTrue("legacy XML should expose settings", xmlKeys.isNotEmpty())
        assertEquals(emptySet<String>(), xmlKeys - definitionKeys)
    }

    @Test
    fun definitionsExposeExpectedDashboardCategories() {
        val definitions = NovaSettingDefinitions.load(context)
        val categoryKeys = definitions.categories.map { it.key }

        assertEquals(
            listOf(
                "category_stream_quality",
                "category_display_audio",
                "category_dual_screen",
                "category_input",
                "category_overlays",
                "category_nova",
                "category_advanced"
            ),
            categoryKeys
        )
    }

    @Test
    fun resolutionAndFpsListsOfferTheCustomValuesTypedUnderAdvanced() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, "2560x1600")
            .putString(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING, "90")
            .commit()

        val definitions = NovaSettingDefinitions.load(context)
        val resolution = definitions.require("list_resolution").options
        val fps = definitions.require("list_fps").options

        val custom = resolution.single { it.value == "2560x1600" }
        assertTrue(custom.label, custom.label.startsWith(context.getString(R.string.resolution_prefix_custom)))
        assertEquals(resolution.size, resolution.map { it.value }.toSet().size)
        assertTrue(fps.any { it.value == "90.0" && it.label.startsWith(context.getString(R.string.resolution_prefix_custom)) })
    }

    @Test
    fun definitionsClassifyCoreControlTypes() {
        val definitions = NovaSettingDefinitions.load(context)

        assertEquals(NovaSettingType.Select, definitions.require("list_resolution").type)
        assertEquals(NovaSettingType.Slider, definitions.require("seekbar_bitrate_kbps").type)
        assertEquals(NovaSettingType.Toggle, definitions.require("checkbox_enable_hdr").type)
        assertEquals(NovaSettingType.Text, definitions.require("edit_diy_w_h").type)
        assertEquals(NovaSettingType.Action, definitions.require("pref_debug_info").type)
        assertFalse(definitions.require("nova_stream_preset").options.isEmpty())
    }

    @Test
    fun hdrSettingCopyExplainsItRequestsHostSupportedHdr() {
        val definitions = NovaSettingDefinitions.load(context)
        val hdr = definitions.require("checkbox_enable_hdr")

        assertEquals("Request HDR when host supports it", hdr.title)
        assertEquals("Asks Polaris for HDR/10-bit streaming when the game, host capture display, encoder, and client all support it. Private Stream may still run as 10-bit SDR and Command Center will explain why.", hdr.summary)
    }

    @Test
    fun definitionsExposeReadOnlyNovaAppVersion() {
        val definitions = NovaSettingDefinitions.load(context)
        val version = definitions.require("nova_app_version")

        assertEquals("category_nova", version.categoryKey)
        assertEquals(NovaSettingType.Action, version.type)
        assertEquals("Version", version.title)
        assertEquals(NovaSettingValue.StringValue(NovaAppVersion.current()), version.defaultValue)
    }

    @Test
    fun defaultStreamValuesMatchBalancedPreset() {
        val definitions = NovaSettingDefinitions.load(context)

        assertEquals(
            NovaSettingValue.StringValue(StreamPreset.BALANCED.key),
            definitions.require("nova_stream_preset").defaultValue
        )
        assertEquals(
            NovaSettingValue.StringValue(StreamPreset.BALANCED.resolution),
            definitions.require("list_resolution").defaultValue
        )
        assertEquals(StreamPreset.BALANCED.resolution, PreferenceConfiguration.DEFAULT_RESOLUTION)
    }

    @Test
    fun hudModePreferenceOffersCasualPerformanceAndDebugModes() {
        val definitions = NovaSettingDefinitions.load(context)
        val hudMode = definitions.require("nova_polaris_hud_mode")

        assertEquals(NovaSettingValue.StringValue("minimal"), hudMode.defaultValue)
        assertEquals(
            listOf("minimal", "performance", "debug"),
            hudMode.options.map { it.value }
        )
        assertEquals(
            listOf("Minimal", "Performance", "Debug"),
            hudMode.options.map { it.label }
        )
    }

    @Test
    fun novaTextSizeIsGlobalSystemRelativeInstantSlider() {
        val definitions = NovaSettingDefinitions.load(context)
        val textSize = definitions.require("nova_ui_font_scale_percent")

        assertEquals("Nova Text Size", textSize.title)
        assertEquals("category_nova", textSize.categoryKey)
        assertEquals(NovaSettingType.Slider, textSize.type)
        assertEquals(NovaSettingValue.IntValue(100), textSize.defaultValue)
        assertEquals(80, textSize.min)
        assertEquals(130, textSize.max)
        assertEquals(1, textSize.step)
        assertEquals("%", textSize.suffix)
        assertEquals(NovaSettingApplyTiming.Instant, textSize.applyTiming)
    }

    @Test
    fun hudOpacityPreferenceIsAdjustableInstantSlider() {
        val definitions = NovaSettingDefinitions.load(context)
        val hudOpacity = definitions.require("nova_polaris_hud_opacity")

        assertEquals(NovaSettingType.Slider, hudOpacity.type)
        assertEquals(NovaSettingValue.IntValue(64), hudOpacity.defaultValue)
        assertEquals(0, hudOpacity.min)
        assertEquals(100, hudOpacity.max)
        assertEquals(1, hudOpacity.step)
        assertEquals("%", hudOpacity.suffix)
        assertEquals(NovaSettingApplyTiming.Instant, hudOpacity.applyTiming)
    }

    @Test
    fun menuOpacityPreferenceIsIndependentAdjustableInstantSlider() {
        val definitions = NovaSettingDefinitions.load(context)
        val menuOpacity = definitions.require("nova_menu_opacity")

        assertEquals("Menu & Drawer Opacity", menuOpacity.title)
        assertEquals("category_overlays", menuOpacity.categoryKey)
        assertEquals(NovaSettingType.Slider, menuOpacity.type)
        assertEquals(NovaSettingValue.IntValue(64), menuOpacity.defaultValue)
        assertEquals(0, menuOpacity.min)
        assertEquals(100, menuOpacity.max)
        assertEquals(1, menuOpacity.step)
        assertEquals("%", menuOpacity.suffix)
        assertEquals(NovaSettingApplyTiming.Instant, menuOpacity.applyTiming)
        assertEquals(null, menuOpacity.dependencyKey)
    }

    @Test
    fun upgradedBalancedInstallMigratesLegacy720Resolution() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("nova_stream_preset", StreamPreset.BALANCED.key)
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
            .putString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS)
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 15000)
            .putString("video_format", StreamPreset.BALANCED.codec)
            .commit()

        assertTrue(PreferenceConfiguration.migrateLegacyBalancedResolutionDefault(context))
        assertEquals(
            StreamPreset.BALANCED.resolution,
            prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null)
        )
    }

    @Test
    fun upgradedBalancedInstallMigratesLegacyCombined720Resolution() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("nova_stream_preset", StreamPreset.BALANCED.key)
            .putString("list_resolution_fps", "720p60")
            .putInt("seekbar_bitrate", 15)
            .putString("video_format", StreamPreset.BALANCED.codec)
            .commit()

        assertTrue(PreferenceConfiguration.migrateLegacyBalancedResolutionDefault(context))
        assertEquals(
            StreamPreset.BALANCED.resolution,
            prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null)
        )
        assertEquals(
            PreferenceConfiguration.DEFAULT_FPS,
            prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, null)
        )
        assertFalse(prefs.contains("list_resolution_fps"))
    }

    @Test
    fun upgradedPerformanceInstallKeepsLegacy720Resolution() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("nova_stream_preset", StreamPreset.PERFORMANCE.key)
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, StreamPreset.PERFORMANCE.resolution)
            .putString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS)
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, StreamPreset.PERFORMANCE.bitrateKbps)
            .putString("video_format", StreamPreset.PERFORMANCE.codec)
            .commit()

        assertFalse(PreferenceConfiguration.migrateLegacyBalancedResolutionDefault(context))
        assertEquals(
            StreamPreset.PERFORMANCE.resolution,
            prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null)
        )
    }

    @Test
    fun definitionsClassifyApplyTimingForCommonSettings() {
        val definitions = NovaSettingDefinitions.load(context)

        assertEquals(NovaSettingApplyTiming.NextStream, definitions.require("list_resolution").applyTiming)
        assertEquals(NovaSettingApplyTiming.NextStream, definitions.require("seekbar_bitrate_kbps").applyTiming)
        assertEquals(NovaSettingApplyTiming.RestartApp, definitions.require("list_languages").applyTiming)
        assertEquals(NovaSettingApplyTiming.Instant, definitions.require("nova_theme").applyTiming)
    }

    @Test
    fun availabilityRemovesTouchUsbAndVibratorDependentSettings() {
        val packageManager = Shadows.shadowOf(context.packageManager)
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, false)
        packageManager.setSystemFeature(PackageManager.FEATURE_USB_HOST, false)

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        Shadows.shadowOf(vibrator).setHasVibrator(false)

        val filtered = NovaSettingsAvailability.filter(context, NovaSettingDefinitions.load(context))
        val keys = filtered.settings.map { it.key }.toSet()

        assertFalse(keys.contains("checkbox_show_onscreen_controls"))
        assertFalse(keys.contains("checkbox_usb_driver"))
        assertFalse(keys.contains("checkbox_vibrate_fallback"))
        assertFalse(keys.contains("seekbar_vibrate_fallback_strength"))
    }

    @Test
    fun externalDisplayTargetPreferenceOffersAutoPrimaryExternalAndLargest() {
        val definitions = NovaSettingDefinitions.load(context)
        val target = definitions.require(PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING)

        assertEquals(NovaSettingType.Select, target.type)
        assertEquals(NovaSettingValue.StringValue(AndroidStreamDisplayTarget.AUTO), target.defaultValue)
        assertEquals(PreferenceConfiguration.ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING, target.dependencyKey)
        assertEquals(NovaSettingApplyTiming.NextStream, target.applyTiming)
        assertEquals(
            listOf(
                AndroidStreamDisplayTarget.AUTO,
                AndroidStreamDisplayTarget.PRIMARY,
                AndroidStreamDisplayTarget.EXTERNAL,
                AndroidStreamDisplayTarget.LARGEST
            ),
            target.options.map { it.value }
        )
        assertEquals(context.getString(R.string.android_stream_display_target_auto), target.options[0].label)
        assertEquals(context.getString(R.string.android_stream_display_target_largest), target.options[3].label)
    }

    @Test
    fun dualScreenCategoryGroupsRoutingAndCompanionPowerPreferences() {
        val definitions = NovaSettingDefinitions.load(context)
        val enabled = definitions.require(PreferenceConfiguration.ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING)
        val streamTarget = definitions.require(PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING)
        val quickMenu = definitions.require(PreferenceConfiguration.QUICK_MENU_DISPLAY_POLICY_PREF_STRING)
        val dimTimeout = definitions.require(PreferenceConfiguration.COMPANION_SCREEN_DIM_TIMEOUT_PREF_STRING)

        assertEquals("category_dual_screen", enabled.categoryKey)
        assertEquals("category_dual_screen", streamTarget.categoryKey)

        assertEquals("category_dual_screen", quickMenu.categoryKey)
        assertEquals(NovaSettingType.Select, quickMenu.type)
        assertEquals(
            NovaSettingValue.StringValue(DualScreenQuickMenuPolicy.FOLLOW_INTERACTION),
            quickMenu.defaultValue,
        )
        assertEquals(PreferenceConfiguration.ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING, quickMenu.dependencyKey)
        assertEquals(NovaSettingApplyTiming.NextStream, quickMenu.applyTiming)
        assertEquals(
            listOf(
                DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                DualScreenQuickMenuPolicy.STREAM,
                DualScreenQuickMenuPolicy.COMPANION,
            ),
            quickMenu.options.map { it.value },
        )

        assertEquals("category_dual_screen", dimTimeout.categoryKey)
        assertEquals(NovaSettingType.Select, dimTimeout.type)
        assertEquals(NovaSettingValue.StringValue("10"), dimTimeout.defaultValue)
        assertEquals(PreferenceConfiguration.ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING, dimTimeout.dependencyKey)
        assertEquals(NovaSettingApplyTiming.NextStream, dimTimeout.applyTiming)
        assertEquals(listOf("10", "30", "60", "0"), dimTimeout.options.map { it.value })
    }

    @Test
    fun profileEditorRemovesGlobalOnlyActions() {
        val definitions = NovaSettingsAvailability.filterForProfileEditor(
            NovaSettingDefinitions.load(context)
        )
        val keys = definitions.settings.map { it.key }.toSet()

        assertFalse(keys.contains("option_reset_osc_preference"))
        assertFalse(keys.contains("import_keyboard_file"))
        assertFalse(keys.contains("export_keyboard_file"))
        assertFalse(keys.contains("import_special_button_file"))
        assertFalse(keys.contains("nova_ui_font_scale_percent"))
    }

    private fun legacyPreferenceKeys(): Set<String> {
        val keys = linkedSetOf<String>()
        val parser = context.resources.getXml(R.xml.preferences)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val key = Xml.asAttributeSet(parser).getAttributeValue(ANDROID_NS, "key")
            if (!key.isNullOrBlank() && !parser.name.endsWith("PreferenceCategory")) {
                keys += key
            }
        }
        return keys
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
