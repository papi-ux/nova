package com.papi.nova.ui

import android.content.Context
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaMomentReport
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaselineFlow
import com.papi.nova.diagnostics.NovaWorkingBaselineStore
import com.papi.nova.novaAutomaticUpdatePage
import com.papi.nova.preferences.NovaUpdateRelease
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.ServerHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the notices that can run longer than their panel open, and what the D-pad does on them
 * (#464). Each opened on its action, which stands under the text, so a long one opened scrolled to
 * its end with its start out of a D-pad's reach, as the Last Stream page did (#459). The Working
 * Baseline comparison is read first; the update prompt, the network test's result and a plain
 * message keep their action's first focus and show their start. One that fits is as it was.
 *
 * The 1.3 cases set the font scale through the density the page is drawn at. Under this test
 * runner's text measuring that is not known to make a line taller, so they show that the page
 * opens and routes the same with the scale set, not that larger letters still fit. The short
 * panel cases are what take room away from the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land")
class NovaLongNoticesOpeningComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private val pressed = ArrayList<String>()

    private fun text(id: Int) = rule.activity.getString(id)

    /** Forty lines, each its own paragraph, so it is long however a line is measured. */
    private fun lines(what: String) = (1..40).joinToString("\n") { "$what $it" }

    private fun show(page: NovaCommonPage.Notice, fontScale: Float = 1f): NovaTestKeys {
        panel.open(page)
        val keys = rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    NovaPageStackHost(state = panel) { }
                }
            }
        }
        rule.waitForIdle()
        rule.frames(16)
        return keys
    }

    private fun message(page: NovaCommonPage.Notice) = rule.onNodeWithText(page.message)
    private fun button(label: String) = rule.onNodeWithText(label)
    private fun top(node: SemanticsNodeInteraction): Dp = node.getUnclippedBoundsInRoot().top

    private fun focused(node: SemanticsNodeInteraction): Boolean =
        node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    private fun press(keys: NovaTestKeys, key: Int, what: String, done: () -> Boolean) {
        var presses = 0
        while (!done()) {
            assertTrue("$what after $presses presses", presses++ < MAX_PRESSES)
            keys.press(key)
        }
    }

    /** The message's first line is under the title. */
    private fun assertTheMessageIsAtItsTop(page: NovaCommonPage.Notice) {
        val title = rule.onNodeWithText(page.title).getUnclippedBoundsInRoot()
        val start = top(message(page))
        assertTrue("the message starts at $start, the title ends at ${title.bottom}", start >= title.bottom)
    }

    /** Whole, not a sliver at the page's edge: nothing clips it. */
    private fun assertWhollyInView(label: String) {
        button(label).assertIsDisplayed()
        assertEquals(label, button(label).getUnclippedBoundsInRoot(), button(label).getBoundsInRoot())
    }

    /**
     * A long notice that keeps its action's first focus: its start and every action are in view,
     * [first] has focus, Up reaches the message, Up and Down scroll it, and Down at its end comes
     * back through the actions in order.
     */
    private fun assertOpensOnItsActionWithItsStartInView(page: NovaCommonPage.Notice, fontScale: Float, first: String, actions: List<String>) {
        val keys = show(page, fontScale)
        assertTheMessageIsAtItsTop(page)
        button(first).assertIsFocused()
        actions.forEach(::assertWhollyInView)
        val start = top(message(page))

        keys.press(NovaTestKeys.UP)
        message(page).assertIsFocused()
        assertEquals("taking focus moves nothing", start, top(message(page)))
        keys.press(NovaTestKeys.DOWN)
        assertTrue("Down scrolls the message", top(message(page)) < start)
        message(page).assertIsFocused()
        press(keys, NovaTestKeys.UP, "the message is back at its top") { top(message(page)) >= start }

        actions.forEach { label ->
            press(keys, NovaTestKeys.DOWN, "$label has focus") { focused(button(label)) }
            assertWhollyInView(label)
        }
        assertEquals("moving over an action never presses it", emptyList<String>(), pressed)
    }

    /** A notice that fits: its close has focus, its message is no stop and stands where it did. */
    private fun assertAShortNoticeIsAsItWas(page: NovaCommonPage.Notice) {
        val keys = show(page)
        assertTheMessageIsAtItsTop(page)
        button(page.closeLabel).assertIsFocused()
        assertWhollyInView(page.closeLabel)
        // No room is kept around the words for a ring they never wear: they start on the button's edge.
        assertEquals(button(page.closeLabel).getUnclippedBoundsInRoot().left, message(page).getUnclippedBoundsInRoot().left)
        keys.press(NovaTestKeys.UP)
        button(page.closeLabel).assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        button(page.closeLabel).assertIsFocused()
    }

    // Working Baseline's comparison, from a saved baseline as Compare shows it.

    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        override fun launchIo(name: String, block: suspend () -> Unit) {
            io += block
        }
        override suspend fun onMain(block: () -> Unit) = block()
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
    }

    private fun comparison(): NovaCommonPage.Notice {
        val runtime = HeldRuntime()
        val flow = NovaWorkingBaselineFlow(
            store = NovaWorkingBaselineStore(rule.activity.getSharedPreferences(NovaWorkingBaselineStore.FILE, Context.MODE_PRIVATE)),
            runtime = runtime,
            entry = { NovaWorkingBaseline.RequestedEntry.of("pc-entry", "app-entry") },
            isActive = { true },
            snapshot = {
                NovaMomentReport.capture(
                    markedAtUtc = "2026-10-08T13:00:00Z", nowElapsedMs = 10_000L, novaVersion = "1.4.15", buildType = "release",
                    androidRelease = "15", androidSdk = 35, device = "Google Pixel 9", watching = false, space = false,
                    requestedWidth = 1920, requestedHeight = 1080, requestedFps = 60f, requestedBitrateKbps = 20_000,
                    requestedCodec = "FORCE_HEVC",
                    sample = NovaMomentReport.Sample("c2.qti.hevc.decoder", 1920, 1080, 59.8, 60.0, 3.0, 4, 1, 0.5, 2.0,
                        120_000, 12, 9_800L),
                )
            },
        )
        flow.save {}
        rule.runOnUiThread { runtime.runIo() }
        rule.waitForIdle()
        val page = NovaWorkingBaselinePages(rule.activity, flow, feedback = {}, reopen = {}, redraw = {}).comparison()
        assertTrue("a saved baseline compares at length: ${page.message.lines().size} lines", page.message.lines().size >= 20)
        return page
    }

    /** Read first, as the Last Stream page is: the comparison has focus at its top, with Done in view under it. */
    private fun assertTheComparisonIsReadFirst(fontScale: Float) {
        val page = comparison()
        val done = text(R.string.nova_working_baseline_done)
        val keys = show(page, fontScale)
        assertTheMessageIsAtItsTop(page)
        message(page).assertIsFocused()
        assertWhollyInView(done)
        val start = top(message(page))

        keys.press(NovaTestKeys.UP)
        assertEquals("Up at the top moves nothing", start, top(message(page)))
        keys.press(NovaTestKeys.DOWN)
        assertTrue("Down scrolls the comparison", top(message(page)) < start)
        message(page).assertIsFocused()
        press(keys, NovaTestKeys.DOWN, "Done has focus") { focused(button(done)) }
        assertWhollyInView(done)
        keys.press(NovaTestKeys.UP)
        message(page).assertIsFocused()
        press(keys, NovaTestKeys.UP, "the comparison is back at its top") { top(message(page)) >= start }
        assertEquals(start, top(message(page)))
    }

    @Test fun theWorkingBaselineComparisonIsReadFirst() = assertTheComparisonIsReadFirst(1f)

    @Test fun theWorkingBaselineComparisonIsReadFirstAtLargeText() = assertTheComparisonIsReadFirst(1.3f)

    // The automatic update prompt, with a release's notes.

    private val download = "Download APK"

    private fun updatePrompt() = novaAutomaticUpdatePage(
        context = rule.activity,
        key = "nova-update",
        release = NovaUpdateRelease("v1.5.0", "1.5.0", "https://example.invalid/release", "nova.apk", "https://example.invalid/nova.apk", lines("A change in this release")),
        currentVersion = "1.4.15",
        primary = NovaAction(download) { pressed += download },
        onSkip = { pressed += "skip" },
    )

    private fun updateActions() = listOf(download, text(R.string.nova_update_skip_version), text(R.string.nova_update_later))

    @Test fun theUpdatePromptOpensOnDownloadWithItsVersionLineInView() =
        assertOpensOnItsActionWithItsStartInView(updatePrompt(), 1f, download, updateActions())

    @Test fun theUpdatePromptOpensOnDownloadWithItsVersionLineInViewAtLargeText() =
        assertOpensOnItsActionWithItsStartInView(updatePrompt(), 1.3f, download, updateActions())

    /**
     * Three actions on a panel too short to leave the notes their least room: the page itself has
     * to scroll, so it opens on the notes, with the version line in view, and Download is a few
     * presses of Down away rather than under the fold with the focus.
     */
    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    fun onAShortPanelTheUpdatePromptOpensOnItsNotesAndTheDpadReachesEveryAction() {
        val page = updatePrompt()
        val keys = show(page)
        assertTheMessageIsAtItsTop(page)
        message(page).assertIsFocused()
        updateActions().forEach { label ->
            press(keys, NovaTestKeys.DOWN, "$label has focus") { focused(button(label)) }
            button(label).assertIsDisplayed()
        }
        assertEquals(emptyList<String>(), pressed)
    }

    // The network test's result.

    private fun networkResult(summary: String) = ServerHelper.networkResultPage(rule.activity.resources, summary)

    private fun blockedPorts() = text(R.string.nettest_text_failure) + lines("UDP 47998, blocked port")

    @Test fun aLongNetworkResultOpensOnCloseWithItsStartInView() = networkResult(blockedPorts()).let {
        assertOpensOnItsActionWithItsStartInView(it, 1f, it.closeLabel, listOf(it.closeLabel))
    }

    @Test fun aLongNetworkResultOpensOnCloseWithItsStartInViewAtLargeText() = networkResult(blockedPorts()).let {
        assertOpensOnItsActionWithItsStartInView(it, 1.3f, it.closeLabel, listOf(it.closeLabel))
    }

    @Test fun aNetworkResultThatFitsIsAsItWas() =
        assertAShortNoticeIsAsItWas(networkResult(text(R.string.nettest_text_inconclusive)))

    // A plain message, as every caller of Dialog.displayDialog shows one.

    private fun plainMessage(message: String) =
        Dialog.messagePage("nova-legacy-dialog-1", "Host problem", message, action = null, closeLabel = "Close", onClose = {}, help = null)

    @Test fun aLongMessageOpensOnCloseWithItsStartInView() = plainMessage(lines("What the host said, line")).let {
        assertOpensOnItsActionWithItsStartInView(it, 1f, it.closeLabel, listOf(it.closeLabel))
    }

    @Test fun aLongMessageOpensOnCloseWithItsStartInViewAtLargeText() = plainMessage(lines("What the host said, line")).let {
        assertOpensOnItsActionWithItsStartInView(it, 1.3f, it.closeLabel, listOf(it.closeLabel))
    }

    @Test fun aMessageThatFitsIsAsItWas() = assertAShortNoticeIsAsItWas(plainMessage("The host is already awake."))

    private companion object {
        /** More than any page here needs; a D-pad that goes nowhere fails rather than hangs. */
        const val MAX_PRESSES = 80
    }
}
