package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
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
 * Play Setup cuts nothing (R13). The plan card is pinned above the rows and says the plan in two
 * lines; the whole plan opens on its own page, where every part is a stop the cursor can scroll to.
 * The rows scroll under the card when they are taller than the panel, and the card stays put.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupFitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val plan = NovaPlaySetupPlan(
        mode = "Private Stream",
        lines = listOf("1920×1080 at 60 FPS · HEVC", "Nothing outside this game changes."),
        facts = listOf(
            NovaPlaySetupFact(key = "Last session", value = "Smooth", tone = NovaPlaySetupTone.GOOD),
            NovaPlaySetupFact(key = "Limited by", value = "Network", detail = "12 ms of jitter", tone = NovaPlaySetupTone.WARN),
        ),
    )

    @Test
    fun thePlanCardSaysThePlanAndOpensItWholeOnItsPage() {
        var opened = 0
        rule.setPanelContent {
            Box(Modifier.width(476.dp)) {
                NovaPlaySetupBody(
                    card = {
                        NovaPlaySetupPlanCard(
                            title = "What Will Happen",
                            value = plan.mode,
                            line = novaPlaySetupPlanSummary(plan).orEmpty(),
                            onOpen = { opened++ },
                        )
                    },
                ) {
                    NovaRow(title = "Resolution", onClick = {})
                }
            }
        }
        rule.onNodeWithText("1920×1080 at 60 FPS · HEVC · Limited by: Network", substring = true, useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithText("12 ms of jitter", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun thePinnedCardStaysPutWhileTheRowsScrollUnderIt() {
        rule.setPanelContent {
            Box(Modifier.width(476.dp).height(260.dp)) {
                NovaPlaySetupBody(card = { NovaPlaySetupPlanCard(title = "What Will Happen", value = "Private Stream", line = "1920×1080") }) {
                    repeat(12) { index -> NovaRow(title = "Row $index", onClick = {}, modifier = Modifier.testTag("row-$index")) }
                }
            }
        }
        val before = rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG).getUnclippedBoundsInRoot()
        rule.onNodeWithTag("row-11").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("row-11").assertIsFocused()
        val after = rule.onNodeWithTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG).getUnclippedBoundsInRoot()
        assertEquals("the plan card is pinned, not scrolled away with the rows", before, after)
        val rows = rule.onNodeWithTag(NOVA_PLAY_SETUP_ROWS_TAG).getUnclippedBoundsInRoot()
        val last = rule.onNodeWithTag("row-11").getUnclippedBoundsInRoot()
        assertTrue("the focused row is brought whole into the rows' view", last.bottom <= rows.bottom + 0.5.dp)
        assertTrue("and never under the card", last.top >= after.bottom)
    }

    @Test
    fun aPreviewSaysWhatWouldHoldTheChoiceBackInPlaceOfTheMode() {
        rule.setPanelContent {
            NovaPlaySetupPlanCard(
                title = "If you choose 2x",
                value = "Private Stream",
                line = novaPlaySetupPreviewLine(
                    "1920×1080 at 120 FPS · 200 Mbps · PyroWave · SDR",
                    NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, "3840×2160", limit = "Limited by bitrate"),
                ),
                accentPart = "3840×2160",
                limit = "Limited by bitrate",
            )
        }
        rule.onNodeWithText("Limited by bitrate", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Private Stream", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("3840×2160 at 120 FPS · 200 Mbps · PyroWave · SDR", useUnmergedTree = true).assertExists()
    }

    @Test
    fun thePlansPageOpensOnItsStatementAndWalksEveryFact() {
        val state = NovaPanelState()
        var closes = 0
        state.open(PlaySetupPage.Plan("What Will Happen", plan))
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
}
