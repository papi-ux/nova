package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The approved Stage geometry and navigation, measured on the real composable. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp-land")
class NovaLibraryStageRebuildComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val games = listOf(
        PolarisGame("alpha", name = "Alpha", source = "steam", category = "action", lastLaunched = System.currentTimeMillis() / 1000 - 3600,
            playTime = PolarisGame.PlayTime(seconds = 84 * 3600)),
        PolarisGame("bravo", name = "Bravo", source = "steam"),
        PolarisGame("charlie", name = "Charlie", source = "steam"),
    )
    private val focused = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun stage(restore: String? = null) {
        rule.setPanelContent {
            Box(Modifier.requiredSize(833.dp, 354.dp)) {
                NovaLibraryStage(
                    games = games,
                    focusedGame = games.firstOrNull { it.id == restore } ?: games.first(),
                    restoreFocusGameId = restore,
                    apiClient = PolarisApiClient(context, ""),
                    showPosterTitles = false,
                    onGameFocused = { focused += it.id },
                    onOpenDetail = { opened += it.id },
                    artworkLoader = { _, _, _ -> },
                    posterLoader = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun heightSizedSelectedPosterAndFullSizeNeighboursReplaceTheSmallUniformRail() {
        stage()
        val selected = rule.onNodeWithTag("nova-poster-art-alpha", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val next = rule.onNodeWithTag("nova-poster-art-bravo", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val after = rule.onNodeWithTag("nova-poster-art-charlie", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(224f, (selected.right - selected.left).value, 0.6f)
        assertEquals(336f, (selected.bottom - selected.top).value, 0.6f)
        assertEquals(120f, (next.right - next.left).value, 0.6f)
        assertEquals(180f, (next.bottom - next.top).value, 0.6f)
        assertEquals(12f, (after.left - next.right).value, 0.6f)
        rule.onNodeWithText("Review & Launch").assertDoesNotExist()
        rule.onNodeWithTag("nova-stage-title", useUnmergedTree = true).assertTextEquals("Alpha")
        rule.onNodeWithTag("nova-stage-play-stats", useUnmergedTree = true).assertTextContains("84 h played", substring = true)
        rule.onNodeWithTag("nova-stage-play-stats", useUnmergedTree = true).assertTextContains("Last played", substring = true)
    }

    @Test fun controllerWrapKeepsTheSelectedPosterInTheSamePlaceAndAOpensOnlyItsDetail() {
        stage("charlie")
        val before = rule.onNodeWithTag("nova-poster-art-charlie", useUnmergedTree = true).getUnclippedBoundsInRoot()
        rule.onNodeWithTag("nova-poster-charlie").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        val after = rule.onNodeWithTag("nova-poster-art-alpha", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(before, after)
        rule.onNodeWithTag("nova-stage-position", useUnmergedTree = true).assertTextEquals("1 of 3 · Library Order")
        rule.onNodeWithTag("nova-poster-alpha").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("alpha"), opened)
        rule.onNodeWithTag("nova-poster-alpha").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-position", useUnmergedTree = true).assertTextEquals("3 of 3 · Library Order")
        assertEquals("charlie", focused.last())
    }

    @Test fun changingTheSelectedGameWhileAHeldCancelsThatPress() {
        stage()
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput {
            keyDown(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { keyUp(Key.Enter) }
        assertEquals("a release belongs to the game on which the press began", emptyList<String>(), opened)
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("bravo"), opened)
    }

    @Test fun touchingANeighbourSelectsItBeforeOpeningItsDetails() {
        stage()
        rule.onNodeWithTag("nova-poster-bravo").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        assertEquals("bravo", focused.last())
        val b = rule.onNodeWithTag("nova-poster-art-bravo", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(224f, (b.right - b.left).value, 0.6f)
        rule.onNodeWithTag("nova-poster-bravo").performClick()
        assertEquals(listOf("bravo"), opened)
    }

    @Test fun changingAwayAndBackStillCancelsTheOriginalHeldPress() {
        stage()
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput {
            keyDown(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { keyUp(Key.Enter) }
        assertEquals(emptyList<String>(), opened)
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("alpha"), opened)
    }

    @Test fun stageKeepsTheLiveSessionInTheSharedStripWithoutAnIdleContinueCard() {
        assertEquals(true, NovaLibraryUiStateMapper.showStandaloneHomeHero(NovaLibraryLayoutMode.STAGE, hasActiveSession = true))
        assertEquals(false, NovaLibraryUiStateMapper.showStandaloneHomeHero(NovaLibraryLayoutMode.STAGE, hasActiveSession = false))
        val presentation = NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        assertEquals(1f, presentation.focusedScale)
        assertEquals(1f, presentation.unfocusedAlpha)
        assertEquals(0, presentation.focusGutterDp)
        assertEquals(0, NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp())
    }
}
