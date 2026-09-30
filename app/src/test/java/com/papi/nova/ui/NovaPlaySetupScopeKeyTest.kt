package com.papi.nova.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holding Y flipped Play Setup between This Game and Every Game for as long as it was held, because
 * the flip ran on every key down, repeats included (C03). It runs once, on the release of a press
 * that began while Play Setup had Y.
 */
class NovaPlaySetupScopeKeyTest {
    private val y = KeyEvent.KEYCODE_BUTTON_Y

    @Test
    fun aHeldYFlipsOnceOnRelease() {
        val key = NovaPlaySetupScopeKey()
        var flips = 0
        assertTrue(key.down(y, 0, claims = true))
        repeat(20) { repeat -> assertTrue("repeats are taken, and flip nothing", key.down(y, repeat + 1, claims = true)) }
        if (key.flipsOnUp(y, canceled = false, claims = true)) flips++
        assertEquals(1, flips)
        assertFalse("a second release with no press flips nothing", key.flipsOnUp(y, canceled = false, claims = true))
    }

    @Test
    fun aYPlaySetupDoesNotHaveIsLeftAlone() {
        val key = NovaPlaySetupScopeKey()
        assertFalse(key.down(y, 0, claims = false))
        assertFalse("a press that began elsewhere does not flip on release", key.flipsOnUp(y, canceled = false, claims = true))
        assertFalse(key.down(KeyEvent.KEYCODE_BUTTON_X, 0, claims = true))
    }

    @Test
    fun aCancelledYOrOneReleasedAfterThePanelWentFlipsNothing() {
        val key = NovaPlaySetupScopeKey()
        key.down(y, 0, claims = true)
        assertFalse(key.flipsOnUp(y, canceled = true, claims = true))
        key.down(y, 0, claims = true)
        assertFalse(key.flipsOnUp(y, canceled = false, claims = false))
    }

    @Test
    fun theGameDetailWindowFlipsOnKeyUpNotOnEveryKeyDown() {
        val source = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val down = source.substringAfter("override fun onKeyDown(").substringBefore("override fun onKeyUp(")
        val up = source.substringAfter("override fun onKeyUp(").substringBefore("private fun selectPlaySetupScope(")
        assertFalse("a key down, repeats included, must not flip the scope", down.contains("selectPlaySetupScope("))
        assertTrue(down.contains("playSetupScopeKey.down("))
        assertTrue(up.contains("playSetupScopeKey.flipsOnUp(") && up.contains("selectPlaySetupScope("))
    }
}
