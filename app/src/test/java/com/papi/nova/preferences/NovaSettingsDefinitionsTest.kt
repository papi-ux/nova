package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
