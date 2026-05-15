package com.papi.nova.binding.input.capture

import android.view.MotionEvent

abstract class InputCaptureProvider {
    protected var isCapturing = false
    protected var isCursorVisible = false

    open fun enableCapture() {
        isCapturing = true
        hideCursor()
    }

    open fun disableCapture() {
        isCapturing = false
        showCursor()
    }

    open fun destroy() = Unit

    open fun isCapturingEnabled(): Boolean = isCapturing

    open fun isCapturingActive(): Boolean = isCapturing

    open fun showCursor() {
        isCursorVisible = true
    }

    open fun hideCursor() {
        isCursorVisible = false
    }

    open fun eventHasRelativeMouseAxes(event: MotionEvent?): Boolean = false

    open fun getRelativeAxisX(event: MotionEvent?, pointerIndex: Int): Float = 0f

    open fun getRelativeAxisX(event: MotionEvent?): Float = getRelativeAxisX(event, 0)

    open fun getRelativeAxisY(event: MotionEvent?, pointerIndex: Int): Float = 0f

    open fun getRelativeAxisY(event: MotionEvent?): Float = getRelativeAxisY(event, 0)

    open fun onWindowFocusChanged(focusActive: Boolean) = Unit
}
