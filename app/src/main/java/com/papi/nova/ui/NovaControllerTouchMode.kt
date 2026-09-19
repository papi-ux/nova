package com.papi.nova.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import kotlin.math.abs

/**
 * Retroid handhelds report their D-pad as a joystick hat, not as D-pad keys. Android
 * turns a hat, and the left stick, into D-pad keys inside the app, and on Android 13
 * those keys never take a screen out of touch mode, so after any touch the D-pad did
 * nothing until a face button was pressed. A press takes the screen out of touch mode
 * here instead, the way the first D-pad key after a touch does.
 */
object NovaControllerTouchMode {
    private const val PRESS = 0.5f

    /** The D-pad hat or the left stick pushed, on a game controller. */
    fun isDirectionalPress(
        source: Int,
        action: Int,
        hatX: Float,
        hatY: Float,
        stickX: Float,
        stickY: Float,
    ): Boolean =
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            action == MotionEvent.ACTION_MOVE &&
            listOf(hatX, hatY, stickX, stickY).any { abs(it) >= PRESS }

    /**
     * Takes [window] out of touch mode for a directional press. A view that still holds
     * focus keeps it and the press goes on to move from there; with nothing focused the
     * press is spent bringing focus back, and this returns true.
     */
    fun leaveTouchMode(window: Window, event: MotionEvent): Boolean {
        val press = isDirectionalPress(
            source = event.source,
            action = event.action,
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            stickX = event.getAxisValue(MotionEvent.AXIS_X),
            stickY = event.getAxisValue(MotionEvent.AXIS_Y),
        )
        if (!press) return false
        val decorView = window.decorView
        if (!decorView.isInTouchMode) return false
        val focused = decorView.findFocus()
        (focused ?: decorView).requestFocusFromTouch()
        return focused == null
    }

    /** Gives a dialog's window what NovaActivity does for a screen's own window. */
    fun install(window: Window) {
        val callback = window.callback ?: return
        if (callback is PressLeavesTouchMode) return
        window.callback = PressLeavesTouchMode(callback, window)
    }

    private class PressLeavesTouchMode(
        private val base: Window.Callback,
        private val window: Window,
    ) : Window.Callback by base {
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            leaveTouchMode(window, event) || base.dispatchGenericMotionEvent(event)
    }
}
