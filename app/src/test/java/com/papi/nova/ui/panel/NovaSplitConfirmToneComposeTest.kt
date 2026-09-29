package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaSurfaceLook
import com.papi.nova.ui.compose.NovaSurfaceLookKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A split confirm's two tones (review finding 4). A destructive one rests in the destructive words
 * and confirms in red; a neutral one, for a setting such as Live Tuning, rests as a row and
 * confirms in the accent. Armed, both show one filled half, the confirm, and Stay rests beside it;
 * focus on either half takes the one accent ring every control has, and on the filled half the
 * fill stands off the ring so the ring reads on the accent too (in-game #14). Round 2 filled the
 * neutral confirm beside a Stay that filled under focus as well, two accent surfaces, and ringed
 * the focused confirm in its label colour. Both keep the guard: one A never confirms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSplitConfirmToneComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var colors: NovaComposeColors
    private lateinit var surfaces: NovaLibrarySurfaces
    private var confirmed = 0
    private val state = NovaSplitConfirmState()

    private fun setUp(tone: NovaSplitTone): NovaTestKeys {
        val keys = rule.setPanelContent {
            CompositionLocalProvider(LocalNovaComposeColors provides LocalNovaComposeColors.current.withDistinctRoles()) {
                colors = LocalNovaComposeColors.current
                surfaces = LocalNovaLibrarySurfaces.current
                Column {
                    Box(Modifier.size(48.dp).testTag("other").focusable())
                    NovaSplitConfirm(
                        label = "Live Tuning",
                        confirmLabel = "Turn Off",
                        onConfirm = { confirmed++ },
                        consequence = "Changes Polaris for every device.",
                        shape = NovaSplitShape.Row,
                        caption = "Steady.",
                        state = state,
                        tone = tone,
                    )
                }
            }
        }
        rule.onNodeWithTag("other").requestFocus()
        rule.waitForIdle()
        return keys
    }

    private fun labelColour(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    private fun look(text: String): NovaSurfaceLook = rule.onNodeWithText(text).fetchSemanticsNode().config[NovaSurfaceLookKey]

    private fun arm(keys: NovaTestKeys) {
        rule.onNodeWithText("Live Tuning").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
    }

    /** The armed pair, Stay focused and then the confirm: one fill, one accent ring, in [fill]. */
    private fun assertArmedPair(keys: NovaTestKeys, fill: Color, onFill: Color) {
        rule.onNodeWithText("Stay").assertIsFocused()
        assertEquals("Stay focused keeps its rest and takes the accent ring", NovaSurfaceLook(surfaces.control, surfaces.focusRing, false), look("Stay"))
        assertEquals("the confirm beside it is the one filled half, with no ring", NovaSurfaceLook(fill, Color.Unspecified, false), look("Turn Off"))
        assertEquals("in the fill's label colour", onFill, labelColour("Turn Off"))

        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        rule.onNodeWithText("Turn Off").assertIsFocused()
        assertEquals("the focused confirm keeps its fill and takes the same accent ring, stood off the fill", NovaSurfaceLook(fill, surfaces.focusRing, true), look("Turn Off"))
        assertEquals("Stay rests, unfilled and with no ring", NovaSurfaceLook(surfaces.control, Color.Unspecified, false), look("Stay"))
        assertEquals("one A never confirms", 0, confirmed)
    }

    @Test
    fun aNeutralSplitRestsAsARowAndConfirmsInTheAccent() {
        val keys = setUp(NovaSplitTone.Neutral)
        assertEquals("at rest its label is a row's", colors.textPrimary, labelColour("Live Tuning"))

        arm(keys)
        assertArmedPair(keys, fill = colors.accent, onFill = colors.onAccent)
    }

    @Test
    fun aDestructiveSplitKeepsItsRedAndTheSameRing() {
        val keys = setUp(NovaSplitTone.Destructive)
        assertEquals(colors.destructive, labelColour("Live Tuning"))

        arm(keys)
        assertArmedPair(keys, fill = colors.destructiveFill, onFill = colors.onDestructiveFill)
    }

    @Test
    fun aNeutralConfirmStillWaitsForTheGuard() {
        val keys = setUp(NovaSplitTone.Neutral)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Live Tuning").requestFocus()
        rule.frames(2)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("inside the guard A does nothing", 0, confirmed)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        rule.frames(4)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertEquals(1, confirmed)
    }
}

/**
 * The theme under test draws destructive words in the text colour, where its red would not read,
 * and puts the same ink on both fills; these tests tell the roles apart, so each role gets its own.
 */
internal fun NovaComposeColors.withDistinctRoles(): NovaComposeColors = copy(
    destructive = Color(0xFFE5484D),
    destructiveFill = Color(0xFFE5484D),
    onDestructiveFill = Color(0xFFFFFFFF),
    onAccent = Color(0xFF101010),
)
