package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * While Play Setup rechecks the plan with the host, the host's plan is gone and a frame rate chosen
 * here is composed onto nothing. That is no plan at all, and it read as one: What Will Happen said
 * "Profile / 120 FPS" and Launch dropped its preset to "Launch 120 FPS" (in-game smoke #18). A
 * launch the host has not planned has no summary; the page keeps the last plan or says it is
 * checking.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaLaunchProfileNoHalfLabelTest {
    @Test
    fun aFrameRateComposedOntoNoHostPlanIsNotAPlan() {
        val composed = NovaLaunchStreamOverride.compose(
            raw = null,
            resolution = null,
            fpsOverride = 120,
            fallbackWidth = 3840,
            fallbackHeight = 2160,
            fallbackFps = 60,
        )
        assertNull(buildNovaLaunchProfileSummary(composed, clientAskedFps = 120.0))
    }

    @Test
    fun whileTheHostRechecksThePlanTheLastOneStandsInAndLaunchKeepsItsPreset() {
        val settled = buildNovaLaunchProfileSummary(
            org.json.JSONObject(
                """{"source":"deterministic_preset_v1","resolved_profile":{"policy_version":1,"preset":"quality","preset_label":"Quality","fields":{
                    "display_mode":{"value":"3840x2160x120"},"display_width":{"value":3840},"display_height":{"value":2160},
                    "target_fps":{"value":120},"target_bitrate_kbps":{"value":300000}}}}""",
            ),
        )!!
        val checking = NovaGameDetailOptimizationState(preflightInFlight = true, lastPlan = settled)
            .withLastPlanWhileChecking()

        assertEquals(settled, checking.profileSummary)
        assertEquals("Launch Quality · 120 FPS", checking.profileSummary?.primaryLaunchLabel)
        assertTrue("it reads dimmed until the host answers", checking.showsLastPlan)

        val answered = NovaGameDetailOptimizationState(profileSummary = settled, rawOptimization = org.json.JSONObject(), lastPlan = settled)
        assertEquals(answered, answered.withLastPlanWhileChecking())
        assertFalse("an answered plan is not the kept one", answered.showsLastPlan)
        assertNull("with no plan before it there is nothing to keep", NovaGameDetailOptimizationState(preflightInFlight = true).withLastPlanWhileChecking().profileSummary)
    }
}
