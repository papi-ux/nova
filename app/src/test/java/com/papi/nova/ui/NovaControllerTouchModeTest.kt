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

    @Test
    fun aHatPushedAnyWayIsADpadPress() {
        assertTrue(NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_MOVE, 0f, 1f))
        assertTrue(NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_MOVE, 0f, -1f))
        assertTrue(NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_MOVE, 1f, 0f))
        assertTrue(NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_MOVE, -1f, 0f))
    }

    @Test
    fun aReleasedHatOrAnotherSourceIsNot() {
        assertFalse("letting go of the D-pad is not a press", NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_MOVE, 0f, 0f))
        assertFalse("a mouse is not a D-pad", NovaControllerTouchMode.isHatPress(InputDevice.SOURCE_MOUSE, MotionEvent.ACTION_MOVE, 0f, 1f))
        assertFalse(NovaControllerTouchMode.isHatPress(joystick, MotionEvent.ACTION_HOVER_MOVE, 0f, 1f))
    }

    @Test
    fun novaScreensLeaveTouchModeOnAHatPressButTheStreamDoesNot() {
        val base = readSource("src/main/java/com/papi/nova/NovaActivity.kt")
        assertTrue(
            "a Retroid D-pad after a touch did nothing until A was pressed; every Nova screen routes a hat press through NovaControllerTouchMode first",
            base.contains("NovaControllerTouchMode.leaveTouchMode(window, event)") &&
                base.contains("override fun dispatchGenericMotionEvent"),
        )
        val stream = readSource("src/main/java/com/papi/nova/Game.kt")
        assertTrue(
            "in the stream the hat is controller input for the host, so the stream must never spend a press on focus",
            stream.contains("override val hatPressLeavesTouchMode") && stream.contains("= false"),
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
