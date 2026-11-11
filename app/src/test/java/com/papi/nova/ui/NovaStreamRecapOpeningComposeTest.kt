package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the Last Stream page opens and what the D-pad does on it (#459). On the RP6 it opened with
 * focus down on Save Report and the report scrolled to its end, so the end reason and the time were
 * off screen, and Up from Save Report did nothing: the report was no stop, so a pad or a remote
 * could not reach its top at all. It opens on the report's top now, with what it opened on before
 * still in view under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-land")
class NovaStreamRecapOpeningComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private var saves = 0

    private fun text(id: Int) = rule.activity.getString(id)

    /** A clean minute of stream, so the page has its report, its glance block and both actions. */
    private fun recap(end: End): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(9L)
        acc.launch(launch())
        acc.connected(1_000L)
        acc.accept(sample(2_000L, expected = 60, rendered = 58.0), 2_000L)
        acc.accept(sample(62_000L, expected = 3_600, rendered = 60.0), 62_000L)
        return acc.finish(end, null, 63_000L, "2026-10-30T20:00:00Z")!!
    }

    /** The page as View makes it, each press a new one. */
    private fun page(recap: NovaStreamRecap.Recap) =
        NovaStreamRecapPages.page(rule.activity, recap, onSaveReport = { saves++ })

    /** The Library's end panel on a 1080p handheld: about half a landscape screen wide, and its height. */
    private fun show(recap: NovaStreamRecap.Recap, fontScale: Float = 1f): NovaTestKeys {
        panel.open(page(recap))
        val keys = rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(600.dp).fillMaxHeight()) {
                    NovaPageStackHost(state = panel) { }
                }
            }
        }
        rule.waitForIdle()
        rule.frames(16)
        return keys
    }

    private fun reopen(recap: NovaStreamRecap.Recap) {
        panel.close()
        rule.frames(16)
        panel.open(page(recap))
        rule.waitForIdle()
        rule.frames(16)
    }

    private fun report(recap: NovaStreamRecap.Recap) = rule.onNodeWithText(NovaStreamRecapText.page(rule.activity, recap))
    private fun save() = rule.onNodeWithText(text(R.string.nova_recap_save_report))
    private fun close() = rule.onNodeWithText(text(R.string.nova_recap_close))

    private fun top(node: SemanticsNodeInteraction): Dp = node.getUnclippedBoundsInRoot().top

    private fun focused(node: SemanticsNodeInteraction): Boolean =
        node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    /** Presses [key] until [done], and says so if it never is. */
    private fun press(keys: NovaTestKeys, key: Int, what: String, done: () -> Boolean) {
        var presses = 0
        while (!done()) {
            assertTrue("$what after $presses presses", presses++ < MAX_PRESSES)
            keys.press(key)
        }
    }

    private fun glance() = rule.onNodeWithTag(NOVA_STREAM_RECAP_AT_A_GLANCE_TAG)

    /** The report's first line is under the title and the report has focus. */
    private fun assertTheReportIsAtItsTop(recap: NovaStreamRecap.Recap) {
        val title = rule.onNodeWithText(text(R.string.nova_recap_page_title)).getUnclippedBoundsInRoot()
        val reportTop = top(report(recap))
        assertTrue("the report starts at $reportTop, the title ends at ${title.bottom}", reportTop >= title.bottom)
        report(recap).assertIsFocused()
    }

    /**
     * The report at its top, and under it, in view without a scroll, what the page used to open on:
     * the block that reads at a glance and both actions (#423).
     */
    private fun assertOpensAtTheTop(recap: NovaStreamRecap.Recap) {
        assertTheReportIsAtItsTop(recap)
        // The report is longer than its area here, or opening at its top would prove nothing.
        val reportEnd = report(recap).getUnclippedBoundsInRoot().bottom
        assertTrue("the report ends at $reportEnd, past where the block starts at ${top(glance())}", reportEnd > top(glance()))
        listOf(glance(), save(), close()).forEach {
            it.assertIsDisplayed()
            // Whole, not a sliver at the page's edge: nothing clips it.
            assertEquals(it.getUnclippedBoundsInRoot(), it.getBoundsInRoot())
        }
    }

    @Test
    fun opensAtTheTopAfterAnEnd() {
        val recap = recap(End.END_REQUESTED)
        show(recap)
        assertOpensAtTheTop(recap)
    }

    @Test
    fun opensAtTheTopAfterADisconnect() {
        val recap = recap(End.LEFT_STREAM)
        show(recap)
        assertOpensAtTheTop(recap)
    }

    /** A recap kept from an earlier stream is viewed again and again: the last visit's place is not kept. */
    @Test
    fun aRecapLeftByAnEarlierStreamOpensAtTheTopOnEveryView() {
        val recap = recap(End.END_REQUESTED)
        val keys = show(recap)
        press(keys, NovaTestKeys.DOWN, "Close has focus") { focused(close()) }
        close().assertIsDisplayed()
        reopen(recap)
        assertOpensAtTheTop(recap)
    }

    @Test
    fun theDpadScrollsTheReportAndReachesSaveReportThenClose() {
        val recap = recap(End.END_REQUESTED)
        val keys = show(recap)
        val start = top(report(recap))

        keys.press(NovaTestKeys.UP)
        assertEquals("Up at the top moves nothing", start, top(report(recap)))
        report(recap).assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        val moved = top(report(recap))
        assertTrue("Down scrolls the report: it started at $start and is at $moved", moved < start)
        report(recap).assertIsFocused()

        press(keys, NovaTestKeys.DOWN, "Save Report has focus") { focused(save()) }
        save().assertIsDisplayed()
        keys.press(NovaTestKeys.DOWN)
        close().assertIsFocused()
        close().assertIsDisplayed()

        keys.press(NovaTestKeys.UP)
        save().assertIsFocused()
        keys.press(NovaTestKeys.UP)
        report(recap).assertIsFocused()
        press(keys, NovaTestKeys.UP, "the report is back at its top") { top(report(recap)) >= start }
        assertEquals(start, top(report(recap)))
        report(recap).assertIsFocused()
        assertEquals("moving over Save Report never presses it", 0, saves)
    }

    @Test
    fun largeTextInLandscapeStillShowsTheTopFirst() {
        val recap = recap(End.LEFT_STREAM)
        val keys = show(recap, fontScale = 1.3f)
        assertOpensAtTheTop(recap)
        press(keys, NovaTestKeys.DOWN, "Save Report has focus") { focused(save()) }
        save().assertIsDisplayed()
        keys.press(NovaTestKeys.DOWN)
        close().assertIsFocused()
        close().assertIsDisplayed()
    }

    /** TalkBack reads down the page: the whole report as one stop, what reads at a glance, then the actions. */
    @Test
    fun theReadingOrderIsTheReportTheGlanceThenSaveReportAndClose() {
        val recap = recap(End.END_REQUESTED)
        show(recap)
        val tops = listOf(
            top(report(recap)),
            top(glance()),
            top(save()),
            top(close()),
        )
        assertEquals("each stands under the one before it: $tops", tops.sorted(), tops)
        assertEquals(tops.size, tops.distinct().size)
    }

    /**
     * A panel too short for the block and the actions to leave the report its least room: the page
     * scrolls as any page does, and still opens on the report's top with the actions a D-pad away.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun onAShortPanelThePageStillOpensOnTheReportAndTheDpadReachesTheActions() {
        val recap = recap(End.END_REQUESTED)
        val keys = show(recap)
        assertTheReportIsAtItsTop(recap)
        press(keys, NovaTestKeys.DOWN, "Save Report has focus") { focused(save()) }
        save().assertIsDisplayed()
        keys.press(NovaTestKeys.DOWN)
        close().assertIsFocused()
        close().assertIsDisplayed()
        assertEquals(0, saves)
    }

    private companion object {
        /** More than any page here needs; a D-pad that goes nowhere fails rather than hangs. */
        const val MAX_PRESSES = 60
    }
}
