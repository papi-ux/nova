package com.papi.nova.preferences

import org.junit.Assert.*
import org.junit.Test

class NovaSettingsMigrationTest {
    @Test fun freshStartsRecommendedWithoutCustomKeys() {
        val result = NovaSettingsMigration.migrate(emptyMap())
        assertEquals("recommended", result[NovaSettingsMigration.TIER])
        assertEquals(true, result[NovaSettingsMigration.AUTO])
        assertNull(NovaStreamSettings.custom(result))
        assertEquals(result, NovaSettingsMigration.migrate(result))
    }

    @Test fun everyUpgradePreservesItsEffectiveValuesAsCustom() {
        val cases = listOf(
            mapOf("nova_stream_preset" to "balanced", "list_resolution" to "1920x1080", "list_fps" to "60", "seekbar_bitrate_kbps" to 20000),
            mapOf("nova_stream_preset" to "performance", "list_resolution" to "1280x720", "list_fps" to "120", "seekbar_bitrate_kbps" to 10000),
            mapOf("nova_stream_preset" to "quality", "list_resolution" to "1920x1080", "seekbar_bitrate_kbps" to 50000, "video_format" to "forceh265"),
            mapOf("list_resolution" to "2560x1440", "list_fps" to "120", "seekbar_bitrate_kbps" to 37000,
                "edit_diy_w_h" to "2400x1080", "custom_refresh_rate" to "90")
        )
        for (input in cases) {
            val result = NovaSettingsMigration.migrate(input)
            assertEquals("custom", result[NovaSettingsMigration.TIER])
            input.forEach { (key, value) -> assertEquals(key, value, result[key]) }
            assertEquals(result, NovaSettingsMigration.migrate(result))
        }
    }

    @Test fun legacyBalancedNormalizationRunsBeforeMigrationWithoutChangingBitrate() {
        val result = NovaSettingsMigration.migrate(mapOf("list_resolution_fps" to "720p60", "seekbar_bitrate" to 15))
        assertEquals("1920x1080", result["list_resolution"])
        assertEquals("60", result["list_fps"])
        assertEquals(15000, result["seekbar_bitrate_kbps"])
        assertEquals("custom", result[NovaSettingsMigration.TIER])
        assertEquals(false, result[NovaSettingsMigration.AUTO])
        assertFalse(result.containsKey("list_resolution_fps"))
    }

    @Test fun oldResolutionAndUnsetBitrateKeepOldDefaultNotRoundedAuto() {
        val result = NovaSettingsMigration.migrate(mapOf("list_resolution_fps" to "1080p30"))
        assertEquals("1920x1080", result["list_resolution"])
        assertEquals("30", result["list_fps"])
        assertFalse(result.containsKey("seekbar_bitrate_kbps"))
        assertEquals(PreferenceConfiguration.getDefaultBitrate("1920x1080", "30"), NovaStreamSettings.custom(result)!!.bitrateKbps)
        assertEquals(true, result[NovaSettingsMigration.AUTO])
    }

    @Test fun savedSetupMarksOnlyStreamOverridesCustom() {
        val sound = mapOf<String, Any>("checkbox_play_host_audio" to true)
        assertEquals(sound, NovaSettingsMigration.savedSetup(sound))
        for (key in NovaSettingsMigration.STREAM_KEYS) {
            assertEquals("custom", NovaSettingsMigration.savedSetup(mapOf(key to "value"))[NovaSettingsMigration.TIER])
        }
        assertEquals(false, NovaSettingsMigration.savedSetup(mapOf("seekbar_bitrate_kbps" to 37000))[NovaSettingsMigration.AUTO])
    }
}
