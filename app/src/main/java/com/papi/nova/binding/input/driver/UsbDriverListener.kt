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
}
