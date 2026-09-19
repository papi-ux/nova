package com.papi.nova.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import kotlin.math.abs

/**
 * Retroid handhelds report their D-pad as a joystick hat, not as D-pad keys. Android
 * turns a hat into D-pad keys inside the app, and on Android 13 those keys never take a
 * screen out of touch mode, so after any touch the D-pad did nothing until a face button
 * was pressed. A hat press takes the screen out of touch mode here instead, and like the
 * first D-pad key after a touch it only brings focus back: that press is spent.
 */
object NovaControllerTouchMode {
    private const val HAT_PRESS = 0.5f

    /** A D-pad press from a controller whose D-pad is a hat. */
    fun isHatPress(source: Int, action: Int, hatX: Float, hatY: Float): Boolean =
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            action == MotionEvent.ACTION_MOVE &&
            (abs(hatX) >= HAT_PRESS || abs(hatY) >= HAT_PRESS)

    /** True when the press was spent taking [window] out of touch mode. */
    fun leaveTouchMode(window: Window, event: MotionEvent): Boolean {
        val press = isHatPress(
            source = event.source,
            action = event.action,
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        if (!press) return false
        val decorView = window.decorView
        if (!decorView.isInTouchMode) return false
        decorView.requestFocusFromTouch()
        return true
    }
}
