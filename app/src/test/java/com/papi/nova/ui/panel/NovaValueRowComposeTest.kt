package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaValueRowComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sizes = listOf("S", "M", "L").map { NovaOption(it, it) }

    private fun valueRow(ordered: Boolean, style: NovaValueStyle = NovaValueStyle.Auto, start: String = "M"): Pair<NovaTestKeys, () -> String> {
        var current by mutableStateOf(start)
        val keys = rule.setPanelContent {
            NovaValueRow(
                title = "Size",
                options = sizes,
                current = current,
                onChange = { current = it },
                ordered = ordered,
                style = style,
                modifier = Modifier.testTag("row"),
            )
        }
        rule.onNodeWithTag("row").requestFocus()
        rule.waitForIdle()
        return keys to { current }
    }

    @Test
    fun leftAndRightChangeTheValueAndFocusStaysOnTheRow() {
        val (keys, current) = valueRow(ordered = false)
        keys.down(NovaTestKeys.RIGHT)
        keys.up(NovaTestKeys.RIGHT)
        assertEquals("L", current())
        rule.onNodeWithTag("row").assertIsFocused()

        keys.down(NovaTestKeys.LEFT)
        keys.down(NovaTestKeys.LEFT, repeat = 1)
        keys.up(NovaTestKeys.LEFT)
        assertEquals("repeats count too", "S", current())
        rule.onNodeWithTag("row").assertIsFocused()
    }

    @Test
    fun orderedRowsStopAtTheEndsAndKeepFocus() {
        val (keys, current) = valueRow(ordered = true)
        repeat(4) { keys.press(NovaTestKeys.RIGHT) }
        assertEquals("L", current())
        rule.onNodeWithTag("row").assertIsFocused()
        repeat(5) { keys.press(NovaTestKeys.LEFT) }
        assertEquals("S", current())
        rule.onNodeWithTag("row").assertIsFocused()
    }

    @Test
    fun unorderedRowsWrap() {
        val (keys, current) = valueRow(ordered = false, start = "L")
        keys.press(NovaTestKeys.RIGHT)
        assertEquals("S", current())
        keys.press(NovaTestKeys.LEFT)
        assertEquals("L", current())
    }

    @Test
    fun aStepsForwardAndWrapsEvenOnAnOrderedRow() {
        val (keys, current) = valueRow(ordered = true)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("L", current())
        keys.press(NovaTestKeys.CENTER)
        assertEquals("S", current())
    }

    @Test
    fun aFlipsASwitchBothWays() {
        // On the RP6, A turned HDR on and then did nothing: only Left turned it off.
        var on by mutableStateOf(false)
        val keys = rule.setPanelContent {
            NovaValueRow(
                title = "HDR",
                options = listOf(NovaOption(false, "Off"), NovaOption(true, "On")),
                current = on,
                onChange = { on = it },
                modifier = Modifier.testTag("switch"),
            )
        }
        rule.onNodeWithTag("switch").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.A)
        assertEquals(true, on)
        keys.press(NovaTestKeys.A)
        assertEquals(false, on)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(true, on)
        rule.onNodeWithTag("switch").assertIsFocused()
    }

    @Test
    fun theStateDescriptionIsTheCurrentLabel() {
        val (keys, _) = valueRow(ordered = false)
        rule.onNodeWithTag("row").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "M"))
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithTag("row").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "L"))
    }

    @Test
    fun aDisabledRowLetsLeftAndRightMoveFocusOnAndChangesNothing() {
        var current by mutableStateOf("M")
        var steps by mutableIntStateOf(4)
        val keys = rule.setPanelContent {
            Row {
                NovaRow(title = "Rail", onClick = {}, modifier = Modifier.width(RAIL_WIDTH).testTag("rail"))
                Column(Modifier.weight(1f)) {
                    NovaValueRow(
                        title = "Size",
                        options = sizes,
                        current = current,
                        onChange = { current = it },
                        enabled = false,
                        modifier = Modifier.testTag("row"),
                    )
                    NovaStepperRow(
                        title = "Steps",
                        value = steps,
                        range = 0..10,
                        step = 1,
                        format = { "$it" },
                        onChange = { steps = it },
                        enabled = false,
                        modifier = Modifier.testTag("stepper"),
                    )
                }
            }
        }
        rule.onNodeWithTag("row").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("row").assertIsFocused()

        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithTag("rail").assertIsFocused()
        assertEquals("a row that cannot change keeps its value", "M", current)

        rule.onNodeWithTag("stepper").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithTag("rail").assertIsFocused()
        assertEquals(4, steps)
    }

    @Test
    fun segmentedMarksTheCurrentSegmentWithTheCheck() {
        valueRow(ordered = false, style = NovaValueStyle.Segmented)
        val marks = rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one mark, on the current segment only", 1, marks.size)
        val mark = rule.onNodeWithTag(NovaCurrentMarkTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val current = rule.onNodeWithText("M", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val next = rule.onNodeWithText("L", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the check trails the current label (R9)", mark.left >= current.right && mark.left < current.right + NovaPanelMetrics.SpaceSm)
        assertTrue("and not another", next.left > mark.right)
    }

    @Test
    fun valueRowsSitOnThePlainRowScale() {
        var current by mutableStateOf("M")
        var on by mutableStateOf(false)
        rule.setPanelContent {
            Column {
                NovaValueRow("Size", sizes, current, { current = it }, style = NovaValueStyle.Segmented, modifier = Modifier.testTag("segments"))
                NovaValueRow("Size", sizes, current, { current = it }, style = NovaValueStyle.Cycler, modifier = Modifier.testTag("cycler"))
                NovaValueRow("HUD", listOf(NovaOption(false, "Off"), NovaOption(true, "On")), on, { on = it }, modifier = Modifier.testTag("switch"))
            }
        }
        for (tag in listOf("segments", "cycler", "switch")) {
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals("$tag row height", NovaPanelMetrics.RowMinHeight, bounds.bottom - bounds.top)
        }
    }

    @Test
    fun theCyclersArrowsStayPutAsTheValueChanges() {
        val options = listOf("30", "60", "120", "Unlimited").map { NovaOption(it, "$it FPS") }
        var current by mutableStateOf("30")
        val keys = rule.setPanelContent {
            NovaValueRow("Frame rate", options, current, { current = it }, style = NovaValueStyle.Cycler, modifier = Modifier.testTag("row"))
        }
        rule.onNodeWithTag("row").requestFocus()
        rule.waitForIdle()
        fun arrows() = listOf(NovaChevronBackTag, NovaChevronOpensTag).map { rule.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val before = arrows()
        repeat(3) {
            keys.press(NovaTestKeys.RIGHT)
            assertEquals("the widest label's room is kept, so nothing moves under the thumb", before, arrows())
        }
    }

    @Test
    fun segmentsThatCannotFitTheRowDrawAsACycler() {
        var current by mutableStateOf("M")
        rule.setPanelContent {
            Column {
                Box(Modifier.width(NARROW_ROW)) {
                    NovaValueRow("Size", sizes, current, { current = it }, style = NovaValueStyle.Segmented, modifier = Modifier.testTag("narrow"))
                }
            }
        }
        rule.onNodeWithTag(NovaChevronBackTag, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("narrow").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "M"))
    }

    @Test
    fun inARightToLeftLayoutTheDpadFollowsTheScreenAndTalkBackFollowsTheOrder() {
        var current by mutableStateOf("M")
        val keys = rule.setPanelContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NovaValueRow("Size", sizes, current, { current = it }, modifier = Modifier.testTag("row"))
            }
        }
        rule.onNodeWithTag("row").requestFocus()
        rule.waitForIdle()

        keys.press(NovaTestKeys.LEFT)
        assertEquals("options run from the right, so Left moves on", "L", current)

        val actions = rule.onNodeWithTag("row").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Previous" }.action() }
        assertEquals("M", current)
        rule.runOnIdle { actions.first { it.label == "Previous" }.action() }
        assertEquals("S", current)
    }

    @Test
    fun autoPicksItsStyleByTheCharacterRule() {
        fun options(vararg labels: String) = labels.map { NovaOption(it, it) }
        assertEquals(NovaValueStyle.Switch, resolveNovaValueStyle(listOf(NovaOption(false, "Off"), NovaOption(true, "On"))))
        // Slim / Minimal / Performance / Debug is 27 characters, so it fits.
        assertEquals(NovaValueStyle.Segmented, resolveNovaValueStyle(options("Slim", "Minimal", "Performance", "Debug")))
        assertEquals(NovaValueStyle.Segmented, resolveNovaValueStyle(options("Aaaaaaa", "Bbbbbbb", "Ccccccc", "Ddddddd")))
        assertEquals("29 characters", NovaValueStyle.Cycler, resolveNovaValueStyle(options("Aaaaaaa", "Bbbbbbb", "Ccccccc", "Dddddddee")))
        assertEquals("five options", NovaValueStyle.Cycler, resolveNovaValueStyle(options("A", "B", "C", "D", "E")))
        assertEquals("an explicit style wins", NovaValueStyle.Cycler, resolveNovaValueStyle(options("A", "B"), NovaValueStyle.Cycler))
        assertEquals("two strings are not a switch", NovaValueStyle.Segmented, resolveNovaValueStyle(options("On", "Off")))
    }

    @Test
    fun theStepperAcceleratesAfterEightRepeatsAndAOpensTheExactPage() {
        var value by mutableIntStateOf(0)
        var exact = 0
        val keys = rule.setPanelContent {
            NovaStepperRow(
                title = "Bitrate",
                value = value,
                range = 0..1000,
                step = 1,
                format = { "$it Mbps" },
                onChange = { value = it },
                onExact = { exact++ },
                modifier = Modifier.testTag("stepper"),
            )
        }
        rule.onNodeWithTag("stepper").requestFocus()
        rule.waitForIdle()

        keys.down(NovaTestKeys.RIGHT)
        (1..8).forEach { keys.down(NovaTestKeys.RIGHT, repeat = it) }
        assertEquals("the press and eight repeats move a step each", 9, value)
        keys.down(NovaTestKeys.RIGHT, repeat = 9)
        assertEquals("after eight repeats a press moves five steps", 14, value)
        keys.up(NovaTestKeys.RIGHT)
        rule.onNodeWithTag("stepper").assertIsFocused()

        keys.press(NovaTestKeys.LEFT)
        assertEquals(13, value)

        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, exact)
    }

    @Test
    fun theStepperStopsAtTheEndsOfItsRange() {
        var value by mutableIntStateOf(98)
        val keys = rule.setPanelContent {
            NovaStepperRow(
                title = "Opacity",
                value = value,
                range = 0..100,
                step = 5,
                format = { "$it%" },
                onChange = { value = it },
                modifier = Modifier.testTag("stepper"),
            )
        }
        rule.onNodeWithTag("stepper").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.RIGHT)
        assertEquals(100, value)
    }

    private companion object {
        val NARROW_ROW = 80.dp
        val RAIL_WIDTH = 120.dp
    }
}
