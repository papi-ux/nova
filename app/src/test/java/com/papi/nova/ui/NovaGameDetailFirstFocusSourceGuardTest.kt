package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The game-detail Overview must let Play hold first focus reliably.
 *
 * When the launch profile is already cached, playEnabled is true on the very first
 * composition, so the focus effect runs before Play has been placed. A bare request
 * lands on nothing, runCatching swallows it, and framework default focus falls to the
 * first focusable above Play -- the launch-profile row. Observed on device (both the
 * debug and benchmark builds): the first A-press on a game's detail screen activated the
 * stats/"more details" row instead of the highlighted Launch button.
 *
 * The request must settle first, exactly the way the shared novaHoldsFirstFocus helper
 * does, so it lands on Play once layout has happened. This guards that invariant at the
 * source, since the timing is not reachable from a JVM unit test.
 */
class NovaGameDetailFirstFocusSourceGuardTest {
    @Test
    fun playFocusRequestSettlesBeforeAskingSoItLandsOnPlayNotTheRowAbove() {
        val source = File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText()

        val effectStart = source.indexOf("LaunchedEffect(playFocusable)")
        assertTrue(
            "Overview must request Play focus in a LaunchedEffect keyed on playFocusable.",
            effectStart >= 0,
        )
        val requestIndex = source.indexOf("playFocusRequester.requestFocus()", effectStart)
        assertTrue(
            "Overview must request focus on playFocusRequester.",
            requestIndex >= 0,
        )
        val settleIndex = source.indexOf("delay(NOVA_FIRST_FOCUS_SETTLE_MS)", effectStart)
        assertTrue(
            "Play focus must settle (delay(NOVA_FIRST_FOCUS_SETTLE_MS)) before requesting it. Without " +
                "the settle, a cached profile makes the request fire before Play is placed, and first " +
                "focus falls to the launch-profile row above Play.",
            settleIndex in effectStart until requestIndex,
        )
    }
}
