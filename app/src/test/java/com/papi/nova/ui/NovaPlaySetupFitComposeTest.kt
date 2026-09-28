package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Setup cuts nothing (R13). What does not fit where it stands is shown whole elsewhere or
 * not at all: the legend as every card, else the current choice's card, else nothing, and the plan
 * as one row that opens its own page, where every part of it is a stop the cursor can scroll to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupFitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val plan = NovaPlaySetupPlan(
        mode = "Private Stream",
        lines = listOf("1920x1080 at 60 FPS in HEVC", "Nothing outside this game changes."),
        facts = listOf(
            NovaPlaySetupFact(key = "Last session", value = "Smooth", tone = NovaPlaySetupTone.GOOD),
            NovaPlaySetupFact(key = "Limited by", value = "Network", detail = "12 ms of jitter", tone = NovaPlaySetupTone.WARN),
        ),
    )

    private fun placed(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    private fun fits(maxHeight: Dp, heights: List<Dp>, keepTallest: Boolean = false) = rule.setPanelContent {
        NovaFirstThatFits(
            maxHeight = maxHeight,
            forms = heights.mapIndexed { index, height ->
                { Box(Modifier.fillMaxWidth().height(height).testTag("form$index")) }
            },
            keepTallest = keepTallest,
            modifier = Modifier.width(200.dp).testTag("fit"),
        )
    }

    @Test
    fun theFirstFormThatFitsIsPlacedWhole() {
        fits(100.dp, listOf(120.dp, 60.dp))
        assertTrue("the second form fits and is drawn", placed("form1"))
        assertTrue("the first did not fit and is not drawn cut", !placed("form0"))
        val bounds = rule.onNodeWithTag("fit").getUnclippedBoundsInRoot()
        assertEquals(60.dp, bounds.bottom - bounds.top)
    }

    @Test
    fun whenNothingFitsNothingIsDrawn() {
        fits(40.dp, listOf(120.dp, 60.dp))
        assertTrue(!placed("form0") && !placed("form1"))
        val bounds = rule.onNodeWithTag("fit").getUnclippedBoundsInRoot()
        assertEquals(0.dp, bounds.bottom - bounds.top)
    }

    @Test
    fun theWholeFormIsPreferredWhenItFits() {
        fits(200.dp, listOf(120.dp, 60.dp))
        assertTrue(placed("form0") && !placed("form1"))
    }

    @Test
    fun aLegendKeepsTheTallestHeightItHasHad() {
        var tall by mutableStateOf(true)
        rule.setPanelContent {
            NovaFirstThatFits(
                maxHeight = 100.dp,
                forms = listOf({ Box(Modifier.fillMaxWidth().height(if (tall) 80.dp else 30.dp)) }),
                keepTallest = true,
                modifier = Modifier.width(200.dp).testTag("fit"),
            )
        }
        rule.runOnIdle { tall = false }
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("fit").getUnclippedBoundsInRoot()
        assertEquals("a shorter legend keeps the room, so the rows above do not move", 80.dp, bounds.bottom - bounds.top)
    }

    @Test
    fun aNarrowPanelShowsThePlanAsARowThatOpensItWhole() {
        var opened: NovaPlaySetupPlan? = null
        rule.setPanelContent {
            Box(Modifier.width(440.dp)) {
                NovaPlaySetupBody(
                    plan = plan,
                    rows = { NovaRow(title = "Resolution", onClick = {}) },
                    fitHeight = 400.dp,
                    onOpenPlan = { opened = it },
                )
            }
        }
        rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_ROW_TAG).assertExistsAndIsPlaced()
        rule.onNodeWithText("12 ms of jitter").assertDoesNotExist()
        rule.onNodeWithText("Private Stream", substring = true).performClick()
        assertEquals(plan, opened)
    }

    @Test
    fun withoutAPageThePlanStaysWholeInTheBody() {
        rule.setPanelContent {
            Box(Modifier.width(440.dp)) {
                NovaPlaySetupBody(plan = plan, rows = { NovaRow(title = "Resolution", onClick = {}) }, fitHeight = 400.dp)
            }
        }
        rule.onNodeWithTag(NOVA_PLAY_SETUP_READ_TAG).assertExistsAndIsPlaced()
        rule.onNodeWithText("12 ms of jitter", useUnmergedTree = true).assertExistsAndIsPlaced()
    }

    @Test
    fun thePlansPageOpensOnItsStatementAndWalksEveryFact() {
        val state = NovaPanelState()
        var closes = 0
        state.open(PlaySetupPage.Plan("What will happen", plan))
        val keys = rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = { closes++ }) { page ->
                if (page is PlaySetupPage.Plan) NovaPlaySetupPlanPage(page)
            }
        }
        rule.onNodeWithText("Private Stream", substring = true).assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Smooth", substring = true).assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("12 ms of jitter", substring = true).assertIsFocused()
        keys.press(NovaTestKeys.A)
        assertEquals("a part of the plan does nothing on A", 1, state.depth)
        keys.back()
        assertEquals(1, closes)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndIsPlaced() {
        assertTrue(fetchSemanticsNode().layoutInfo.isPlaced)
    }
}
