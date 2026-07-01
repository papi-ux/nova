package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.util.Xml
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
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
                "category_input",
                "category_overlays",
                "category_nova",
                "category_advanced"
            ),
            categoryKeys
        )
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
    fun hudOpacityPreferenceIsAdjustableInstantSlider() {
        val definitions = NovaSettingDefinitions.load(context)
        val hudOpacity = definitions.require("nova_polaris_hud_opacity")

        assertEquals(NovaSettingType.Slider, hudOpacity.type)
        assertEquals(NovaSettingValue.IntValue(90), hudOpacity.defaultValue)
        assertEquals(25, hudOpacity.min)
        assertEquals(100, hudOpacity.max)
        assertEquals(1, hudOpacity.step)
        assertEquals("%", hudOpacity.suffix)
        assertEquals(NovaSettingApplyTiming.Instant, hudOpacity.applyTiming)
    }

    @Test
    fun upgradedBalancedInstallMigratesLegacy720Resolution() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("nova_stream_preset", StreamPreset.BALANCED.key)
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
            .putString(PreferenceConfiguration.FPS_PREF_STRING, StreamPreset.BALANCED.fps)
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
            StreamPreset.BALANCED.fps,
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
            .putString(PreferenceConfiguration.FPS_PREF_STRING, StreamPreset.PERFORMANCE.fps)
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
    fun profileEditorRemovesGlobalOnlyActions() {
        val definitions = NovaSettingsAvailability.filterForProfileEditor(
            NovaSettingDefinitions.load(context)
        )
        val keys = definitions.settings.map { it.key }.toSet()

        assertFalse(keys.contains("option_reset_osc_preference"))
        assertFalse(keys.contains("import_keyboard_file"))
        assertFalse(keys.contains("export_keyboard_file"))
        assertFalse(keys.contains("import_special_button_file"))
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
