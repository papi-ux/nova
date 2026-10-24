package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapStore
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.panel.setPanelContent
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library's Last Stream recap: it shows what ended and when in the theme's text colours, never
 * takes focus on its own, and View and Dismiss are its only actions. Dismiss through the store
 * removes that recap only. In the host bar it takes no room from the host, the menus or a live
 * session's Resume and End.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaStreamRecapCardComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val store = NovaStreamRecapStore()
    private var views = 0
    private var dismissals = 0

    private fun published(end: End = End.END_REQUESTED): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(store.begin())
        acc.launch(launch(host = "host-entry-1"))
        acc.connected(1_000L)
        acc.accept(sample(2_000L, expected = 60), 2_000L)
        acc.accept(sample(3_000L, expected = 120), 3_000L)
        val recap = acc.finish(end, null, 4_000L, "2026-10-08T14:00:00Z")!!
        assertTrue(store.publish(recap))
        return recap
    }

    private fun show(recap: NovaStreamRecap.Recap) {
        rule.setPanelContent {
            Column {
                NovaStreamRecapCard(
                    recap = recap,
                    onView = { views++ },
                    onDismiss = {
                        dismissals++
                        store.dismiss(recap.generation)
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun theCardSaysWhatEndedAndNeverTakesFocusOnItsOwn() {
        show(published())
        val card = rule.onNodeWithTag(NOVA_STREAM_RECAP_CARD_TAG, useUnmergedTree = true)
        card.assertExists()
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).assertIsNotFocused()
        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).assertIsNotFocused()
        val description = rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        val activity = rule.activity
        assertTrue(description, description.startsWith(activity.getString(R.string.nova_recap_card_title)))
        assertTrue(description, description.contains("End requested · "))
        // The stamp as stored, and the counts, are the page's: View opens them.
        listOf("2026-10-08T14:00:00Z", "UTC", "client samples").forEach { assertFalse("$it in $description", description.contains(it)) }
    }

    // papi's screenshot of 2026-10-23 23:34: "Last stream" and its line drew black on the dark
    // theme. They named no colour, and nothing above them in the Library provides one, so they
    // took Material's default content colour.
    @Test fun itsWordsTakeTheThemesTextRolesNotMaterialsDefaultBlack() {
        var primary = Color.Unspecified
        var accent = Color.Unspecified
        val recap = published(End.SURFACE_LOST)
        rule.setPanelContent {
            val colors = LocalNovaComposeColors.current
            primary = colors.textPrimary
            accent = colors.accent
            Column { NovaStreamRecapCard(recap = recap, onView = {}, onDismiss = {}) }
        }
        rule.waitForIdle()
        val summary = rule.onNodeWithTag(NOVA_STREAM_RECAP_SUMMARY_TAG, useUnmergedTree = true)
        val label = rule.onNodeWithText(
            rule.activity.getString(R.string.nova_recap_card_title).uppercase(Locale.getDefault()),
            useUnmergedTree = true,
        )
        assertEquals("the summary is the theme's primary text", primary, textColor(summary))
        assertEquals("the label is a section label, in the accent", accent, textColor(label))
        assertNotEquals("Material's default content colour, black", Color.Black, textColor(summary))
        assertNotEquals("Material's default content colour, black", Color.Black, textColor(label))
    }

    @Test fun viewOpensTheRecapAndChangesNothing() {
        val recap = published()
        show(recap)
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, views)
        assertEquals(0, dismissals)
        assertEquals(recap.generation, store.forHost("host-entry-1")!!.generation)
    }

    @Test fun dismissRemovesThatRecapFromTheStore() {
        show(published())
        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(0, views)
        assertNull(store.forHost("host-entry-1"))
    }

    private val game = PolarisGame(id = "active", name = "Control Ultimate Edition", source = "steam", launcherSource = "steam")
    private val session = NovaLibraryActiveSessionUiState(24, "active", "Control Ultimate Edition", "Retroid Pocket", true, 0, false, false, 1920, 1080, 60f)

    /**
     * The landscape strip, with a live session's card when [live], and the recap while [withRecap]
     * says so: the same strip with and without it, so what the recap costs can be read off.
     */
    private fun strip(live: Boolean, withRecap: () -> Boolean): NovaLibraryHeroState {
        val recap = published(End.SURFACE_LOST)
        val hero = NovaLibraryUiStateMapper.build(listOf(game), "", NovaLibraryFilterState(), activeSession = session).hero
        rule.setPanelContent {
            val shown = withRecap()
            NovaLibraryLandscapeShowcaseStripContent(
                hostLabel = "pc-papi.lan",
                polarisReady = true,
                onOpenOptions = {},
                onOpenSystemMenu = {},
                continueSlot = if (live) {
                    { fit ->
                        NovaLibraryStripContinue(
                            hero = hero,
                            apiClient = PolarisApiClient(rule.activity, ""),
                            fit = fit,
                            onPrimaryAction = {},
                            onSecondaryAction = if (hero.secondaryAction != null) ({}) else null,
                        )
                    }
                } else {
                    null
                },
                continueCard = hero.topBarContinue(),
                recapWords = if (shown) novaStreamRecapWords(recap) else null,
                recapSlot = if (shown) {
                    { lines -> NovaStreamRecapCard(recap = recap, onView = {}, onDismiss = {}, summaryLines = lines) }
                } else {
                    null
                },
            )
        }
        rule.waitForIdle()
        return hero
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test fun inAnIdleStripTheRecapCostsTheHostAndTheMenusNothing() {
        var withRecap by mutableStateOf(false)
        strip(live = false) { withRecap }
        val parts = listOf("nova-library-toolbar-identity", "nova-library-toolbar-options", "nova-library-toolbar-system-menu")
        val before = parts.map { bounds(it) }
        rule.runOnIdle { withRecap = true }
        rule.waitForIdle()
        assertEquals("the host, Options and System keep their places", before, parts.map { bounds(it) })
        rule.onAllNodesWithTag(NOVA_STREAM_RECAP_CARD_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag(NOVA_STREAM_RECAP_CARD_TAG, useUnmergedTree = true).assertIsDisplayed()
        // Present, but never focused first: the Library's own focus keeps its place.
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).assertIsNotFocused()
        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).assertIsNotFocused()
    }

    @Test fun besideResumeAndEndTheRecapTakesTheBarsSecondLine() {
        var withRecap by mutableStateOf(false)
        val hero = strip(live = true) { withRecap }
        val resume = rule.onNodeWithContentDescription(hero.actionLabel, substring = true)
        val resumeBefore = resume.getUnclippedBoundsInRoot()
        val cardBefore = bounds("nova-library-showcase-continue")
        rule.runOnIdle { withRecap = true }
        rule.waitForIdle()
        assertEquals("Resume keeps its place", resumeBefore, resume.getUnclippedBoundsInRoot())
        assertEquals("the live card keeps all of its room", cardBefore, bounds("nova-library-showcase-continue"))
        val strip = bounds("nova-library-landscape-toolbar")
        val line = bounds(NOVA_LIBRARY_RECAP_LINE_TAG)
        val card = bounds(NOVA_STREAM_RECAP_CARD_TAG)
        assertTrue("the recap's line is under the strip: $line under $strip", line.top >= strip.bottom - 0.5.dp)
        assertTrue("the recap is on its line: $card in $line", card.top >= line.top - 0.5.dp && card.bottom <= line.bottom + 0.5.dp)
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).assertIsNotFocused()
    }

    @Test fun inPortraitTheRecapIsTheHeadersSecondLine() {
        val recap = published(End.SURFACE_LOST)
        var withRecap by mutableStateOf(false)
        rule.setPanelContent {
            Box(Modifier.width(360.dp)) {
                NovaLibraryPortraitToolbarContent(
                    hostLabel = "pc-papi.lan",
                    resultCount = 12,
                    layoutLabel = "Grid",
                    polarisReady = true,
                    onOpenOptions = {},
                    onOpenSystemMenu = {},
                    recapSlot = if (withRecap) {
                        { NovaStreamRecapCard(recap = recap, onView = {}, onDismiss = {}) }
                    } else {
                        null
                    },
                )
            }
        }
        rule.waitForIdle()
        val options = bounds("nova-library-toolbar-options")
        rule.runOnIdle { withRecap = true }
        rule.waitForIdle()
        assertEquals("Options keeps its place", options, bounds("nova-library-toolbar-options"))
        val line = bounds(NOVA_LIBRARY_RECAP_LINE_TAG)
        val header = bounds("nova-library-portrait-toolbar")
        assertTrue("the recap's line is under the menus: $line under $options", line.top >= options.bottom - 0.5.dp)
        assertTrue("and inside the header's frame: $line in $header", line.bottom <= header.bottom + 0.5.dp)
    }

    private fun textColor(node: SemanticsNodeInteraction): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first().layoutInput.style.color
    }
}
