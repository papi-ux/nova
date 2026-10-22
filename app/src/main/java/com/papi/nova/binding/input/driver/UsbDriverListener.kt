package com.papi.nova.binding.input.driver

interface UsbDriverListener {
    fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float
    )

    fun reportControllerMotion(controllerId: Int, motionType: Byte, motionX: Float, motionY: Float, motionZ: Float)

    fun deviceRemoved(controller: AbstractController)

    fun deviceAdded(controller: AbstractController)

    fun reportControllerTouch(controllerId: Int, eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float) = Unit

    fun reportControllerMouse(controllerId: Int, deltaX: Short, deltaY: Short, buttons: Int, scroll: Short) = Unit

    fun reportControllerQuickMenu(controllerId: Int) = Unit

    /** Detach is cancellation, never a Guide tap or a shortcut-button release. */
    fun resetControllerState(controllerId: Int) = Unit
}
