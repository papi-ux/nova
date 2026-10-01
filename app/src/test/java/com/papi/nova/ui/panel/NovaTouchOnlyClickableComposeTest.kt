package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Touch/TalkBack siblings share Nova activation without acquiring another controller stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaTouchOnlyClickableComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // The wrapper excludes both of its targets. The baseline outer focusProperties failed
    // navigation because its exclusion could not cross novaClickable's first focus target.
    private fun Modifier.touchOnly(onClick: () -> Unit): Modifier =
        novaClickable(controllerFocusable = false, onClick = onClick)

    @Test fun touchOnlyActionsAreSkippedByControllerNavigation() {
        val keys = rule.setPanelContent {
            Column {
                Box(Modifier.size(48.dp).testTag("number-row").novaClickable {})
                Box(Modifier.size(48.dp).testTag("touch-step").touchOnly {})
                Box(Modifier.size(48.dp).testTag("next-row").novaClickable {})
            }
        }
        rule.onNodeWithTag("number-row").requestFocus()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithTag("next-row").assertIsFocused()
        keys.press(NovaTestKeys.UP)
        rule.onNodeWithTag("number-row").assertIsFocused()
    }

    @Test fun touchOrTalkBackLeavesParentActivationAndOriginReleaseUntouched() {
        var parent = 0
        var step = 0
        val focus = FocusRequester()
        val keys = rule.setPanelContent {
            Column {
                Box(Modifier.size(96.dp).testTag("number-row").focusRequester(focus).novaClickable { parent++ }) {
                    Box(Modifier.size(48.dp).testTag("touch-step").touchOnly { step++; focus.requestFocus() })
                }
                Box(Modifier.size(48.dp).testTag("next-row").novaClickable { parent += 100 })
            }
        }
        rule.onNodeWithTag("number-row").requestFocus()
        rule.onNodeWithTag("touch-step").performClick()
        rule.onNodeWithTag("number-row").assertIsFocused()
        assertEquals(1, step)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A belongs to the focused parent, not its touch-only content", 1, parent)
        assertEquals(1, step)
        keys.down(NovaTestKeys.CENTER)
        rule.onNodeWithTag("next-row").requestFocus()
        keys.up(NovaTestKeys.CENTER)
        assertEquals("a release elsewhere completes neither origin nor recipient", 1, parent)
        assertEquals(1, step)
    }
}
