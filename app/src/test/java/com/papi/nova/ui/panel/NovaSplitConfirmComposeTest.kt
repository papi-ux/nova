package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSplitConfirmComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var ended = 0
    private val state = NovaSplitConfirmState()

    private fun setUp(): NovaTestKeys {
        val keys = rule.setPanelContent {
            Column(Modifier.padding(top = 200.dp)) {
                Box(Modifier.size(48.dp).testTag("other").focusable())
                NovaSplitConfirm(
                    label = "End session",
                    confirmLabel = "End",
                    onConfirm = { ended++ },
                    consequence = "The game closes on the host.",
                    state = state,
                )
            }
        }
        rule.onNodeWithText("End session").requestFocus()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        return keys
    }

    private fun frames(count: Int = 4) = rule.frames(count)

    @Test
    fun aArmsAndStayTakesFocus() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithText("The game closes on the host.").assertExists()
    }

    @Test
    fun aOnStayDisarmsAndRefocusesTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        keys.press(NovaTestKeys.CENTER)
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
    }

    @Test
    fun mashedAPressesNeverConfirm() {
        val keys = setUp()
        repeat(3) {
            keys.press(NovaTestKeys.CENTER)
            frames(16)
        }
        rule.advance(1_000)
        repeat(3) {
            keys.press(NovaTestKeys.CENTER)
            frames(16)
        }
        assertEquals(0, ended)
    }

    @Test
    fun aRightAConfirmsOnlyAfterTheGuard() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("End").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("inside 400ms the destructive half ignores A", 0, ended)
        assertTrue(state.armed)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        frames()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertEquals(1, ended)
        assertFalse(state.armed)
    }

    @Test
    fun bDisarmsAndRefocusesTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
        assertEquals(0, ended)
    }

    @Test
    fun aDisarmFromOutsideHandsFocusBackToTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        rule.onNodeWithText("Stay").assertIsFocused()

        // The companion deck's back disarms the hoisted state itself.
        rule.runOnIdle { state.disarm() }
        frames(16)

        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
    }

    @Test
    fun anArmedButtonSplitsInItsOwnSlotNotAcrossTheRow() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames(16)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithText("End").getUnclippedBoundsInRoot()
        val pair = end.right - stay.left
        val twoHalves = NovaPanelMetrics.SplitHalfMinWidth * 2 + NovaPanelMetrics.SplitGap
        assertTrue("a narrow button widens only to two 96dp halves, not the full row: $pair", abs((pair - twoHalves).value) <= 1f)
    }

    @Test
    fun aButtonThatFillsItsSlotRestsAndSplitsAcrossIt() {
        rule.setPanelContent {
            Row(Modifier.width(600.dp)) {
                NovaPanelButton(text = "Close", onClick = {}, modifier = Modifier.weight(1f))
                NovaSplitConfirm(
                    label = "End session",
                    confirmLabel = "End",
                    onConfirm = {},
                    state = state,
                    fillSlot = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val close = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        val rest = rule.onNodeWithText("End session").getUnclippedBoundsInRoot()
        assertEquals("the split is the button beside it, as wide", close.width.value, rest.width.value, 0.5f)
        assertEquals("and as tall", close.height.value, rest.height.value, 0.5f)

        rule.onNodeWithText("End session").performClick()
        rule.waitForIdle()
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithText("End").getUnclippedBoundsInRoot()
        assertEquals("armed, the pair spans its own slot", rest.left.value, stay.left.value, 0.5f)
        assertEquals(rest.right.value, end.right.value, 0.5f)
    }

    /**
     * Robolectric measures every character one pixel wide, so a word eight times as long stands in
     * for a real one at a television's type: "End Session" there needs more than a 96dp half.
     */
    private fun wide(word: String) = word.repeat(8)

    /** How [text] was laid out: the width it was given and the widths it needs. */
    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first()
    }

    /** [text] had the width to sit on one line. */
    private fun assertOneLine(text: String) {
        val paragraph = layoutOf(text).multiParagraph
        assertTrue(
            "\"$text\" needs ${paragraph.intrinsics.maxIntrinsicWidth}px for one line and was given ${paragraph.width}px",
            paragraph.width >= paragraph.intrinsics.maxIntrinsicWidth,
        )
    }

    /** [text] had at least the width of its longest word, so it can only break between words. */
    private fun assertWholeWords(text: String) {
        val paragraph = layoutOf(text).multiParagraph
        assertTrue(
            "\"$text\" needs ${paragraph.intrinsics.minIntrinsicWidth}px for its longest word and was given ${paragraph.width}px",
            paragraph.width >= paragraph.intrinsics.minIntrinsicWidth,
        )
    }

    private fun armInABox(width: Int, confirmLabel: String): NovaTestKeys {
        val keys = rule.setPanelContent {
            Box(Modifier.width(width.dp)) {
                NovaSplitConfirm(
                    label = "End session",
                    confirmLabel = confirmLabel,
                    onConfirm = { ended++ },
                    state = state,
                )
            }
        }
        rule.onNodeWithText("End session").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        return keys
    }

    @Test
    fun anArmedButtonGrowsIntoTheRoomBesideItSoEachLabelStaysOnOneLine() {
        // The game page's End Session on a television: a short button with the rest of its row free.
        val end = "End ${wide("Session")}"
        armInABox(width = 600, confirmLabel = end)

        assertOneLine("Stay")
        assertOneLine(end)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText(end).getUnclippedBoundsInRoot()
        assertEquals("side by side", stay.top.value, action.top.value, 0.5f)
        assertTrue("Stay keeps a 96dp half: ${stay.width}", stay.width >= NovaPanelMetrics.SplitHalfMinWidth - 0.5.dp)
        assertTrue("the pair stays inside its row: ${action.right}", action.right <= 600.dp + 0.5.dp)
    }

    @Test
    fun aPairWithNoRoomToGrowBreaksItsLabelOnlyBetweenWords() {
        val end = "${wide("Close")} ${wide("Session")}"
        armInABox(width = 160, confirmLabel = end)

        assertOneLine("Stay")
        assertWholeWords(end)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText(end).getUnclippedBoundsInRoot()
        assertEquals("still side by side", stay.top.value, action.top.value, 0.5f)
        assertTrue("inside the slot: ${action.right}", action.right <= 160.dp + 0.5.dp)
    }

    @Test
    fun aButtonThatFillsANarrowSlotBreaksItsLabelOnlyBetweenWords() {
        val end = "${wide("Close")} ${wide("Session")}"
        rule.setPanelContent {
            Row(Modifier.width(300.dp)) {
                NovaPanelButton(text = "Close", onClick = {}, modifier = Modifier.weight(1f))
                NovaSplitConfirm(
                    label = "End session",
                    confirmLabel = end,
                    onConfirm = {},
                    state = state,
                    fillSlot = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        rule.onNodeWithText("End session").performClick()
        rule.waitForIdle()

        assertOneLine("Stay")
        assertWholeWords(end)
        val close = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText(end).getUnclippedBoundsInRoot()
        assertTrue("the pair keeps to its own slot", stay.left >= close.right)
        assertEquals(300f, action.right.value, 0.5f)
    }

    @Test
    fun aPairTooNarrowForBothLongestWordsStacksStayOverTheAction() {
        val end = "${wide("Close")} ${wide("Session")}"
        armInABox(width = 130, confirmLabel = end)

        assertOneLine("Stay")
        assertWholeWords(end)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText(end).getUnclippedBoundsInRoot()
        assertTrue("Stay over the action", action.top >= stay.bottom)
        assertEquals("each takes the slot's width", 130f, stay.width.value, 0.5f)
        assertEquals(130f, action.width.value, 0.5f)
        rule.onNodeWithText("Stay").assertIsFocused()
    }

    @Test
    fun aStackedPairStillConfirmsWithARightPress() {
        val end = "${wide("Close")} ${wide("Session")}"
        val keys = armInABox(width = 130, confirmLabel = end)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Stay").assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText(end).assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithText("Stay").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(end).assertIsFocused()

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        frames()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertEquals(1, ended)
    }

    @Test
    fun theHalvesShareTheirRoomEvenlyWhenBothLabelsFitInHalf() {
        assertArrayEquals(intArrayOf(97, 97), novaSplitHalfWidths(room = 194, stayLine = 40, actionLine = 90, stayWord = 40, actionWord = 50))
        assertArrayEquals("the wider label takes what it needs", intArrayOf(64, 130), novaSplitHalfWidths(room = 194, stayLine = 40, actionLine = 130, stayWord = 40, actionWord = 70))
        assertArrayEquals("past one line, the action breaks between words", intArrayOf(40, 110), novaSplitHalfWidths(room = 150, stayLine = 40, actionLine = 130, stayWord = 40, actionWord = 70))
        assertArrayEquals("Stay gives way before a word is cut", intArrayOf(30, 70), novaSplitHalfWidths(room = 100, stayLine = 40, actionLine = 130, stayWord = 30, actionWord = 70))
        assertNull("not even the longest words fit side by side", novaSplitHalfWidths(room = 90, stayLine = 40, actionLine = 130, stayWord = 30, actionWord = 70))
    }

    @Test
    fun armingTheLastRowOfAScrollingPageBringsItsConsequenceIntoView() {
        val keys = rule.setPanelContent {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(210.dp).testTag("list"),
            ) {
                items(3) { index -> Box(Modifier.fillMaxWidth().requiredHeight(44.dp).testTag("row-$index").focusable()) }
                item {
                    NovaSplitConfirm(
                        label = "Delete PC",
                        confirmLabel = "Delete",
                        onConfirm = {},
                        consequence = "The host is forgotten on this device.",
                        shape = NovaSplitShape.Row,
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        rule.onNodeWithText("Delete PC").requestFocus()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        keys.press(NovaTestKeys.CENTER)
        rule.advance(NovaPanelMetrics.SplitMillis.toLong() + 100)
        frames(16)

        assertTrue(state.armed)
        val list = rule.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val line = rule.onNodeWithText("The host is forgotten on this device.").getUnclippedBoundsInRoot()
        assertTrue("armed, its warning is on screen: ${line.bottom} in ${list.bottom}", line.bottom <= list.bottom + 0.5.dp)
        rule.onNodeWithText("Stay").assertIsFocused()
    }

    @Test
    fun aTouchInsideThePairKeepsItArmed() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        val inside = rule.onNodeWithText("Stay").fetchSemanticsNode().boundsInWindow.center
        val now = SystemClock.uptimeMillis()
        val touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, inside.x, inside.y, 0)
        rule.runOnUiThread { NovaSplitConfirmRegistry.onTouch(rule.activity.window.decorView, touch) }
        frames()
        assertTrue(state.armed)
        touch.recycle()
    }

    @Test
    fun aRecycledViewShowingAnotherItemStartsDisarmed() {
        var view: ComposeView? = null
        rule.setPanelContent { AndroidView(factory = { context -> ComposeView(context).also { view = it } }) }
        rule.runOnUiThread { view!!.setNovaSplitConfirm("Delete profile", "Delete", itemKey = "handheld") {} }
        rule.onNodeWithText("Delete profile").performClick()
        rule.onNodeWithText("Stay").assertExists()

        rule.runOnUiThread { view!!.setNovaSplitConfirm("Delete profile", "Delete", itemKey = "living room") {} }
        rule.waitForIdle()

        rule.onNodeWithText("Stay").assertDoesNotExist()
        rule.onNodeWithText("Delete profile").assertExists()
    }

    @Test
    fun focusLeavingThePairDisarms() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        rule.onNodeWithTag("other").requestFocus()
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithTag("other").assertIsFocused()
    }

    @Test
    fun aTouchDoubleTapNeverConfirms() {
        setUp()
        rule.onNodeWithText("End session").performClick()
        frames()
        assertTrue(state.armed)
        rule.onNodeWithText("End").performClick()
        frames()
        assertEquals(0, ended)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        frames()
        rule.onNodeWithText("End").performClick()
        frames()
        assertEquals("a deliberate second tap after the guard ends it", 1, ended)
    }

    @Test
    fun aTouchOutsideThePairDisarms() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        rule.runOnUiThread { NovaSplitConfirmRegistry.onTouch(rule.activity.window.decorView, outside) }
        frames()
        assertFalse(state.armed)
        outside.recycle()
    }

    @Test
    fun aWindowWithTheInstalledFeedDisarmsOnATouchOutside() {
        val keys = setUp()
        rule.runOnUiThread {
            // A plain Activity window, such as the companion deck's, feeds the registry this way.
            NovaSplitConfirmRegistry.install(rule.activity.window)
            NovaSplitConfirmRegistry.install(rule.activity.window)
        }
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        rule.runOnUiThread { rule.activity.window.callback.dispatchTouchEvent(outside) }
        frames()
        assertFalse(state.armed)
        outside.recycle()
    }
}
