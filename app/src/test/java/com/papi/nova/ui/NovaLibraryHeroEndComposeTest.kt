package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End Session on the library's home hero and on the landscape strip's card splits in its own slot
 * (R3). One A arms it with Stay focused while the actions beside it step aside, B puts the button
 * back and ends nothing, a press inside the guard ends nothing, and A, Right, A after the guard
 * ends the session once, with no second confirm after it.
 */
@RunWith(RobolectricTestRunner::class)
// A phone in portrait, where the home hero stands above the grid.
@Config(sdk = [33], qualifiers = "w412dp-h915dp")
class NovaLibraryHeroEndComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var ends = 0
    private var resumes = 0

    private val hero = NovaLibraryHeroState(
        game = null,
        title = "Control Ultimate Edition",
        subtitle = "Running on pc-papi",
        caption = "Resume this stream, or end it if the host game is stale.",
        eyebrow = "Resume your stream",
        actionLabel = "Resume Stream",
        badges = listOf("HDR", "4K"),
        reason = NovaLibraryHeroReason.ACTIVE_SESSION,
        primaryAction = NovaLibraryHeroPrimaryAction.RESUME,
        supportingLine = "",
        artworkFallbackTitle = "Control",
        artworkFallbackSubtitle = "",
        secondaryActionLabel = "End Session",
        secondaryAction = NovaLibraryHeroSecondaryAction.END_SESSION,
    )

    private fun homeHero(): NovaTestKeys = rule.setPanelContent {
        Box(Modifier.fillMaxSize()) {
            NovaLibraryHeroCard(
                hero = hero,
                compact = false,
                apiClient = PolarisApiClient(context, ""),
                onPrimaryAction = { resumes++ },
                onSecondaryAction = { ends++ },
                onOpenDetail = {},
                onGameFocused = {},
            )
        }
    }

    private fun strip(): NovaTestKeys = rule.setPanelContent {
        Row(Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
            NovaLibraryStripContinue(
                hero = hero,
                apiClient = PolarisApiClient(context, ""),
                fit = NovaTopBarFit(continueTitleLines = 2),
                onPrimaryAction = { resumes++ },
                onSecondaryAction = { ends++ },
            )
        }
    }

    /** The strip over a hero the test changes, as the library does when the host answers an End. */
    private var liveHero by mutableStateOf(hero)

    private fun liveStrip(): NovaTestKeys = rule.setPanelContent {
        Row(Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
            NovaLibraryStripContinue(
                hero = liveHero,
                apiClient = PolarisApiClient(context, ""),
                fit = NovaTopBarFit(continueTitleLines = 2),
                onPrimaryAction = { resumes++ },
                onSecondaryAction = { ends++ },
            )
        }
    }

    private fun armFrom(tagOrLabel: () -> Unit, keys: NovaTestKeys) {
        tagOrLabel()
        rule.mainClock.autoAdvance = false
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        rule.onNodeWithContentDescription(stay()).assertIsFocused()
    }

    private fun stay() = context.getString(R.string.nova_panel_stay)

    @Test
    fun theHomeHerosEndArmsInPlaceAndBPutsItBackEndingNothing() {
        val keys = homeHero()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        rule.onNodeWithText("Resume Stream").assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.nova_panel_end_session_message)).assertExists()

        keys.back()
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)

        assertEquals("B cancels; nothing ends", 0, ends)
        rule.onNodeWithContentDescription(stay()).assertDoesNotExist()
        rule.onNodeWithText("Resume Stream").assertExists()
        rule.onNodeWithContentDescription("End Session").assertIsFocused()
    }

    @Test
    fun theHomeHerosEndIgnoresTheGuardThenEndsOnceOnARight() {
        val keys = homeHero()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)

        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        assertEquals("a press inside the guard after arming ends nothing", 0, ends)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)

        assertEquals(1, ends)
        assertEquals(0, resumes)
    }

    @Test
    fun aOnStayDisarmsTheHomeHero() {
        val keys = homeHero()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)

        keys.press(NovaTestKeys.A)
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)

        assertEquals(0, ends)
        rule.onNodeWithText("Resume Stream").assertExists()
    }

    @Test
    fun onAPhoneTheHerosActionsGoUnderItsWordsAndOnATabletBesideThem() {
        val padding = 16.dp
        val gap = 16.dp
        val artwork = novaLibraryHeroArtworkWidth(compact = false)
        assertTrue("a phone's 380dp hero leaves the words too narrow beside the actions", novaLibraryHeroActionsUnder(380.dp, padding, gap, artwork))
        assertFalse("a tablet in portrait keeps them beside", novaLibraryHeroActionsUnder(776.dp, padding, gap, artwork))
        homeHero()
        // The card merges its words into one node for TalkBack, so the title is found unmerged.
        val words = rule.onNodeWithText("Control Ultimate Edition", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val resume = rule.onNodeWithText("Resume Stream", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("on the phone Resume sits under the title, not beside it: $words $resume", resume.top >= words.bottom)
    }

    @Test
    fun theStripsEndArmsInPlaceAndItsNeighboursStepAside() {
        val keys = strip()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        // The title stays, so the player sees which game is ending; only Resume steps aside.
        rule.onNodeWithText("Control Ultimate Edition").assertExists()
        rule.onNodeWithContentDescription("Resume Stream").assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.nova_library_end_strip_consequence)).assertExists()

        keys.back()
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)

        assertEquals(0, ends)
        rule.onNodeWithText("Control Ultimate Edition").assertExists()
        rule.onNodeWithContentDescription(stay()).assertDoesNotExist()
    }

    @Test
    fun mashingAOnTheStripsEndNeverEndsIt() {
        val keys = strip()
        rule.onNodeWithContentDescription("End Session").requestFocus()
        rule.mainClock.autoAdvance = false
        repeat(3) {
            keys.press(NovaTestKeys.A)
            rule.advance(MASH_GAP_MS)
        }
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        assertEquals("three mashed presses arm, stay and arm again; nothing ends", 0, ends)
    }

    @Test
    fun theStripsEndEndsOnceAfterTheGuard() {
        val keys = strip()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)

        assertEquals(1, ends)
        assertEquals(0, resumes)
    }

    @Test
    fun oncePressedTheStripSaysEndingInsteadOfOfferingItAgain() {
        val keys = strip()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        assertEquals(1, ends)
        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()
        rule.onNodeWithContentDescription("Resume Stream").assertDoesNotExist()
        rule.onNodeWithContentDescription("End Session").assertDoesNotExist()
    }

    @Test
    fun aRefusedEndPutsResumeBackWithTryAgainAndSaysSo() {
        val keys = liveStrip()
        armFrom({ rule.onNodeWithContentDescription("End Session").requestFocus() }, keys)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        assertEquals(1, ends)
        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()

        // The host refuses: the library hands the strip a failed status for this session.
        liveHero = refused(liveHero)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertDoesNotExist()
        rule.onNodeWithTag(NOVA_LIBRARY_END_FAILED_TAG).assertExists()
        rule.onNodeWithContentDescription("Resume Stream").assertExists()
        // Focus goes back to End's slot, now Try Again, so the next A retries from a visible ring.
        rule.onNodeWithContentDescription(tryAgain()).assertIsFocused()
    }

    @Test
    fun tryAgainAfterARefusalEndsAgainThroughItsSplit() {
        liveHero = refused(hero)
        val keys = liveStrip()
        armFrom({ rule.onNodeWithContentDescription(tryAgain()).requestFocus() }, keys)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)

        assertEquals("Try Again ends once, after its own split", 1, ends)
        liveHero = liveHero.copy(endStatus = NovaLibraryEndStatus.Ending(GAME_ID), eyebrow = hero.eyebrow)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()
        rule.onNodeWithTag(NOVA_LIBRARY_END_FAILED_TAG).assertDoesNotExist()
    }

    @Test
    fun anEndAskedForFromTheGamePageShowsEndingOnTheStrip() {
        liveHero = hero.copy(endStatus = NovaLibraryEndStatus.Ending(GAME_ID))
        liveStrip()
        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()
        rule.onNodeWithContentDescription("End Session").assertDoesNotExist()
    }

    private fun tryAgain() = context.getString(R.string.nova_panel_try_again)

    private fun refused(from: NovaLibraryHeroState): NovaLibraryHeroState = from.copy(
        endStatus = NovaLibraryEndStatus.Failed(GAME_ID, context.getString(R.string.nova_library_end_failed)),
        eyebrow = context.getString(R.string.nova_library_end_failed),
        secondaryActionLabel = tryAgain(),
    )

    @Test
    fun aTapArmsTheStripsEndToo() {
        strip()
        rule.onNodeWithContentDescription("End Session").performClick()
        rule.onNodeWithContentDescription(stay()).assertExists()
        assertEquals(0, ends)
    }

    private companion object {
        const val GAME_ID = 7
        val STRIP_HEIGHT = 60.dp
        const val ARM_SETTLE_MS = 50L
        const val MASH_GAP_MS = 60L
    }
}
