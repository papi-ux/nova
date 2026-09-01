package com.papi.nova.ui

import android.content.Context
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaFrameRateOverridesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun game(id: String, appId: Int = 0) = PolarisGame(id = id, appId = appId, name = "Game")

    // --- store: save/load/clear, canonical per-app isolation ---

    @Test
    fun savedFpsIsRestoredForTheSameGame() {
        val target = game(id = "uuid-a")

        NovaFrameRateOverrides.save(context, target, 90)

        assertEquals(90, NovaFrameRateOverrides.load(context, target))
    }

    @Test
    fun differentGamesKeepIndependentCanonicalPins() {
        val gameA = game(id = "uuid-a")
        val gameB = game(id = "uuid-b")

        NovaFrameRateOverrides.save(context, gameA, 90)
        NovaFrameRateOverrides.save(context, gameB, 120)

        assertEquals(90, NovaFrameRateOverrides.load(context, gameA))
        assertEquals(120, NovaFrameRateOverrides.load(context, gameB))
    }

    @Test
    fun gamesWithBlankIdFallBackToAppIdAndStayIndependent() {
        val gameA = game(id = "", appId = 111)
        val gameB = game(id = "", appId = 222)

        NovaFrameRateOverrides.save(context, gameA, 90)

        assertEquals(90, NovaFrameRateOverrides.load(context, gameA))
        assertNull(NovaFrameRateOverrides.load(context, gameB))
    }

    @Test
    fun clearRemovesThePinSoTheGameFollowsTheResolutionOrHostAgain() {
        val target = game(id = "uuid-a")
        NovaFrameRateOverrides.save(context, target, 90)

        NovaFrameRateOverrides.clear(context, target)

        assertNull(NovaFrameRateOverrides.load(context, target))
    }

    @Test
    fun loadWithNoSavedPinReturnsNull() {
        assertNull(NovaFrameRateOverrides.load(context, game(id = "uuid-never-pinned")))
    }

    // --- effectiveFpsPin: explicit Frame Rate row wins over Tuning = High FPS ---

    @Test
    fun explicitChosenFpsWinsOverHighFpsPreference() {
        val winner = effectiveFpsPin(chosenFps = 60, profilePreference = "high_fps", settingsFps = 120f)

        assertEquals(60, winner)
    }

    @Test
    fun highFpsPreferenceAppliesOnlyWhenNoExplicitChoiceExists() {
        val winner = effectiveFpsPin(chosenFps = null, profilePreference = "high_fps", settingsFps = 120f)

        assertEquals(120, winner)
    }

    @Test
    fun neitherAnExplicitChoiceNorHighFpsMeansNoPin() {
        val winner = effectiveFpsPin(chosenFps = null, profilePreference = "quality", settingsFps = 120f)

        assertNull(winner)
    }

    // --- end to end: the precedence winner is what the resolver actually receives ---

    @Test
    fun theExplicitWinnerNotTheHighFpsPreferenceReachesTheComposedTargetFps() {
        // The review's exact failure case: Tuning = High FPS would pin 120, but the
        // player explicitly chose 60 on the Frame Rate row. The composed envelope --
        // what the deterministic resolver and the game actually launch with -- must
        // carry 60, locked, with explicit provenance, not 120.
        val winner = effectiveFpsPin(chosenFps = 60, profilePreference = "high_fps", settingsFps = 120f)
        val composed = NovaLaunchStreamOverride.compose(
            raw = JSONObject(),
            resolution = null,
            fpsOverride = winner,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
            fallbackFps = 60,
        )!!

        val targetFps = composed.getJSONObject("resolved_profile").getJSONObject("fields").getJSONObject("target_fps")
        assertEquals(60.0, targetFps.getDouble("value"), 0.001)
        assertEquals("explicit_launch_request", targetFps.getString("source"))
        assertTrue(targetFps.getBoolean("locked"))
        assertEquals("1920x1080x60", composed.getString("display_mode"))
    }
}
