package com.papi.nova.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaTuningOutcomeTest {
    private fun outcome(optimization: JSONObject?, preference: String) =
        novaTuningOutcome(optimization, preference, testLaunchProfileText())

    @Test
    fun autoAndHighFpsHaveNoHostOutcome() {
        val blob = JSONObject("{\"preference_applied\":false}")
        assertEquals(NovaTuningOutcome.Default, outcome(blob, "auto"))
        // High FPS is binding client-side; what the host thinks of the ask is moot.
        assertEquals(NovaTuningOutcome.Default, outcome(blob, "high_fps"))
        assertEquals(NovaTuningOutcome.Default, outcome(null, "quality"))
    }

    @Test
    fun hostsWithoutTheFieldNeverReadAsDeclines() {
        // preference_applied absent everywhere: an older host, not a decline.
        assertEquals(
            NovaTuningOutcome.Default,
            outcome(JSONObject("{\"display_mode\":\"1920x1080x60\"}"), "quality")
        )
    }

    @Test
    fun appliedAndDeclinedReadFromEitherLevel() {
        assertEquals(
            NovaTuningOutcome.Applied,
            outcome(JSONObject("{\"preference_applied\":true}"), "quality")
        )
        assertEquals(
            NovaTuningOutcome.Applied,
            outcome(
                JSONObject("{\"profile_state\":{\"preference_applied\":true}}"),
                "stability"
            )
        )
        assertEquals(
            NovaTuningOutcome.Declined("History Safe Profile"),
            outcome(
                JSONObject(
                    "{\"preference_applied\":false," +
                        "\"preference_blocked_reason\":\"history_safe_profile\"}"
                ),
                "quality"
            )
        )
        assertEquals(
            NovaTuningOutcome.Declined(""),
            outcome(JSONObject("{\"preference_applied\":false}"), "stability")
        )
    }
}
