package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A compact page header is one 40dp line, and the tap on a pushed page's `‹ Title` that goes back
 * had only that line to land on (audit C24). A finger gets 48dp: the target reaches 4dp past the
 * line above and below, while the line keeps its height, so the rows under it do not move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaPageHeaderBackTargetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class TestPage(override val key: String, override val title: String) : NovaPage

    private val state = NovaPanelState()
    private val rowTaps = mutableListOf<String>()

    private fun pushedPage() {
        state.open(TestPage("root", "Root"))
        rule.setPanelContent {
            NovaPanelFrame(
                edge = NovaEdge.End,
                width = NovaPanelWidth.Standard,
                open = true,
                onDismissRequest = {},
                onClosed = {},
                scrim = NovaScrim.None,
            ) {
                NovaPageStackHost(state = state) { page ->
                    NovaRow(title = "${page.title} row", onClick = { rowTaps += page.key }, modifier = Modifier.testTag("row-${page.key}"))
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { state.push(TestPage("pushed", "Pushed")) }
        rule.waitForIdle()
    }

    private fun tapAt(x: Float, y: Float) {
        rule.onRoot().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    @Test
    fun aTapJustUnderTheCompactHeaderLineStillGoesBack() {
        pushedPage()
        val back = rule.onNode(hasTestTag(NovaPageBackTag) and hasText("Pushed")).getUnclippedBoundsInRoot()
        val lineBottom = rule.onNodeWithTag("row-pushed").getUnclippedBoundsInRoot().top
        with(rule.density) {
            tapAt(((back.left + back.right) / 2).toPx(), (lineBottom + 3.dp).toPx())
        }
        rule.runOnIdle { assertEquals("3dp under the line is still the header's back", 1, state.depth) }
        assertEquals("and not the row under it", emptyList<String>(), rowTaps)
    }

    @Test
    fun aTapJustAboveTheCompactHeaderLineStillGoesBack() {
        pushedPage()
        val back = rule.onNode(hasTestTag(NovaPageBackTag) and hasText("Pushed")).getUnclippedBoundsInRoot()
        val lineTop = rule.onNodeWithTag("row-pushed").getUnclippedBoundsInRoot().top - NovaPanelMetrics.HeaderHeightCompact
        with(rule.density) {
            tapAt(((back.left + back.right) / 2).toPx(), (lineTop - 3.dp).toPx())
        }
        rule.runOnIdle { assertEquals("3dp over the line is still the header's back", 1, state.depth) }
    }
}
