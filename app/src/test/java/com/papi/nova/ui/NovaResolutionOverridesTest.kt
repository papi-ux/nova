package com.papi.nova.ui

import android.content.Context
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaResolutionOverridesTest {
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

    @Test
    fun savedChoiceIdIsRestoredForTheSameGame() {
        val target = game(id = "uuid-a")

        NovaResolutionOverrides.save(context, target, "performance-960x540")

        assertEquals("performance-960x540", NovaResolutionOverrides.load(context, target))
    }

    @Test
    fun differentGamesKeepIndependentCanonicalChoices() {
        val gameA = game(id = "uuid-a")
        val gameB = game(id = "uuid-b")

        NovaResolutionOverrides.save(context, gameA, "performance-960x540")
        NovaResolutionOverrides.save(context, gameB, "quality-1920x1080")

        assertEquals("performance-960x540", NovaResolutionOverrides.load(context, gameA))
        assertEquals("quality-1920x1080", NovaResolutionOverrides.load(context, gameB))
    }

    @Test
    fun gamesWithBlankIdFallBackToAppIdAndStayIndependent() {
        val gameA = game(id = "", appId = 111)
        val gameB = game(id = "", appId = 222)

        NovaResolutionOverrides.save(context, gameA, "performance-960x540")

        assertEquals("performance-960x540", NovaResolutionOverrides.load(context, gameA))
        assertNull(NovaResolutionOverrides.load(context, gameB))
    }

    @Test
    fun clearRemovesTheSavedChoiceSoTheGameFollowsThePlannerAgain() {
        val target = game(id = "uuid-a")
        NovaResolutionOverrides.save(context, target, "performance-960x540")

        NovaResolutionOverrides.clear(context, target)

        assertNull(NovaResolutionOverrides.load(context, target))
    }

    @Test
    fun loadWithNoSavedChoiceReturnsNull() {
        assertNull(NovaResolutionOverrides.load(context, game(id = "uuid-never-chosen")))
    }

    // --- resolveSavedResolutionChoice: the saved id resolved against a live planner ---

    private fun choice(id: String, recommended: Boolean = false) = NovaDisplayResolutionChoice(
        id = id,
        title = id,
        targetMode = "1920x1080x60",
        badge = "",
        reason = "",
        advanced = false,
        custom = false,
        safe = true,
        recommended = recommended,
    )

    @Test
    fun savedIdPresentInThePlannerResolvesToThatChoice() {
        val choices = listOf(choice("balanced", recommended = true), choice("performance"))

        val resolved = resolveSavedResolutionChoice(savedId = "performance", visibleChoices = choices)

        assertEquals("performance", resolved?.id)
    }

    @Test
    fun savedIdNoLongerInThePlannerResolvesToNoOverrideRatherThanCrashing() {
        val choices = listOf(choice("balanced", recommended = true))

        val resolved = resolveSavedResolutionChoice(savedId = "performance-removed", visibleChoices = choices)

        assertNull(resolved)
    }

    @Test
    fun blankOrNullSavedIdResolvesToNoOverride() {
        val choices = listOf(choice("balanced", recommended = true))

        assertNull(resolveSavedResolutionChoice(savedId = null, visibleChoices = choices))
        assertNull(resolveSavedResolutionChoice(savedId = "", visibleChoices = choices))
    }
}
