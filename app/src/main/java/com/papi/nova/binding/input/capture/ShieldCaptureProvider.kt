package com.papi.nova.binding.input.capture

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class ShieldCaptureProvider(private val context: Context) : InputCaptureProvider() {
    private fun setCursorVisibility(visible: Boolean) {
        try {
            methodSetCursorVisibility?.invoke(
                context.getSystemService(Context.INPUT_SERVICE),
                visible,
            )
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        } catch (e: InvocationTargetException) {
            e.printStackTrace()
        }
    }

    override fun showCursor() {
        super.showCursor()
        setCursorVisibility(true)
    }

    override fun hideCursor() {
        super.hideCursor()
        setCursorVisibility(false)
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent?): Boolean {
        val motionEvent = event ?: return false

        return motionEvent.pointerCount == 1 &&
            motionEvent.actionIndex == 0 &&
            motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    override fun getRelativeAxisX(event: MotionEvent?, pointerIndex: Int): Float =
        event?.getAxisValue(AXIS_RELATIVE_X) ?: 0f

    override fun getRelativeAxisX(event: MotionEvent?): Float =
        event?.getAxisValue(AXIS_RELATIVE_X) ?: 0f

    override fun getRelativeAxisY(event: MotionEvent?): Float =
        event?.getAxisValue(AXIS_RELATIVE_Y) ?: 0f

    companion object {
        private var nvExtensionSupported = false
        private var methodSetCursorVisibility: Method? = null
        private var AXIS_RELATIVE_X = 0
        private var AXIS_RELATIVE_Y = 0

        init {
            try {
                methodSetCursorVisibility =
                    InputManager::class.java.getMethod("setCursorVisibility", java.lang.Boolean.TYPE)

                val fieldRelX = MotionEvent::class.java.getField("AXIS_RELATIVE_X")
                val fieldRelY = MotionEvent::class.java.getField("AXIS_RELATIVE_Y")

                AXIS_RELATIVE_X = fieldRelX.get(null) as Int
                AXIS_RELATIVE_Y = fieldRelY.get(null) as Int

                nvExtensionSupported = true
            } catch (e: Exception) {
                nvExtensionSupported = false
            }
        }

        @JvmStatic
        fun isCaptureProviderSupported(): Boolean = nvExtensionSupported
    }
}
