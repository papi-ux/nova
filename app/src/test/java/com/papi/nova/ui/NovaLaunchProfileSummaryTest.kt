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
        assertEquals("Launch Recovery profile 40 FPS", summary.primaryLaunchLabel)
        assertEquals("Requested: High FPS stream / 120 FPS", summary.requestedLine)
        assertEquals("Selected: Recovery profile / 40 FPS", summary.selectedLine)
        assertEquals("Limited by: Decoder path", summary.limitingLine)
        assertEquals(
            "Last stream: 58.5/60 FPS. The client decoder missed frames, which can cause stutter or uneven motion.",
            summary.noticeDetail
        )
        assertEquals(
            "Next launch: 40 FPS Recovery instead of your requested 120 FPS because the learned recovery profile is active. Try 120 FPS once remains available below.",
            summary.noticeRecommendation
        )
        assertEquals("Recovery active from last session · 1 min ago", summary.freshnessLine)
        assertEquals("Try 120 FPS once", summary.retryHighFpsLabel)
        assertTrue(summary.showRetryHighFps)
        assertTrue(summary.historyLines.contains("Last: grade B at 58.5/60 FPS"))
        assertTrue(summary.historyLines.contains("Issue: Decoder path"))
        assertTrue(summary.historyLines.contains("Next: one clean launch can release recovery, or reset this game profile."))
    }

    @Test
    fun hostRenderLimitNoticeExplainsEvidenceImpactAndRecoveryTarget() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"display_mode\":\"1920x1080x30\"," +
                    "\"effective_target_fps\":30," +
                    "\"preference\":\"high_fps\"," +
                    "\"preference_applied\":false," +
                    "\"limiting_factor\":\"host_render_limited\"," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x60\",\"target_fps\":60}," +
                    "\"profile_state\":{" +
                    "\"state\":\"recovering\"," +
                    "\"label\":\"Recovery\"," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x30\",\"target_fps\":30}," +
                    "\"last_result\":{\"grade\":\"C\",\"delivered_fps\":54,\"target_fps\":60," +
                    "\"primary_issue\":\"host_render_limited\",\"updated_at\":1780000000}," +
                    "\"actions\":{\"can_retry_high_fps\":true}" +
                    "}" +
                    "}"
            ),
            nowSeconds = 1780000060L
        )

        requireNotNull(summary)
        assertEquals("Limited by: Host render", summary.limitingLine)
        assertEquals(
            "Last stream: 54/60 FPS. The host missed the stream target, which can cause repeated frames or uneven motion.",
            summary.noticeDetail
        )
        assertEquals(
            "Next launch: 30 FPS Recovery instead of your requested 60 FPS because the learned recovery profile is active. Try 60 FPS once remains available below.",
            summary.noticeRecommendation
        )
    }

    @Test
    fun unknownIssueReportsSourceTruthWithoutInventingAnFpsMiss() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"effective_target_fps\":60," +
                    "\"limiting_factor\":\"thermal_throttle\"," +
                    "\"preference_requested_profile\":{\"target_fps\":60}," +
                    "\"profile_state\":{" +
                    "\"state\":\"stable\",\"label\":\"Quality\"," +
                    "\"current_profile\":{\"target_fps\":60}," +
                    "\"last_result\":{\"delivered_fps\":60,\"target_fps\":60," +
                    "\"primary_issue\":\"thermal_throttle\"}" +
                    "}" +
                    "}"
            )
        )

        requireNotNull(summary)
        assertEquals(
            "Last stream: 60/60 FPS. Polaris reported Thermal throttle for the last session.",
            summary.noticeDetail
        )
        assertFalse(summary.noticeDetail.contains("did not meet"))
        assertEquals("", summary.noticeRecommendation)
    }

    @Test
    fun lowerNonRecoveryProfileUsesNeutralSelectionCopy() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"effective_target_fps\":60," +
                    "\"preference\":\"high_fps\",\"preference_applied\":false," +
                    "\"limiting_factor\":\"network\"," +
                    "\"preference_requested_profile\":{\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"stable\",\"label\":\"Quality\"," +
                    "\"current_profile\":{\"target_fps\":60}" +
                    "}" +
                    "}"
            )
        )

        requireNotNull(summary)
        assertEquals(
            "Next launch: Nova selected 60 FPS instead of your requested 120 FPS. Try 120 FPS once remains available below.",
            summary.noticeRecommendation
        )
        assertFalse(summary.noticeRecommendation.contains("Recovery"))
        assertFalse(summary.noticeRecommendation.contains("steadier"))
    }

    @Test
    fun equalTargetRecoveryExplainsReleaseWithoutPromisingPacing() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"effective_target_fps\":60," +
                    "\"preference_requested_profile\":{\"target_fps\":60}," +
                    "\"profile_state\":{" +
                    "\"state\":\"recovering\",\"label\":\"Recovery\"," +
                    "\"current_profile\":{\"target_fps\":60}" +
                    "}" +
                    "}"
            )
        )

        requireNotNull(summary)
        assertEquals(
            "Next launch: 60 FPS Recovery remains active. One clean launch can release it, or reset this game profile below.",
            summary.noticeRecommendation
        )
        assertFalse(summary.noticeRecommendation.contains("steadier"))
    }

    @Test
    fun knownLimitTypesExplainPlayerVisibleImpactWithoutFpsEvidence() {
        val expected = mapOf(
            "network" to "The network path was unstable, which can cause hitching or dropped frames.",
            "encoder" to "The host encoder missed frames, which can cause uneven frame delivery.",
            "frame_pacing" to "Frames arrived unevenly, which can look like judder even when average FPS is high."
        )

        expected.forEach { (issue, detail) ->
            val summary = buildNovaLaunchProfileSummary(
                JSONObject("{\"limiting_factor\":\"$issue\",\"profile_state\":{\"state\":\"stable\"}}")
            )

            requireNotNull(summary)
            assertEquals(issue, detail, summary.noticeDetail)
        }
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
        assertEquals("Try High FPS stream 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Requested: High FPS stream / 120 FPS", summary.requestedLine)
        assertEquals("Selected: High FPS trial / 120 FPS", summary.selectedLine)
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
        assertEquals("Launch High FPS stream 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Selected: High FPS stream / 120 FPS", summary.selectedLine)
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

    @Test
    fun highFpsRecommendationNamesStreamTargetInsteadOfGameRenderPromise() {
        val summary = buildNovaLaunchProfileSummary(
            JSONObject(
                "{" +
                    "\"display_mode\":\"1920x1080x120\"," +
                    "\"effective_target_fps\":120," +
                    "\"preference\":\"high_fps\"," +
                    "\"preference_applied\":true," +
                    "\"preference_requested_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}," +
                    "\"profile_state\":{" +
                    "\"state\":\"stable\"," +
                    "\"reason\":\"Nova recommends High FPS for this game.\"," +
                    "\"preference_label\":\"Prefer High FPS\"," +
                    "\"current_profile\":{\"display_mode\":\"1920x1080x120\",\"target_fps\":120}" +
                    "}" +
                    "}"
            )
        )

        requireNotNull(summary)
        assertEquals("Launch High FPS stream 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Requested: High FPS stream / 120 FPS", summary.requestedLine)
        assertEquals("Selected: High FPS stream / 120 FPS", summary.selectedLine)
        assertEquals("Reason: Nova recommends High FPS for this game.", summary.reasonLine)
    }

}
