package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library holds a Steam Controller natively only while it is resumed (#356). */
class NovaLibraryNativeControllerSourceTest {
    private val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
    private val input = File("src/main/java/com/papi/nova/binding/input/NativeControllerLibraryInput.kt").readText()

    private fun body(source: String, start: String): String {
        val from = source.indexOf(start)
        assertTrue("Missing source marker: $start", from >= 0)
        return source.substring(from, source.indexOf("\n    }\n", from))
    }

    @Test fun theLibraryTakesTheControllerOnResumeAndGivesItBackFirstOnPause() {
        val resume = body(library, "override fun onResume()")
        assertTrue(resume.indexOf("nativeControllerInput.resume()") > resume.indexOf("if (recreateForThemeChangeIfNeeded()) return"))
        val pause = body(library, "override fun onPause()")
        assertTrue(pause.indexOf("nativeControllerInput.pause()") in 0 until pause.indexOf("cancelPendingSpaceOpen()"))
    }

    @Test fun onlyAnOwnedControllersOwnKeyboardAndMouseAreFilteredFirst() {
        for (dispatch in listOf("override fun dispatchKeyEvent(", "override fun dispatchGenericMotionEvent(", "override fun dispatchTouchEvent(")) {
            val code = body(library, dispatch).lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            assertTrue(dispatch, code.first() == "if (NativeControllerLibraryInput.isOwnedLizard(event)) return true")
        }
    }

    @Test fun theLibraryNeverAsksForAccessStartsBluetoothOrStopsAnotherOwner() {
        assertTrue(input.contains("promptForPermission = false, steamControllersOnly = true"))
        val config = input.substringAfter("fun libraryConfig(").substringBefore("fun isOwnedLizard(")
        assertTrue(config.contains("usbDriver = saved.usbDriver"))
        assertFalse(config.contains("steamControllerBluetooth"))
        assertTrue(body(input, "fun pause()").contains("driver?.stop(this)"))
        assertFalse(input.contains("requestPermission"))
    }
}
