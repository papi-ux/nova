package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preset the HUD's stream line names (in-game #11): the one in the host's resolved profile,
 * in the words Tuning uses, and nothing where the launch has no preset a player chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLaunchPresetLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launch(preset: String?, source: String = "deterministic_preset_v1", reasoning: String = ""): JSONObject =
        JSONObject()
            .put("source", source)
            .put("optimization_reasoning", reasoning)
            .put(
                "resolved_profile",
                JSONObject().put("policy_version", 1).apply { if (preset != null) put("preset", preset) },
            )

    private fun label(optimization: JSONObject?, trusted: Boolean = true) =
        NovaLaunchPresetLabel.resolved(context.resources, optimization, trusted)

    @Test
    fun aPresetThePlayerChoseIsNamedAsTuningNamesIt() {
        assertEquals("Quality", label(launch("quality")))
        assertEquals("High FPS", label(launch("high_fps")))
        assertEquals("Stability", label(launch("STABILITY")))
    }

    @Test
    fun autoOrNoPresetNamesNothing() {
        assertEquals("", label(launch("auto")))
        assertEquals("", label(launch(null)))
        assertEquals("", label(launch("balanced_but_unknown")))
    }

    @Test
    fun theHostsProseIsNeverRead() {
        assertEquals("", label(launch("auto", reasoning = "The Quality launch preset was resolved without Doctor history or AI settings.")))
    }

    @Test
    fun aLaunchNotResolvedOrNotTrustedNamesNothing() {
        assertEquals("", label(null))
        assertEquals("", label(launch("quality"), trusted = false))
        assertEquals("", label(launch("quality", source = "legacy_ai")))
        assertEquals("a Space's worker contract is not a preset", "", label(launch("worker", source = "worker_profile_v1")))
    }
}
