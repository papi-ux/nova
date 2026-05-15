package com.papi.nova.binding.input.driver

import com.papi.nova.nvstream.jni.MoonBridge

abstract class AbstractController(
    private val deviceId: Int,
    private val listener: UsbDriverListener?,
    private val vendorId: Int,
    private val productId: Int
) {
    @JvmField
    protected var buttonFlags = 0
    @JvmField
    protected var supportedButtonFlags = 0
    @JvmField
    protected var leftTrigger = 0f
    @JvmField
    protected var rightTrigger = 0f
    @JvmField
    protected var rightStickX = 0f
    @JvmField
    protected var rightStickY = 0f
    @JvmField
    protected var leftStickX = 0f
    @JvmField
    protected var leftStickY = 0f
    @JvmField
    protected var gyroX = 0f
    @JvmField
    protected var gyroY = 0f
    @JvmField
    protected var gyroZ = 0f
    @JvmField
    protected var accelX = 0f
    @JvmField
    protected var accelY = 0f
    @JvmField
    protected var accelZ = 0f
    @JvmField
    protected var capabilities: Short = 0
    @JvmField
    protected var type: Byte = 0

    fun getControllerId(): Int = deviceId

    fun getVendorId(): Int = vendorId

    fun getProductId(): Int = productId

    fun getSupportedButtonFlags(): Int = supportedButtonFlags

    fun getCapabilities(): Short = capabilities

    fun getType(): Byte = type

    protected fun setButtonFlag(buttonFlag: Int, data: Int) {
        buttonFlags = if (data != 0) {
            buttonFlags or buttonFlag
        } else {
            buttonFlags and buttonFlag.inv()
        }
    }

    protected open fun reportInput() {
        listener!!.reportControllerState(
            deviceId,
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger
        )
    }

    protected fun reportMotion() {
        listener!!.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ)
        listener.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ)
    }

    abstract fun start(): Boolean

    abstract fun stop()

    abstract fun rumble(lowFreqMotor: Short, highFreqMotor: Short)

    abstract fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short)

    protected fun notifyDeviceRemoved() {
        listener!!.deviceRemoved(this)
    }

    protected fun notifyDeviceAdded() {
        listener!!.deviceAdded(this)
    }
}
