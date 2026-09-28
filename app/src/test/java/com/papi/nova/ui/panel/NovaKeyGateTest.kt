package com.papi.nova.ui.panel

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The gate's decisions, as a pure function of the key events it sees. */
class NovaKeyGateTest {
    private val gate = NovaKeyGate()

    private fun down(code: Int, repeat: Int = 0) = gate.decide(code, KeyEvent.ACTION_DOWN, repeat, canceled = false)

    private fun up(code: Int, canceled: Boolean = false) = gate.decide(code, KeyEvent.ACTION_UP, 0, canceled)

    @Test
    fun aDownBecomesACenterDown() {
        assertEquals(NovaKeyDecision.AsCenter, down(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun aRepeatIsSwallowed() {
        down(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(NovaKeyDecision.Swallow, down(KeyEvent.KEYCODE_BUTTON_A, repeat = 1))
        assertEquals(NovaKeyDecision.Swallow, down(KeyEvent.KEYCODE_BUTTON_A, repeat = 12))
    }

    @Test
    fun aLatchedUpBecomesACenterUpEvenWhenCancelled() {
        down(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(NovaKeyDecision.AsCenter, up(KeyEvent.KEYCODE_BUTTON_A))

        // The cancelled release still goes through, carrying its flag, so the element sees the
        // press end without acting on it.
        down(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(NovaKeyDecision.AsCenter, up(KeyEvent.KEYCODE_BUTTON_A, canceled = true))
    }

    @Test
    fun anAUpWithNoDownIsSwallowed() {
        assertEquals(NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_A))
        down(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("a second release has no press left to finish", NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun bDownIsSwallowedAndAMatchedReleaseMeansBack() {
        assertEquals(NovaKeyDecision.Swallow, down(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(NovaKeyDecision.Swallow, down(KeyEvent.KEYCODE_BUTTON_B, repeat = 3))
        assertEquals(NovaKeyDecision.Back, up(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun escapeFollowsTheSameRuleAsB() {
        assertEquals(NovaKeyDecision.Swallow, down(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(NovaKeyDecision.Back, up(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun aCancelledOrUnmatchedBReleaseIsSwallowed() {
        down(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_B, canceled = true))

        // The release of the press that opened a surface never reaches the surface as Back.
        assertEquals(NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_B))

        down(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("B pressed, Escape released: not the same press", NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun backAndEveryOtherKeyPass() {
        listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_ENTER,
        ).forEach { code ->
            assertEquals("down $code", NovaKeyDecision.Pass, down(code))
            assertEquals("up $code", NovaKeyDecision.Pass, up(code))
        }
    }

    @Test
    fun resetForgetsPressesInProgress() {
        down(KeyEvent.KEYCODE_BUTTON_A)
        down(KeyEvent.KEYCODE_BUTTON_B)
        gate.reset()
        assertEquals(NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(NovaKeyDecision.Swallow, up(KeyEvent.KEYCODE_BUTTON_B))
    }
}
