package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaMomentReporter
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaNoticeReportTag
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What two panel pages hand a screen reader (#470).
 *
 * - Last Stream: the graph under the report is no focus stop, and with a TV remote TalkBack reads
 *   only what takes focus, so its caption was never spoken. The report, the page's first stop, now
 *   carries the caption after its own words. Nothing on screen and no D-pad route changes; the
 *   #459 tests pin the route.
 * - Moment Saved: TalkBack said only "Keep Playing". Its message is now a pane titled Moment Saved
 *   and a polite live region, with Keep Playing still focused.
 *
 * These read the semantics tree, which is what Android's accessibility nodes are built from. They
 * cannot show what TalkBack chooses to say about a pane or a live region, or when: the Shield walk
 * is what shows that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-land")
class NovaPanelPagesSpokenComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()

    private fun text(id: Int) = rule.activity.getString(id)

    private fun show(page: NovaPage) {
        panel.open(page)
        rule.setPanelContent {
            Box(Modifier.width(600.dp).fillMaxHeight()) {
                NovaPageStackHost(state = panel) { }
            }
        }
        rule.waitForIdle()
        rule.frames(16)
    }

    // Last Stream.

    /** A clean minute of stream, with the frame-rate graph under its report. */
    private fun recap(): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(9L)
        acc.launch(launch())
        acc.connected(1_000L)
        acc.accept(sample(2_000L, expected = 60, rendered = 58.0), 2_000L)
        acc.accept(sample(62_000L, expected = 3_600, rendered = 60.0), 62_000L)
        return acc.finish(End.LEFT_STREAM, null, 63_000L, "2026-10-30T20:00:00Z")!!
    }

    private fun report() = rule.onNodeWithTag(NovaNoticeReportTag).fetchSemanticsNode().config

    @Test fun theLastStreamReportIsReadWithTheGraphsCaptionAfterIt() {
        val recap = recap()
        assertNotNull("the page has a graph", recap.fpsTrail)
        val trail = recap.fpsTrail!!
        val caption = NovaStreamRecapText.fpsTrail(rule.activity, recap, trail)
        val words = NovaStreamRecapText.page(rule.activity, recap)
        show(NovaStreamRecapPages.page(rule.activity, recap, onSaveReport = {}))

        val config = report()
        assertEquals("what the report shows is unchanged", words, config[SemanticsProperties.Text].joinToString("") { it.text })
        assertEquals("what it is read as: its words, then the caption", listOf(words + "\n\n" + caption), config[SemanticsProperties.ContentDescription])
        assertEquals("it is the first stop, as #459 made it", true, config.getOrElse(SemanticsProperties.Focused) { false })
        // The graph keeps its own description, for touch exploration on a phone.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(caption))).assertExists()
    }

    @Test fun aReadFirstNoticeWithNothingUnderItIsReadAsItsWordsAlone() {
        val words = (1..40).joinToString("\n") { "A line of the comparison, $it" }
        show(NovaCommonPage.Notice(key = "read-first", title = "Compared", message = words, closeLabel = "Done", readFirst = true))
        assertFalse("no description stands in for the words", report().contains(SemanticsProperties.ContentDescription))
    }

    // Moment Saved.

    private val marked = "2026-11-11T02:26:25Z"

    private fun momentSaved() =
        novaMomentSavedPage(rule.activity, NovaMomentReporter.Saved(File("nova-moment-20261111T022625Z.md"), marked), onShare = {})

    @Test fun momentSavedIsAPaneTitledWithItsMessageALiveRegionAndKeepPlayingFocused() {
        val page = momentSaved()
        assertEquals(text(R.string.nova_moment_report_saved_title), page.title)
        show(page)
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, page.title)).assertExists()
        val message = rule.onNodeWithText(page.message.text).fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, message.getOrNull(SemanticsProperties.LiveRegion))
        rule.onNodeWithText(text(R.string.nova_moment_report_keep_playing)).assertIsFocused()
    }

    /** Every other confirmation is as it was: no pane, no live region. */
    @Test fun anOrdinaryConfirmationAnnouncesNothingOfItsOwn() {
        val page = NovaCommonPage.Confirm(
            key = "end-session",
            title = "End Session",
            message = AnnotatedString("Unsaved progress on the host is lost."),
            stayLabel = "Stay",
            actionLabel = "End Session",
            destructive = true,
            onConfirm = {},
        )
        show(page)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        rule.onAllNodesWithText(page.message.text).assertCountEquals(1)
    }
}
