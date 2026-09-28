package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    fun theStateDescriptionIsTheCurrentLabel() {
        val (keys, _) = valueRow(ordered = false)
        rule.onNodeWithTag("row").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "M"))
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithTag("row").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "L"))
    }

    @Test
    fun segmentedMarksTheCurrentSegmentWithTheCheck() {
        valueRow(ordered = false, style = NovaValueStyle.Segmented)
        val marks = rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one mark, on the current segment only", 1, marks.size)
        val mark = rule.onNodeWithTag(NovaCurrentMarkTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val current = rule.onNodeWithText("M", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val next = rule.onNodeWithText("L", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the check sits just before the current label", mark.right <= current.left && mark.right > current.left - NovaPanelMetrics.SpaceSm)
        assertTrue("and not beside another", next.left > current.right)
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
}
