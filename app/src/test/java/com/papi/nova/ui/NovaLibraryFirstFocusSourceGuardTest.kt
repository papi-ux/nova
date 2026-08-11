package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library grid must land first focus on a poster card reliably, with the ring visible.
 *
 * The poster focus requester runs on the card's first composition, before the grid has
 * placed it. A bare request lands on nothing, and its false return was recorded as
 * "attempted", so it was never retried: a cold start showed no focus ring at all, and the
 * session's first d-pad press blind-scrolled the grid. Restores after a tap-opened detail
 * additionally never declared keyboard intent, so focus existed but no ring showed until a
 * press was spent revealing it.
 *
 * The request must settle first (the shared novaHoldsFirstFocus wait), declare keyboard
 * input mode, and retry a placed-too-late request across a few frames. This guards those
 * invariants at the source, since the timing is not reachable from a JVM unit test.
 */
class NovaLibraryFirstFocusSourceGuardTest {
    private fun effectSource(): Pair<String, Int> {
        val source = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val effectStart = source.indexOf("LaunchedEffect(restoreFocus, coldStartFocus)")
        assertTrue(
            "The poster focus requester must ask in a LaunchedEffect keyed on restoreFocus and coldStartFocus.",
            effectStart >= 0,
        )
        return source to effectStart
    }

    @Test
    fun posterFocusRequestSettlesBeforeAskingSoItLandsOnAPlacedCard() {
        val (source, effectStart) = effectSource()
        val requestIndex = source.indexOf("focusRequester.requestFocus()", effectStart)
        assertTrue("The effect must request focus on the poster's requester.", requestIndex >= 0)
        val settleIndex = source.indexOf("delay(NOVA_FIRST_FOCUS_SETTLE_MS)", effectStart)
        assertTrue(
            "Poster focus must settle (delay(NOVA_FIRST_FOCUS_SETTLE_MS)) before requesting it. Without " +
                "the settle, the request fires before the grid places the card and first focus is lost.",
            settleIndex in effectStart until requestIndex,
        )
    }

    @Test
    fun posterFocusDeclaresKeyboardIntentSoTheRingShowsWithoutSpendingAPress()  {
        val (source, effectStart) = effectSource()
        val requestIndex = source.indexOf("focusRequester.requestFocus()", effectStart)
        val inputModeIndex = source.indexOf("requestInputMode(InputMode.Keyboard)", effectStart)
        assertTrue(
            "Poster focus must declare keyboard input mode before requesting, for restores as well as " +
                "cold starts, or the ring stays hidden and the first press is consumed revealing it.",
            inputModeIndex in effectStart until requestIndex,
        )
    }

    @Test
    fun posterFocusRetriesAFailedRequestInsteadOfRecordingItAsAttempted() {
        val (source, effectStart) = effectSource()
        val effectEnd = source.indexOf("return focusRequester", effectStart)
        val body = source.substring(effectStart, effectEnd)
        assertTrue(
            "A false requestFocus() return means the card was not placed yet; the effect must retry " +
                "across frames (withFrameNanos + re-request) instead of surrendering the first press.",
            body.contains("while (!restoreAttempted") && body.contains("withFrameNanos"),
        )
    }
}
