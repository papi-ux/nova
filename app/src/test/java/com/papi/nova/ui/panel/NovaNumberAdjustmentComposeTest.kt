package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual shared numeric page: gestures, controller keys and accessibility all edit one draft. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w600dp-h900dp")
class NovaNumberAdjustmentComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val state = NovaPanelState()
    private val saves = mutableListOf<Int>()
    private val previews = mutableListOf<Int>()

    private fun page(value: Int = 20) = NovaCommonPage.Slider(
        key = "number", title = "Adjust number", value = value, range = 0..150, step = 5,
        format = { "$it units" }, onPreview = { previews += it }, onSave = { saves += it },
    )

    private fun show(value: Int = 20, short: Boolean = false): NovaTestKeys {
        state.open(TestPage("owner"))
        state.push(page(value))
        return rule.setPanelContent {
            Box(if (short) Modifier.height(200.dp) else Modifier) {
                NovaPageStackHost(state, onCloseRequest = {}) { owner ->
                    NovaRow("Owner ${owner.key}", onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
    }

    private fun track() = rule.onNodeWithTag("nova-number-track", useUnmergedTree = true)
    private fun tap(fraction: Float) = track().performTouchInput {
        click(Offset((width * fraction).coerceIn(.5f, width - .5f), centerY))
    }

    @Test fun aDragSurvivesDraftRecompositionUntilTheFingerIsReleased() {
        show()
        track().performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .4f, centerY), 80) }
        rule.waitForIdle()
        rule.onNodeWithText("60 units").assertExists()
        assertTrue("moving the draft does not save", saves.isEmpty())
        // A second event batch reaches the same down pointer after the first change recomposed.
        track().performTouchInput { moveTo(Offset(width * .8f, centerY), 80); up() }
        rule.waitForIdle()
        rule.onNodeWithText("120 units").assertExists()
        assertTrue(saves.isEmpty())
    }

    @Test fun touchThenControllerEditsTheSameDraftAndSavesOnce() {
        val keys = show()
        tap(.8f)
        rule.onNodeWithText("120 units").assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithText("115 units").assertIsFocused()
        assertTrue(saves.isEmpty())
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Save").assertIsFocused()
        keys.pressTwiceInOneFrame(NovaTestKeys.CENTER)
        assertEquals(listOf(115), saves)
    }

    @Test fun visibleTouchStepsHave48DpTargetsAndClampAtBothBounds() {
        PreferenceManager.getDefaultSharedPreferences(rule.activity).edit().putString("nova_control_size", "compact").commit()
        show(145)
        val increase = rule.onNodeWithContentDescription("Increase value")
        val decrease = rule.onNodeWithContentDescription("Decrease value")
        for (button in listOf(increase, decrease)) {
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue("unscaled finger width: $bounds", bounds.width >= 48.dp)
            assertTrue("unscaled finger height: $bounds", bounds.height >= 48.dp)
        }
        repeat(2) { increase.performTouchInput { click(center) } }
        rule.onNodeWithText("150 units").assertExists()
        decrease.performTouchInput { click(center) }
        rule.onNodeWithText("145 units").assertExists()
        tap(0f)
        decrease.performTouchInput { click(center) }
        rule.onNodeWithText("0 units").assertExists()
        increase.performTouchInput { click(center) }
        rule.onNodeWithText("5 units").assertExists()
        assertTrue(saves.isEmpty())
    }

    @Test fun bothTouchEndpointsAreReachableAndBackDiscardsTheDraft() {
        val keys = show()
        tap(1f)
        rule.onNodeWithText("150 units").assertExists()
        tap(0f)
        rule.onNodeWithText("0 units").assertExists()
        keys.back()
        assertEquals(1, state.depth)
        assertTrue(saves.isEmpty())
        rule.runOnUiThread { state.push(page()) }
        rule.waitForIdle()
        rule.onNodeWithText("20 units").assertIsFocused()
    }

    @Test fun accessibilityRangeAndIncrementActionsOnlyEditTheDraft() {
        show()
        val slider = rule.onNodeWithTag("nova-number-slider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(73f) }
        rule.onNodeWithText("75 units").assertExists()
        val actions = slider.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Decrease value", "Increase value"), actions.map { it.label })
        rule.runOnUiThread { actions[1].action() }
        rule.waitForIdle()
        rule.onNodeWithText("80 units").assertExists()
        assertTrue(saves.isEmpty())
        rule.onNodeWithText("Save").performTouchInput { click(center) }
        assertEquals(listOf(80), saves)
    }

    @Test fun aVerticalSwipeScrollsThePageWithoutChangingTheDraft() {
        show(short = true)
        val before = rule.onNodeWithText("Save").getUnclippedBoundsInRoot().top
        track().performTouchInput {
            down(center)
            moveTo(Offset(centerX + 1f, centerY - 90f), 150)
            up()
        }
        rule.waitForIdle()
        val after = rule.onNodeWithText("Save").getUnclippedBoundsInRoot().top
        assertTrue("the page scrolls: $before -> $after", after < before)
        assertEquals("vertical movement never adjusts the value", listOf(20), previews)
        assertTrue(saves.isEmpty())
    }
}
