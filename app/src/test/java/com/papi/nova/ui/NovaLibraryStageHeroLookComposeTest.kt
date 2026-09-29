package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Stage hero's Review & Launch rests as a tile, its label in the accent, and fills only while it
 * has focus (M6): at rest a solid accent beside the focused poster read as a second focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaLibraryStageHeroLookComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var colors: NovaComposeColors

    private fun labelColour(): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-stage-primary-action-label", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    @Test
    fun reviewAndLaunchRestsAsATileAndFillsUnderFocus() {
        val games = listOf(
            PolarisGame(id = "control", name = "Control", source = "steam"),
            PolarisGame(id = "portal", name = "Portal", source = "steam"),
        )
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            NovaLibraryStage(
                games = games,
                focusedGame = games.first(),
                restoreFocusGameId = null,
                primaryActionLabel = "Review & Launch",
                apiClient = PolarisApiClient(context, ""),
                showPosterTitles = false,
                onPrimaryAction = {},
                onGameFocused = {},
                onOpenDetail = {},
                artworkLoader = { _, _, _ -> },
                posterLoader = { _, _ -> },
            )
        }
        rule.waitForIdle()
        assertEquals("at rest its label is the accent, as a tile's", colors.accentText, labelColour())
        assertNotEquals(colors.onAccent, labelColour())

        rule.onNodeWithTag("nova-stage-primary-action").requestFocus()
        rule.waitForIdle()
        assertEquals("under focus it wears the fill's label", colors.onAccent, labelColour())
    }
}
