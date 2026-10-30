package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** About's version line, five presses and the labelled panel, by every input (#424). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w640dp-h600dp-land")
class NovaSecretCodePanelComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private val taps = NovaVersionLineTaps()
    private val redeemed = mutableListOf<NovaEasterEggs.Code>()
    private var clock = 1_000L
    private val version = "Version 1.5.0"
    private val label get() = rule.activity.getString(R.string.nova_egg_enter_code)

    private fun open(): NovaTestKeys {
        panel.open(
            NovaCommonPage.Notice(
                key = "about",
                title = "About",
                message = version,
                closeLabel = "Close",
                onMessagePress = {
                    if (taps.press(clock)) {
                        panel.push(NovaEasterEggs.codePage(rule.activity) { redeemed += it })
                    }
                },
            ),
        )
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { }
            }
        }
        rule.waitForIdle()
        rule.frames(16)
        return keys
    }

    private fun tap(times: Int) = repeat(times) {
        clock += 300L
        rule.onNodeWithText(version).performClick()
        rule.frames(2)
    }

    private fun enter(code: String) {
        val field = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performClick()
        rule.frames(2)
        field.performTextReplacement(code)
        rule.frames(2)
        rule.onNodeWithText(rule.activity.getString(R.string.nova_egg_enter_code_submit)).performClick()
        rule.frames(16)
    }

    @Test fun fourPressesOpenNothing() {
        open()
        tap(4)
        assertEquals(1, panel.depth)
    }

    @Test fun theFifthTapOpensTheLabelledField() {
        open()
        tap(5)
        assertEquals(2, panel.depth)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertExists()
        // What TalkBack reads for the field, and what the panel is titled.
        rule.onNodeWithContentDescription(label).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, label)).assertExists()
    }

    @Test fun aControllerReachesTheLineAndOpensItWithA() {
        val keys = open()
        rule.onNodeWithText(version).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText(version).assertIsFocused()
        repeat(5) {
            clock += 300L
            keys.press(NovaTestKeys.CENTER)
            rule.frames(2)
        }
        rule.frames(16)
        assertEquals(2, panel.depth)
        // Focus lands in the field, so TalkBack reads its label as the panel opens.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsFocused()
    }

    @Test fun theClosesFocusStaysSafeWhenTheAboutPageOpens() {
        open()
        rule.onNodeWithText("Close").assertIsFocused()
    }

    @Test fun aRightCodeIsRedeemedAndThePanelLeaves() {
        open()
        tap(5)
        rule.frames(16)
        enter("supernova")
        assertEquals(listOf(NovaEasterEggs.Code.SUPERNOVA), redeemed)
        assertEquals(1, panel.depth)
    }

    @Test fun anUnknownCodeDoesNothingQuietly() {
        open()
        tap(5)
        rule.frames(16)
        enter("MILKYWAY")
        assertEquals(emptyList<NovaEasterEggs.Code>(), redeemed)
        assertEquals(1, panel.depth)
        rule.onAllNodesWithText("MILKYWAY").assertCountEquals(0)
    }
}
