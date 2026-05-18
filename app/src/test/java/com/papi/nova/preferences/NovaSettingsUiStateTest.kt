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

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaSettingsUiStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val definitions = NovaSettingDefinitions.load(context)

    @Test
    fun dashboardQuickSettingsUseStreamDefaults() {
        val state = NovaSettingsUiStateFactory.build(
            definitions = definitions,
            values = emptyMap(),
            selectedCategoryKey = "category_stream_quality",
            searchQuery = ""
        )

        assertEquals(
            listOf(
                "nova_stream_preset",
                "list_resolution",
                "list_fps",
                "seekbar_bitrate_kbps",
                "video_format",
                "frame_pacing"
            ),
            state.quickSettings.map { it.key }
        )
    }

    @Test
    fun searchMatchesLabelsSummariesValuesAndAliases() {
        val values = mapOf(
            "list_resolution" to NovaSettingValue.StringValue("1920x1080"),
            "seekbar_bitrate_kbps" to NovaSettingValue.IntValue(45000)
        )

        val hdrState = NovaSettingsUiStateFactory.build(definitions, values, "category_stream_quality", "10 bit")
        val bitrateState = NovaSettingsUiStateFactory.build(definitions, values, "category_stream_quality", "45000")

        assertTrue(hdrState.visibleSettings.any { it.key == "checkbox_enable_hdr" })
        assertTrue(bitrateState.visibleSettings.any { it.key == "seekbar_bitrate_kbps" })
    }

    @Test
    fun searchReportsResultCountWhileKeepingCategoriesVisible() {
        val state = NovaSettingsUiStateFactory.build(
            definitions = definitions,
            values = emptyMap(),
            selectedCategoryKey = "category_stream_quality",
            searchQuery = "bitrate"
        )

        assertTrue(state.searchResultCount > 0)
        assertEquals(definitions.categories.map { it.key }, state.categories.map { it.key })
        assertTrue(state.visibleSettings.any { it.key == "seekbar_bitrate_kbps" })
    }

    @Test
    fun profileOverrideMetadataTracksChangedAndResettableSettings() {
        val state = NovaSettingsUiStateFactory.build(
            definitions = definitions,
            values = mapOf("list_resolution" to NovaSettingValue.StringValue("1920x1080")),
            selectedCategoryKey = "category_stream_quality",
            searchQuery = "",
            overrideKeys = setOf("list_resolution"),
            resettableKeys = setOf("list_resolution")
        )

        assertTrue(state.isOverride(definitions.require("list_resolution")))
        assertTrue(state.canReset(definitions.require("list_resolution")))
        assertFalse(state.isOverride(definitions.require("list_fps")))
    }

    @Test
    fun validatesRiskyTextSettingsBeforeSaving() {
        assertFalse(NovaSettingsValidator.isValidTextValue("edit_diy_w_h", "1080p"))
        assertFalse(NovaSettingsValidator.isValidTextValue("edit_diy_w_h", "0x1080"))
        assertTrue(NovaSettingsValidator.isValidTextValue("edit_diy_w_h", "1920x1080"))

        assertFalse(NovaSettingsValidator.isValidTextValue("custom_refresh_rate", "300"))
        assertTrue(NovaSettingsValidator.isValidTextValue("custom_refresh_rate", "59.94"))

        assertFalse(NovaSettingsValidator.isValidTextValue("edit_diy_bitrate", "0"))
        assertTrue(NovaSettingsValidator.isValidTextValue("edit_diy_bitrate", "45"))
    }
}
