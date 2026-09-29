package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applying a theme recreates the host list, and the recreate reset the flag that says the
 * library was already opened this visit: the RP6 was thrown into the Library, and the Shield
 * flashed "Computer is offline" for an offline preferred host. The flag survives the recreate,
 * focus comes back to Theme, and no Toast floats (R6).
 */
class PcViewThemeRecreateSourceGuardTest {
    private val source = File("src/main/java/com/papi/nova/PcView.kt").readText()

    @Test
    fun theRecreateKeepsTheVisitAndHandsFocusBackToTheme() {
        val apply = source.substringAfter("private fun applyThemeSelection(theme: String) {").substringBefore("\n    }\n")
        assertFalse("no floating Toast on apply", apply.contains("Toast.makeText"))
        assertTrue(apply.contains("returnFocusToTheme = true") && apply.contains("recreate()"))
        assertTrue(source.contains("outState.putBoolean(STATE_AUTO_NAVIGATED, autoNavigated)"))
        assertTrue(source.contains("autoNavigated = savedInstanceState?.getBoolean(STATE_AUTO_NAVIGATED, false) ?: false"))
        assertTrue(source.contains("findViewById<View>(R.id.actionTheme)?.requestFocus()"))
    }
}
