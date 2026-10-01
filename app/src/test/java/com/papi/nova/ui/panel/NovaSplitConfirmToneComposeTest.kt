package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
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
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaSurfaceLook
import com.papi.nova.ui.compose.NovaSurfaceLookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A split confirm's two tones (review finding 4). A destructive one rests in the destructive words
 * and confirms in red; a neutral one, for a setting such as Live Tuning, rests as a row.
 *
 * Armed, a neutral split's halves both rest as tiles, the confirm's label in the accent, and only
 * the half with focus fills, in the accent, inside the one accent ring: fills and rings only ever
 * mean focus. Round 2 filled its confirm at rest beside a Stay that filled under focus, two accent
 * surfaces; round 3 kept the confirm's fill at rest and took Stay's focus fill away, in every
 * split. A destructive split keeps its confirm red at rest and under focus, separating an accent
 * ring that would read below 3:1 with the existing panel gap; its Stay fills as any control does. Each look
 * is read from the node that draws it. Both tones keep the guard: one A never confirms.
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
        rule.onNodeWithText("Stay").assertIsFocused()
    }

    private fun toConfirm(keys: NovaTestKeys) {
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        rule.onNodeWithText("Turn Off").assertIsFocused()
    }

    private val tile get() = NovaSurfaceLook(surfaces.control, Color.Unspecified, false)

    @Test
    fun aNeutralSplitFillsOnlyTheHalfWithFocus() {
        val keys = setUp(NovaSplitTone.Neutral)
        assertEquals("at rest its label is a row's", colors.textPrimary, labelColour("Live Tuning"))

        arm(keys)
        assertEquals("Stay has focus: it fills in the accent, inside the one accent ring", NovaSurfaceLook(colors.accent, surfaces.focusRing, true), look("Stay"))
        assertEquals("in the fill's label colour", colors.onAccent, labelColour("Stay"))
        assertEquals("the confirm beside it rests as a tile, with no fill and no ring", tile, look("Turn Off"))
        assertEquals("its label in the accent", colors.accentText, labelColour("Turn Off"))

        toConfirm(keys)
        assertEquals("the confirm has focus: it fills, inside the same accent ring", NovaSurfaceLook(colors.accent, surfaces.focusRing, true), look("Turn Off"))
        assertEquals(colors.onAccent, labelColour("Turn Off"))
        assertEquals("Stay rests as a tile again", tile, look("Stay"))
        assertEquals(colors.textPrimary, labelColour("Stay"))
        assertEquals("one A never confirms", 0, confirmed)
    }

    /** Keep the historic red fill, labels and focus roles; a low-contrast ring now needs the panel gap. */
    @Test
    fun aDestructiveSplitLooksAsItDidBeforeRoundThree() {
        val keys = setUp(NovaSplitTone.Destructive)
        assertEquals(colors.destructive, labelColour("Live Tuning"))

        arm(keys)
        assertEquals("Stay has focus: the focused control fill and the accent ring, as any control", NovaSurfaceLook(surfaces.selectedControl, surfaces.focusRing, false), look("Stay"))
        assertEquals(colors.textPrimary, labelColour("Stay"))
        assertEquals("the confirm beside it is red at rest, with no ring", NovaSurfaceLook(colors.destructiveFill, Color.Unspecified, false), look("Turn Off"))
        assertEquals("in the red fill's label colour", colors.onDestructiveFill, labelColour("Turn Off"))

        toConfirm(keys)
        assertTrue("This synthetic light ring and red fill are below 3:1", ColorUtils.calculateContrast(surfaces.focusRing.toArgb(), colors.destructiveFill.toArgb()) < 3.0)
        assertEquals("the confirm has focus: still red, with a gap separating the low-contrast accent ring", NovaSurfaceLook(colors.destructiveFill, surfaces.focusRing, true), look("Turn Off"))
        assertEquals(colors.onDestructiveFill, labelColour("Turn Off"))
        assertEquals("Stay rests as a tile", tile, look("Stay"))
        assertEquals("one A never confirms", 0, confirmed)
    }

    private fun setUpDirector(focusRing: Color? = null): NovaTestKeys {
        NovaThemeManager.setTheme(rule.activity, NovaThemeManager.THEME_DIRECTOR)
        val keys = rule.setPanelContent {
            val themedSurfaces = LocalNovaLibrarySurfaces.current
            CompositionLocalProvider(
                LocalNovaLibrarySurfaces provides if (focusRing == null) themedSurfaces else themedSurfaces.copy(focusRing = focusRing),
            ) {
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
                        tone = NovaSplitTone.Destructive,
                    )
                }
            }
        }
        rule.onNodeWithTag("other").requestFocus()
        rule.waitForIdle()
        return keys
    }

    @Test
    fun aDirectorArmedDestructiveConfirmKeepsItsWarmWhiteRingClearOfTheRedFill() {
        val keys = setUpDirector()
        assertEquals(Color(0xFFFFB7AC), colors.destructiveFill)
        assertEquals(Color(0xFFFFF5E8), surfaces.focusRing)
        assertEquals(colors.destructive, labelColour("Live Tuning"))
        assertTrue("This real palette needs ring separation", ColorUtils.calculateContrast(surfaces.focusRing.toArgb(), colors.destructiveFill.toArgb()) < 3.0)
        assertTrue("The exposed panel separates the warm-white ring", ColorUtils.calculateContrast(surfaces.focusRing.toArgb(), surfaces.panel.copy(alpha = 1f).toArgb()) >= 3.0)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Live Tuning").requestFocus()
        rule.frames(2)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        rule.onNodeWithText("Stay").assertIsFocused()
        assertEquals(NovaSurfaceLook(surfaces.selectedControl, surfaces.focusRing, false), look("Stay"))
        assertEquals(NovaSurfaceLook(colors.destructiveFill, Color.Unspecified, false), look("Turn Off"))
        assertEquals(colors.onDestructiveFill, labelColour("Turn Off"))
        val bounds = rule.onNodeWithText("Turn Off").fetchSemanticsNode().boundsInRoot

        toConfirm(keys)
        val focusedLook = look("Turn Off")
        assertEquals("Focus does not resize the armed action", bounds, rule.onNodeWithText("Turn Off").fetchSemanticsNode().boundsInRoot)
        assertEquals(colors.onDestructiveFill, labelColour("Turn Off"))
        assertEquals(tile, look("Stay"))
        keys.press(NovaTestKeys.CENTER)
        assertEquals("Inside the guard A does nothing", 0, confirmed)
        assertTrue(state.armed)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        rule.frames(4)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertEquals("The same action confirms exactly once after the guard", 1, confirmed)
        assertFalse(state.armed)
        assertEquals("The actual focused painter leaves its existing panel gap around the red fill", NovaSurfaceLook(colors.destructiveFill, surfaces.focusRing, true), focusedLook)
    }

    @Test
    fun anArmedDestructiveConfirmWithAContrastingRingKeepsTheFlushPainter() {
        val keys = setUpDirector(focusRing = Color.Black)
        assertTrue("This ring already contrasts with the fill", ColorUtils.calculateContrast(surfaces.focusRing.toArgb(), colors.destructiveFill.toArgb()) >= 3.0)
        arm(keys)
        assertEquals(NovaSurfaceLook(colors.destructiveFill, Color.Unspecified, false), look("Turn Off"))
        toConfirm(keys)
        assertEquals(NovaSurfaceLook(colors.destructiveFill, Color.Black, false), look("Turn Off"))
        assertEquals(colors.onDestructiveFill, labelColour("Turn Off"))
        assertEquals("One A never confirms", 0, confirmed)
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
