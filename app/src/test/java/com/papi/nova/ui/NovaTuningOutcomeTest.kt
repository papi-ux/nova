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

    @Test
    fun autoAndHighFpsHaveNoHostOutcome() {
        val blob = JSONObject("{\"preference_applied\":false}")
        assertEquals(NovaTuningOutcome.Default, novaTuningOutcome(blob, "auto"))
        // High FPS is binding client-side; what the host thinks of the ask is moot.
        assertEquals(NovaTuningOutcome.Default, novaTuningOutcome(blob, "high_fps"))
        assertEquals(NovaTuningOutcome.Default, novaTuningOutcome(null, "quality"))
    }

    @Test
    fun hostsWithoutTheFieldNeverReadAsDeclines() {
        // preference_applied absent everywhere: an older host, not a decline.
        assertEquals(
            NovaTuningOutcome.Default,
            novaTuningOutcome(JSONObject("{\"display_mode\":\"1920x1080x60\"}"), "quality")
        )
    }

    @Test
    fun appliedAndDeclinedReadFromEitherLevel() {
        assertEquals(
            NovaTuningOutcome.Applied,
            novaTuningOutcome(JSONObject("{\"preference_applied\":true}"), "quality")
        )
        assertEquals(
            NovaTuningOutcome.Applied,
            novaTuningOutcome(
                JSONObject("{\"profile_state\":{\"preference_applied\":true}}"),
                "stability"
            )
        )
        assertEquals(
            NovaTuningOutcome.Declined("History Safe Profile"),
            novaTuningOutcome(
                JSONObject(
                    "{\"preference_applied\":false," +
                        "\"preference_blocked_reason\":\"history_safe_profile\"}"
                ),
                "quality"
            )
        )
        assertEquals(
            NovaTuningOutcome.Declined(""),
            novaTuningOutcome(JSONObject("{\"preference_applied\":false}"), "stability")
        )
    }
}
