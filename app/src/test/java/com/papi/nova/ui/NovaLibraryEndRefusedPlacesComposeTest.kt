package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.api.PolarisSpace
import com.papi.nova.api.PolarisSpaces
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A refused End's reason, where the RP6 shows it (XR3): an 833 x 468 dp landscape at font scale
 * 1.0. In the strip the fit gave the eyebrow up first, and the reason was the eyebrow, so a session
 * another device started lost End and said nothing. The Stage hero took no line at all. The reason
 * is now the strip card's words, which the fit never leaves out, and a line under the Stage hero's
 * title. Both are driven through the real strip fit and the real Stage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaLibraryEndRefusedPlacesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val game = PolarisGame(id = "active", name = "Control Ultimate Edition", source = "steam", launcherSource = "steam")
    private val session = NovaLibraryActiveSessionUiState(24, "active", "Control Ultimate Edition", "Retroid Pocket", true, 0, false, false, 1920, 1080, 60f)
    private val spaces = PolarisSpaces(true, true, true, "living-room", listOf(PolarisSpace("living-room", "Living Room", "ready", true)))

    /** The hero the library shows after [status], built by the mapper the library uses. */
    private fun hero(status: NovaLibraryEndStatus.Failed): NovaLibraryHeroState = NovaLibraryUiStateMapper.withEndStatus(
        NovaLibraryUiStateMapper.build(listOf(game), "", NovaLibraryFilterState(), activeSession = session),
        session,
        status,
        context.getString(R.string.nova_panel_try_again),
    ).hero

    /** The landscape strip, about 815 dp wide inside the library's margins, with its Space control. */
    private fun strip(hero: NovaLibraryHeroState) {
        rule.setPanelContent {
            Box(Modifier.padding(horizontal = 9.dp)) {
                NovaLibraryLandscapeShowcaseStripContent(
                    hostLabel = "pc-papi",
                    polarisReady = true,
                    onOpenOptions = {},
                    onOpenSystemMenu = {},
                    continueSlot = { fit ->
                        NovaLibraryStripContinue(
                            hero = hero,
                            apiClient = PolarisApiClient(context, ""),
                            fit = fit,
                            onPrimaryAction = {},
                            onSecondaryAction = if (hero.secondaryAction != null) ({}) else null,
                        )
                    },
                    continueCard = hero.topBarContinue(),
                    environments = spaces,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertWholeInside(tag: String, line: String, container: String) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
        node.assertIsDisplayed()
        node.assertTextEquals(line)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        // Not cut at a last line: every line it takes is shown. (Robolectric measures a character
        // as one pixel wide, so the width it reports is not the device's; the fit's own test has
        // widths measured on the RP6.)
        val laid = layouts.first()
        assertFalse(
            "the line is whole, not cut: $line; ${laid.lineCount} lines of at most ${laid.layoutInput.maxLines}, " +
                "${laid.size} for ${laid.multiParagraph.height} tall",
            laid.didOverflowHeight,
        )
        val bounds = node.getUnclippedBoundsInRoot()
        val inside = rule.onNodeWithTag(container, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("$tag $bounds sits inside $container $inside", bounds.inside(inside))
    }

    private fun DpRect.inside(other: DpRect): Boolean =
        left >= other.left - 0.5.dp && top >= other.top - 0.5.dp && right <= other.right + 0.5.dp && bottom <= other.bottom + 0.5.dp

    @Test
    fun theStripSaysASessionAnotherDeviceStartedEvenWithTheRp6sRoom() {
        val line = context.getString(R.string.nova_library_end_started_elsewhere)
        val hero = hero(NovaLibraryEndStatus.Failed(24, line, canRetry = false))
        strip(hero)
        assertWholeInside(NOVA_LIBRARY_END_FAILED_TAG, line, "nova-library-landscape-toolbar")
        rule.onNodeWithContentDescription("End Session").assertDoesNotExist()
        rule.onNodeWithContentDescription(hero.actionLabel, substring = true).assertIsDisplayed()
    }

    @Test
    fun theStripSaysTheHostsOwnWordsBesideTryAgainToo() {
        val line = "The current session belongs to another client"
        strip(hero(NovaLibraryEndStatus.Failed(24, line, canRetry = true)))
        assertWholeInside(NOVA_LIBRARY_END_FAILED_TAG, line, "nova-library-landscape-toolbar")
        rule.onNodeWithContentDescription(context.getString(R.string.nova_panel_try_again)).assertIsDisplayed()
    }

    @Test
    fun theStageHeroSaysItUnderTheTitle() {
        val line = context.getString(R.string.nova_library_end_started_elsewhere)
        rule.setPanelContent {
            NovaLibraryStage(
                games = listOf(game),
                focusedGame = game,
                restoreFocusGameId = null,
                primaryActionLabel = "Review & Launch",
                sessionTitle = game.name,
                sessionActionLabel = "Resume Stream",
                secondaryActionLabel = null,
                apiClient = PolarisApiClient(context, ""),
                showPosterTitles = false,
                onPrimaryAction = {},
                onSessionAction = {},
                endRefusal = line,
                onGameFocused = {},
                onOpenDetail = {},
                artworkLoader = { _, _, _ -> },
                posterLoader = { _, _ -> },
            )
        }
        rule.waitForIdle()
        assertWholeInside(NOVA_STAGE_END_REFUSED_TAG, line, "nova-stage-hero")
        val title = rule.onNodeWithTag("nova-stage-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val refusal = rule.onNodeWithTag(NOVA_STAGE_END_REFUSED_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("under the title", refusal.top >= title.bottom - 0.5.dp)
        val resume = rule.onNodeWithTag("nova-stage-session-action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val stage = rule.onNodeWithTag("nova-stage-hero", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Resume still sits in the hero: $resume in $stage", resume.bottom <= stage.bottom + 0.5.dp)
    }

    @Test
    fun theSessionOnlyStageHeroSaysItUnderTheTitle() {
        val line = context.getString(R.string.nova_library_end_started_elsewhere)
        rule.setPanelContent {
            NovaLibraryStage(
                games = emptyList(),
                focusedGame = null,
                restoreFocusGameId = null,
                primaryActionLabel = "Review & Launch",
                sessionTitle = game.name,
                sessionSupportingLine = "Running on pc-papi",
                sessionActionLabel = "Resume Stream",
                secondaryActionLabel = null,
                apiClient = PolarisApiClient(context, ""),
                showPosterTitles = false,
                onPrimaryAction = {},
                onSessionAction = {},
                endRefusal = line,
                onGameFocused = {},
                onOpenDetail = {},
                artworkLoader = { _, _, _ -> },
                posterLoader = { _, _ -> },
            )
        }
        rule.waitForIdle()
        assertWholeInside(NOVA_STAGE_END_REFUSED_TAG, line, "nova-stage-session-only-hero")
    }
}
