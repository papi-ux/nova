package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Last Stream page says at a glance: the shape, the calm line and the badge (#423, #424). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w640dp-h1200dp-land")
class NovaStreamRecapAtAGlanceComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()

    private fun recap(coveredMs: Long, lost: Long = 0L): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(9L)
        acc.launch(launch())
        acc.connected(1_000L)
        acc.accept(sample(2_000L, expected = 60, rendered = 58.0), 2_000L)
        acc.accept(sample(2_000L + coveredMs, expected = 216_000, lost = lost, rendered = 60.0), 2_000L + coveredMs)
        return acc.finish(End.LEFT_STREAM, null, 3_000L + coveredMs, "2026-10-30T20:00:00Z")!!
    }

    private fun show(recap: NovaStreamRecap.Recap) {
        panel.open(NovaStreamRecapPages.page(rule.activity, recap))
        rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { }
            }
        }
        rule.waitForIdle()
        rule.frames(40)
    }

    private val hour = NovaStreamRecap.CLEAR_SKIES_MS
    private val nothingMissing get() = rule.activity.getString(R.string.nova_recap_nothing_missing)
    private val clearSkies get() = rule.activity.getString(R.string.nova_recap_clear_skies)

    @Test fun aCleanHourShowsTheBadgeTheCalmLineAndTheLine() {
        show(recap(hour))
        rule.onNodeWithText(clearSkies).assertIsDisplayed()
        rule.onNodeWithText(nothingMissing).assertIsDisplayed()
        rule.onNodeWithTag(NOVA_FPS_SPARKLINE_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aCleanShortStreamIsCalmWithoutTheBadge() {
        show(recap(hour - 1L))
        rule.onNodeWithText(nothingMissing).assertIsDisplayed()
        rule.onAllNodesWithText(clearSkies).assertCountEquals(0)
    }

    @Test fun aStreamThatLostFramesSaysNeither() {
        show(recap(2 * hour, lost = 1L))
        rule.onAllNodesWithText(nothingMissing).assertCountEquals(0)
        rule.onAllNodesWithText(clearSkies).assertCountEquals(0)
        rule.onNodeWithTag(NOVA_FPS_SPARKLINE_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun talkBackReadsTheLineAsWordsAndOnce() {
        show(recap(hour))
        val words = "${rule.activity.getString(R.string.nova_recap_rendered_fps)} over this connection: 58.0 to 60.0"
        rule.onNodeWithContentDescription(words).assertExists()
        rule.onAllNodesWithText(words).assertCountEquals(0)
    }

    @Test fun theLineSharesThePageAndFocusStaysOnItsAction() {
        show(recap(hour))
        rule.onNodeWithText(rule.activity.getString(R.string.nova_recap_close)).assertIsFocused()
        rule.onAllNodesWithTag(NOVA_STREAM_RECAP_AT_A_GLANCE_TAG).assertCountEquals(1)
    }

    @Test fun theHonestWordingStaysOnThePage() {
        show(recap(hour))
        val page = NovaStreamRecapText.page(rule.activity, recap(hour))
        assertTrue(page.contains(rule.activity.getString(R.string.nova_recap_not_measured)))
    }

    @Test fun aRecapWithNothingToDrawHasNoBlockAtAll() {
        val acc = NovaStreamRecap.Accumulator(9L)
        acc.launch(launch())
        acc.connected(1_000L)
        show(acc.finish(End.LEFT_STREAM, null, 3_000L, "2026-10-30T20:00:00Z")!!)
        rule.onAllNodesWithTag(NOVA_STREAM_RECAP_AT_A_GLANCE_TAG).assertCountEquals(0)
    }

    @Test fun theLineRunsFromZeroToTheBestSoASteadyStreamReadsFlat() {
        val points = novaFpsSparklinePoints(listOf(60.0, 59.0, 60.0))
        assertEquals(listOf(0f, 0.5f, 1f), points.map { it.first })
        assertEquals(1f, points[0].second, 0f)
        assertEquals(59f / 60f, points[1].second, 0.0001f)
        assertEquals(emptyList<Pair<Float, Float>>(), novaFpsSparklinePoints(listOf(60.0)))
        // All zero, as a paused stream reads: flat along the floor, not a division by nothing.
        assertEquals(listOf(0f, 0f), novaFpsSparklinePoints(listOf(0.0, 0.0)).map { it.second })
    }
}
