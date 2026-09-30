package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.unit.sp
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
    private val hapticCalls = mutableListOf<HapticFeedbackType>()

    private fun stage(restore: String? = null, densityScale: Float? = null, fontScale: Float? = null,
                      entries: List<PolarisGame> = games, showPosterTitles: Boolean = false) {
        rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(densityScale ?: density.density, fontScale ?: density.fontScale),
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { hapticCalls += hapticFeedbackType }
                }) {
            Box(Modifier.requiredSize(833.dp, 354.dp)) {
                NovaLibraryStage(
                    games = entries,
                    focusedGame = entries.firstOrNull { it.id == restore } ?: entries.first(),
                    restoreFocusGameId = restore,
                    apiClient = PolarisApiClient(context, ""),
                    showPosterTitles = showPosterTitles,
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

    @Test fun aTwoLineTitleAndMetadataKeepTheirSingleLineBudgetAtModeratelyLargeText() = assertModeratelyLargeIdentity(false)

    @Test fun posterCaptionsPreserveTheModeratelyLargeIdentityBudget() = assertModeratelyLargeIdentity(true)

    @Test fun aLongNeighbourCaptionResolvesItsOwnTwoLineStageBudget() {
        val entries = games.toMutableList().apply {
            this[1] = this[1].copy(name = "A long neighbour title\nWith a second visible line")
        }
        stage(fontScale = 1.3f, entries = entries, showPosterTitles = true)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-poster-caption-bravo", true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Stage reserves two 17sp caption lines", 17.sp, layouts.single().layoutInput.style.lineHeight)
        assertEquals("caption permits both lines; native fixture measures the actual two lines", 2, layouts.single().layoutInput.maxLines)
    }

    private fun assertModeratelyLargeIdentity(showPosterTitles: Boolean) {
        val entries = games.toMutableList().apply {
            this[0] = this[0].copy(name = "A game with a longer title\nAnd a visible second line")
            this[1] = this[1].copy(name = "A long neighbour title\nWith a second visible line")
        }
        stage(fontScale = 1.3f, entries = entries, showPosterTitles = showPosterTitles)
        val title = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-stage-title", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(title) }
        if (!showPosterTitles) assertEquals("fixture exercises two title lines where they fit", 2, title.single().lineCount)
        assertTrue("a short caption-on pane keeps a readable bounded title", title.single().lineCount in 1..2)
        rule.onNodeWithTag("nova-stage-title", true).assertTextEquals(entries[0].name)
        val metadata = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("nova-stage-metadata", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(metadata) }
        // LEGACY Robolectric's fake glyph metrics report a 35px line even for this 14sp
        // style. Assert the resolved budget here; native tests measure actual ink/containment.
        assertEquals("metadata resolves its own single-line budget", 14.sp, metadata.single().layoutInput.style.lineHeight)
        listOf("nova-stage-title", "nova-stage-metadata", "nova-stage-play-stats").forEach { tag ->
            val node = rule.onNodeWithTag(tag, true)
            val text = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
            if (tag == "nova-stage-play-stats") {
                node.assertTextContains("84 h played", substring = true).assertTextContains("Last played", substring = true)
                assertFalse("full stats are not ellipsized", (0 until text.single().lineCount).any { text.single().isLineEllipsized(it) })
                assertFalse("full stats stay within the allowed lines", text.single().multiParagraph.didExceedMaxLines)
            }
        }
        if (showPosterTitles) {
            val caption = rule.onNodeWithTag("nova-poster-caption-bravo", true)
            val layouts = mutableListOf<TextLayoutResult>()
            caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("long neighbour permits both caption lines; native fixture measures them", 2, layouts.single().layoutInput.maxLines)
            assertEquals("two caption lines resolve their 34sp budget", 17.sp, layouts.single().layoutInput.style.lineHeight)
            assertTrue("ellipsized captions retain the complete semantic game name",
                rule.onNodeWithTag("nova-poster-bravo").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString().contains(entries[1].name))
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
        assertEquals("a cancelled release gives no confirm cue", 0, hapticCalls.count { it == HapticFeedbackType.Confirm })
        rule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("bravo"), opened)
        assertEquals("a fresh successful activation confirms exactly once", 1, hapticCalls.count { it == HapticFeedbackType.Confirm })
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
