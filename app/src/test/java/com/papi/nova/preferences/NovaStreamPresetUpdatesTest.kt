package com.papi.nova.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NovaStreamPresetUpdatesTest {
    @Test
    fun presetsWriteResolutionBitrateAndCodecOnly() {
        for (preset in StreamPreset.PRESETS) {
            assertEquals(
                setOf(
                    PreferenceConfiguration.RESOLUTION_PREF_STRING,
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    "video_format"
                ),
                novaPresetSettingUpdates(preset).keys
            )
        }
    }

    @Test
    fun presetsNeverTouchTheFpsPreference() {
        // The user's frame-rate choice is orthogonal to the quality preset and
        // must survive a preset switch; presets used to silently reset it to 60.
        for (preset in StreamPreset.PRESETS) {
            assertFalse(
                "preset ${preset.key} must not write ${PreferenceConfiguration.FPS_PREF_STRING}",
                PreferenceConfiguration.FPS_PREF_STRING in novaPresetSettingUpdates(preset)
            )
        }
    }

    @Test
    fun presetValuesStayPinned() {
        assertEquals("1280x720", StreamPreset.PERFORMANCE.resolution)
        assertEquals(10000, StreamPreset.PERFORMANCE.bitrateKbps)
        assertEquals("1920x1080", StreamPreset.BALANCED.resolution)
        assertEquals(20000, StreamPreset.BALANCED.bitrateKbps)
        assertEquals("1920x1080", StreamPreset.QUALITY.resolution)
        assertEquals(50000, StreamPreset.QUALITY.bitrateKbps)
        assertEquals("forceh265", StreamPreset.QUALITY.codec)
    }
}
