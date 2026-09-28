package com.papi.nova.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one rule for how a Select is presented (spec section 7, group 4), over the real
 * definitions in res/xml/preferences.xml: of the 19 list preferences, 12 change in place and 7
 * open a page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSettingsSelectPresentationTest {
    private val definitions = NovaSettingDefinitions.load(ApplicationProvider.getApplicationContext<Context>())
    private val selects = definitions.settings.filter {
        it.type == NovaSettingType.Select && it.key != "list_languages"
    }

    @Test
    fun theNineteenListsSplitTwelveInPlaceAndSevenPages() {
        assertEquals(19, selects.size)
        val inPlace = selects.filter { it.selectPresentation == NovaSelectPresentation.InPlace }.map { it.key }.toSet()
        val pages = selects.filter { it.selectPresentation == NovaSelectPresentation.Page }.map { it.key }.toSet()

        assertEquals(
            setOf(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.FPS_PREF_STRING,
                "dual_screen_companion_dim_timeout_seconds",
                "nova_disconnect_resume_timeout_seconds",
                "nova_polaris_max_retries",
                "list_video_scale_mode",
                "list_audio_config",
                "analog_scrolling",
                "nova_audio_haptics",
                "list_onscreen_controls_layout_preset",
                "dual_screen_quick_menu_display_policy",
                "nova_polaris_hud_mode",
            ),
            inPlace,
        )
        assertEquals(
            setOf(
                "nova_stream_preset",
                PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING,
                "nova_theme",
                "video_format",
                "frame_pacing",
                "mouse_mode_list",
                "keyboard_axi_list",
            ),
            pages,
        )
    }

    @Test
    fun theFiveOrderedScalesStopAtTheirEndsAndTheRestWrap() {
        val ordered = selects.filter { it.isOrderedScale }.map { it.key }.toSet()
        assertEquals(
            setOf(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.FPS_PREF_STRING,
                "dual_screen_companion_dim_timeout_seconds",
                "nova_disconnect_resume_timeout_seconds",
                "nova_polaris_max_retries",
            ),
            ordered,
        )
    }

    @Test
    fun theLanguageListIsLongEnoughToBeAPage() {
        val languages = definitions.require("list_languages")
        assertTrue(languages.options.size > NovaInPlaceMaxOrderedOptions)
        assertEquals(NovaSelectPresentation.Page, languages.selectPresentation)
    }

    @Test
    fun theRuleReadsRiskAndSizeNotJustTheNamedKeys() {
        fun select(count: Int, key: String = "k", risk: NovaSettingRisk = NovaSettingRisk.Normal) = NovaSettingDefinition(
            key = key, title = key, summary = "", categoryKey = "c", type = NovaSettingType.Select,
            options = (1..count).map { NovaSettingOption("$it", "$it") }, risk = risk,
        )

        assertEquals(NovaSelectPresentation.InPlace, select(4).selectPresentation)
        assertEquals("five unordered options open a page", NovaSelectPresentation.Page, select(5).selectPresentation)
        assertEquals("a risky list opens a page", NovaSelectPresentation.Page, select(2, risk = NovaSettingRisk.Confirm).selectPresentation)
        assertEquals(
            "an ordered scale of twelve stays in place",
            NovaSelectPresentation.InPlace,
            select(12, key = PreferenceConfiguration.FPS_PREF_STRING).selectPresentation,
        )
        assertEquals(
            "thirteen steps open a page even when ordered",
            NovaSelectPresentation.Page,
            select(13, key = PreferenceConfiguration.FPS_PREF_STRING).selectPresentation,
        )
        assertFalse(select(3).isOrderedScale)
    }
}
