package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun stage(restore: String? = null, densityScale: Float? = null, fontScale: Float? = null,
                      entries: List<PolarisGame> = games) {
        rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(densityScale ?: density.density, fontScale ?: density.fontScale)) {
            Box(Modifier.requiredSize(833.dp, 354.dp)) {
                NovaLibraryStage(
                    games = entries,
                    focusedGame = entries.firstOrNull { it.id == restore } ?: entries.first(),
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
        }
        rule.waitForIdle()
    }

    @Test fun aFractionalPixelDensityKeepsTheApprovedIntegerContentGeometry() {
        // 354dp rounds to 929px at density 2.625, then reads back as 353.90476dp.
        // Truncating that content budget to 353 silently shrinks both selected dimensions.
        stage(densityScale = 2.625f)
        val size = rule.onNodeWithTag("nova-poster-art-alpha", useUnmergedTree = true).fetchSemanticsNode().size
        assertEquals("224dp selected cover at density 2.625", 588, size.width)
        assertEquals("336dp selected cover at density 2.625", 882, size.height)
    }

    @Test fun aTwoLineTitleAndMetadataKeepTheirSingleLineBudgetAtModeratelyLargeText() {
        val entries = games.toMutableList().apply {
            this[0] = this[0].copy(name = "A long game title on its first line\nAnd its second line is visible too")
        }
        stage(fontScale = 1.3f, entries = entries)
        val title = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-stage-title", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(title) }
        assertEquals("fixture exercises two title lines", 2, title.single().lineCount)
        val metadata = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-stage-metadata", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(metadata) }
        val density = context.resources.displayMetrics.density
        assertTrue("metadata fits its 14sp single-line budget, not an inherited 24sp body line",
            metadata.single().size.height / density <= kotlin.math.ceil(14f * 1.3f) + 1f)
        listOf("nova-stage-title", "nova-stage-metadata", "nova-stage-play-stats").forEach { tag ->
            val node = rule.onNodeWithTag(tag, true)
            val text = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
            assertFalse("$tag is not vertically clipped", text.single().didOverflowHeight)
            val bounds = node.getUnclippedBoundsInRoot()
            val identity = rule.onNodeWithTag("nova-stage-identity", true).getUnclippedBoundsInRoot()
            assertTrue("$tag stays within its identity block", bounds.top >= identity.top - .6.dp && bounds.bottom <= identity.bottom + .6.dp)
        }
    }

    @Test fun heightSizedSelectedPosterAndFullSizeNeighboursReplaceTheSmallUniformRail() {
        stage()
        val selected = rule.onNodeWithTag("nova-poster-art-alpha", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val next = rule.onNodeWithTag("nova-poster-art-bravo", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val after = rule.onNodeWithTag("nova-poster-art-charlie", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val area = rule.onNodeWithTag("nova-library-stage").getUnclippedBoundsInRoot()
        assertEquals("selected art uses the strip's 10dp content inset", 10f, (selected.left - area.left).value, .6f)
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
