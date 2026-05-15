package com.papi.nova.binding.input.capture

import android.annotation.TargetApi
import android.app.Activity
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

@Suppress("DEPRECATION")
@TargetApi(Build.VERSION_CODES.O)
class AndroidNativePointerCaptureProvider(
    private val activity: Activity,
    private val targetView: View,
) : AndroidPointerIconCaptureProvider(activity, targetView), InputManager.InputDeviceListener {
    private val inputManager: InputManager = activity.getSystemService(InputManager::class.java)

    private var focusActive = true
    private var needsCapture = false

    private fun hasCaptureCompatibleInputDevice(): Boolean {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id)
            if (device != null && device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                return true
            }
        }

        return false
    }

    override fun enableCapture() {
        inputManager.registerInputDeviceListener(this, Handler())

        super.enableCapture()
    }

    override fun disableCapture() {
        inputManager.unregisterInputDeviceListener(this)

        super.disableCapture()
    }

    override fun destroy() {
        disableCapture()
    }

    override fun isCapturingActive(): Boolean = targetView.hasPointerCapture()

    override fun showCursor() {
        super.showCursor()

        needsCapture = false

        if (targetView.hasPointerCapture()) {
            targetView.releasePointerCapture()
        }
    }

    override fun hideCursor() {
        super.hideCursor()

        needsCapture = true

        if (!targetView.hasPointerCapture()) {
            if (focusActive && hasCaptureCompatibleInputDevice()) {
                targetView.requestPointerCapture()
            }
        }
    }

    override fun onWindowFocusChanged(focusActive: Boolean) {
        this.focusActive = focusActive

        if (focusActive && needsCapture && hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture()
        }
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent?): Boolean {
        val motionEvent = event ?: return false

        return motionEvent.pointerCount == 1 &&
            motionEvent.actionIndex == 0 &&
            (motionEvent.source and InputDevice.SOURCE_MOUSE_RELATIVE) != 0
    }

    override fun getRelativeAxisX(event: MotionEvent?, pointerIndex: Int): Float =
        event?.getAxisValue(MotionEvent.AXIS_RELATIVE_X, pointerIndex) ?: 0f

    override fun getRelativeAxisX(event: MotionEvent?): Float =
        event?.getAxisValue(MotionEvent.AXIS_RELATIVE_X) ?: 0f

    override fun getRelativeAxisY(event: MotionEvent?, pointerIndex: Int): Float =
        event?.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, pointerIndex) ?: 0f

    override fun getRelativeAxisY(event: MotionEvent?): Float =
        event?.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) ?: 0f

    override fun onInputDeviceAdded(deviceId: Int) {
        // If a capture compatible device was connected and we need capture,
        // try to request capture again.
        if (needsCapture && hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture()
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        // Do nothing
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // If our capture compatible input devices all go away, release pointer capture.
        if (!hasCaptureCompatibleInputDevice() && targetView.hasPointerCapture()) {
            targetView.releasePointerCapture()
        } else if (needsCapture && hasCaptureCompatibleInputDevice()) {
            // If a capture compatible device was connected and we need capture,
            // try to request capture again.
            targetView.requestPointerCapture()
        }
    }

    companion object {
        @JvmStatic
        fun isCaptureProviderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
