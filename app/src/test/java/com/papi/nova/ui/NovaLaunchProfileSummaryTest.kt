package com.papi.nova.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaLaunchProfileSummaryTest {
    @Test
    fun highFpsRecoverySummaryNamesEffectiveLaunchAndRetry() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"source\":\"history_safe\"," +
                    "\"display_mode\":\"1920x1080x40\"," +
                    "\"effective_target_fps\":40," +
                    "\"target_bitrate_kbps\":6000," +
                    "\"preferred_codec\":\"hevc\"," +
                    "\"preference\":\"high_fps\"," +
                    "\"preference_applied\":false," +
                    "\"preference_blocked_reason\":\"history_safe_profile\"," +
                    "\"limiting_factor\":\"decoder\"," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"recovering\"," +
                    "\"label\":\"Recovery\"," +
                    "\"reason\":\"Holding the safer launch profile until a clean session confirms recovery.\"," +
                    "\"preference_label\":\"Prefer High FPS\"," +
                    "\"preference_applied\":false," +
                    "\"preference_blocked_reason\":\"history_safe_profile\"," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x40\",\"target_fps\":40," +
                    "\"target_bitrate_kbps\":6000,\"preferred_codec\":\"hevc\"}," +
                    "\"last_result\":{\"grade\":\"B\",\"delivered_fps\":58.5,\"target_fps\":60," +
                    "\"primary_issue\":\"decoder_path\",\"updated_at\":1780000000}," +
                    "\"actions\":{\"can_retry_high_fps\":true,\"can_reset\":true}" +
                    "}" +
                    "}"
            ),
            nowSeconds = 1780000060L
        )

        requireNotNull(summary)
        assertEquals("Launch Recovery 40 FPS", summary.primaryLaunchLabel)
        assertEquals("Requested: Prefer High FPS / 120 FPS", summary.requestedLine)
        assertEquals("Selected: Recovery / 40 FPS", summary.selectedLine)
        assertEquals("Limited by: Decoder path", summary.limitingLine)
        assertEquals("Recovery active from last session · 1 min ago", summary.freshnessLine)
        assertEquals("Try 120 FPS once", summary.retryHighFpsLabel)
        assertTrue(summary.showRetryHighFps)
        assertTrue(summary.historyLines.contains("Last: grade B at 58.5/60 FPS"))
        assertTrue(summary.historyLines.contains("Issue: Decoder path"))
        assertTrue(summary.historyLines.contains("Next: one clean launch can release recovery, or reset this game profile."))
    }

    @Test
    fun highFpsTrialSummaryNamesOneLaunchTrial() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"source\":\"device_db\"," +
                    "\"trial_profile\":true," +
                    "\"trial_kind\":\"high_fps\"," +
                    "\"display_mode\":\"1920x1080x120\"," +
                    "\"effective_target_fps\":120," +
                    "\"target_bitrate_kbps\":30000," +
                    "\"preferred_codec\":\"hevc\"," +
                    "\"preference\":\"high_fps\"," +
                    "\"preference_applied\":true," +
                    "\"preference_blocked_reason\":\"none\"," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"trial\"," +
                    "\"label\":\"High FPS Trial\"," +
                    "\"reason\":\"Trying High FPS once; learned recovery remains active unless this launch grades cleanly.\"," +
                    "\"preference_label\":\"Prefer High FPS\"," +
                    "\"preference_applied\":true," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120," +
                    "\"target_bitrate_kbps\":30000,\"preferred_codec\":\"hevc\"}," +
                    "\"actions\":{\"can_retry_high_fps\":false,\"can_reset\":true}" +
                    "}" +
                    "}"
            )
        )

        requireNotNull(summary)
        assertEquals("Launch High FPS Trial 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Requested: Prefer High FPS / 120 FPS", summary.requestedLine)
        assertEquals("Selected: High FPS Trial / 120 FPS", summary.selectedLine)
        assertFalse(summary.showRetryHighFps)
    }

    @Test
    fun highFpsSatisfiedSummaryDoesNotOfferRetry() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"display_mode\":\"1920x1080x120\"," +
                    "\"effective_target_fps\":120," +
                    "\"preference\":\"high_fps\"," +
                    "\"preference_applied\":false," +
                    "\"preference_blocked_reason\":\"host_render_limited\"," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"recovering\"," +
                    "\"label\":\"Recovery\"," +
                    "\"reason\":\"Holding quality until the host render path reaches the stream FPS target.\"," +
                    "\"preference_label\":\"Prefer High FPS\"," +
                    "\"preference_applied\":false," +
                    "\"preference_blocked_reason\":\"host_render_limited\"," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"last_result\":{\"grade\":\"A\",\"delivered_fps\":38.9,\"target_fps\":40," +
                    "\"primary_issue\":\"host_render\",\"updated_at\":1780000000}," +
                    "\"actions\":{\"can_retry_high_fps\":true}" +
                    "}" +
                    "}"
            ),
            nowSeconds = 1780000060L
        )

        requireNotNull(summary)
        assertEquals("Launch High FPS 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Selected: High FPS / 120 FPS", summary.selectedLine)
        assertFalse(summary.showRetryHighFps)
    }

    @Test
    fun steadyLastResultDoesNotRenderAsLimited() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"display_mode\":\"1920x1080x120\"," +
                    "\"effective_target_fps\":120," +
                    "\"preference\":\"auto\"," +
                    "\"preference_applied\":true," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"stable\"," +
                    "\"label\":\"Quality\"," +
                    "\"reason\":\"Auto Quality is holding the current launch profile.\"," +
                    "\"preference_label\":\"Auto\"," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"last_result\":{\"grade\":\"A\",\"delivered_fps\":120.1,\"target_fps\":120," +
                    "\"primary_issue\":\"steady\",\"updated_at\":1780000000}" +
                    "}" +
                    "}"
            ),
            nowSeconds = 1780000060L
        )

        requireNotNull(summary)
        assertEquals("", summary.limitingLine)
        assertFalse(summary.historyLines.contains("Issue: Steady"))
    }
}
