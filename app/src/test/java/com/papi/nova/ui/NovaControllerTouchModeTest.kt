package com.papi.nova.ui

import android.view.InputDevice
import android.view.MotionEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaControllerTouchModeTest {
    private val joystick = InputDevice.SOURCE_JOYSTICK
    private val move = MotionEvent.ACTION_MOVE

    @Test
    fun aHatPushedAnyWayIsADirectionalPress() {
        assertTrue(NovaControllerTouchMode.isDirectionalPress(joystick, move, 0f, 1f, 0f, 0f))
        assertTrue(NovaControllerTouchMode.isDirectionalPress(joystick, move, 0f, -1f, 0f, 0f))
        assertTrue(NovaControllerTouchMode.isDirectionalPress(joystick, move, 1f, 0f, 0f, 0f))
        assertTrue(NovaControllerTouchMode.isDirectionalPress(joystick, move, -1f, 0f, 0f, 0f))
    }

    @Test
    fun theLeftStickPushedIsOneTooButItsDriftIsNot() {
        assertTrue("Android turns the left stick into D-pad keys as well", NovaControllerTouchMode.isDirectionalPress(joystick, move, 0f, 0f, 0f, 0.9f))
        assertFalse("a resting stick drifts a little and is not a press", NovaControllerTouchMode.isDirectionalPress(joystick, move, 0f, 0f, 0.12f, -0.08f))
    }

    @Test
    fun aReleasedHatOrAnotherSourceIsNot() {
        assertFalse("letting go of the D-pad is not a press", NovaControllerTouchMode.isDirectionalPress(joystick, move, 0f, 0f, 0f, 0f))
        assertFalse("a mouse is not a D-pad", NovaControllerTouchMode.isDirectionalPress(InputDevice.SOURCE_MOUSE, move, 0f, 1f, 0f, 0f))
        assertFalse(NovaControllerTouchMode.isDirectionalPress(joystick, MotionEvent.ACTION_HOVER_MOVE, 0f, 1f, 0f, 0f))
    }

    @Test
    fun novaScreensLeaveTouchModeOnAPressButTheStreamDoesNot() {
        val base = readSource("src/main/java/com/papi/nova/NovaActivity.kt")
        assertTrue(
            "a Retroid D-pad after a touch did nothing until A was pressed; every Nova screen routes a press through NovaControllerTouchMode first",
            base.contains("NovaControllerTouchMode.leaveTouchMode(window, event)") &&
                base.contains("override fun dispatchGenericMotionEvent"),
        )
        assertTrue(
            "in the stream the hat is controller input for the host, so the stream must never spend a press on focus",
            readSource("src/main/java/com/papi/nova/Game.kt").contains("override val hatPressLeavesTouchMode: Boolean = false"),
        )
    }

    @Test
    fun dialogWindowsGetTheSameHandling() {
        val windows = readSource("src/main/java/com/papi/nova/ui/NovaDialogWindows.kt")
        assertTrue(
            "a sheet or dialog is its own window, so after a touch the D-pad stayed dead inside it; adopting its window installs the handling",
            windows.contains("NovaControllerTouchMode.install(window)"),
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
