package com.papi.nova.binding.input

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.binding.input.driver.AbstractController
import com.papi.nova.binding.input.driver.UsbDriverListener
import com.papi.nova.binding.input.driver.UsbDriverService
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.GameGestures
import com.papi.nova.utils.Vector2d
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.cgutman.shieldcontrollerextensions.SceChargingState
import org.cgutman.shieldcontrollerextensions.SceConnectionType
import org.cgutman.shieldcontrollerextensions.SceManager

@Suppress("DEPRECATION")
class ControllerHandler(
    private val activityContext: Activity,
    private val conn: NvConnection,
    private val gestures: GameGestures,
    private val prefConfig: PreferenceConfiguration,
) : InputManager.InputDeviceListener, UsbDriverListener {
    private val inputVector = Vector2d()

    private val inputDeviceContexts = SparseArray<InputDeviceContext>()
    private val usbDeviceContexts = SparseArray<UsbDeviceContext>()

    private val stickDeadzone: Double
    private val defaultContext = InputDeviceContext()
    private val inputManager: InputManager =
        activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val deviceVibrator: Vibrator =
        activityContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val deviceVibratorManager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activityContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else {
            null
        }
    private val deviceSensorManager: SensorManager =
        activityContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sceManager = SceManager(activityContext)
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val buttonReleaseScheduler =
        ControllerButtonReleaseScheduler(mainThreadHandler, MINIMUM_BUTTON_DOWN_TIME_MS)
    private val backgroundHandlerThread = HandlerThread("ControllerHandler")
    private val backgroundThreadHandler: Handler
    private var hasGameController = false
    private var stopped = false
    private var currentControllers: Short = 0
    private var initialControllers: Short = 0

    init {
        backgroundHandlerThread.start()
        backgroundThreadHandler = Handler(backgroundHandlerThread.looper)

        sceManager.start()

        val deadzonePercentage = prefConfig.deadzonePercentage
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (hasJoystickAxes(dev) || isSteamControllerKeyboardMouseDevice(dev)) {
                hasGameController = true
            }
        }

        stickDeadzone = deadzonePercentage.toDouble() / 100.0

        defaultContext.leftStickXAxis = MotionEvent.AXIS_X
        defaultContext.leftStickYAxis = MotionEvent.AXIS_Y
        defaultContext.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        defaultContext.rightStickXAxis = MotionEvent.AXIS_Z
        defaultContext.rightStickYAxis = MotionEvent.AXIS_RZ
        defaultContext.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        defaultContext.leftTriggerAxis = MotionEvent.AXIS_BRAKE
        defaultContext.rightTriggerAxis = MotionEvent.AXIS_GAS
        defaultContext.hatXAxis = MotionEvent.AXIS_HAT_X
        defaultContext.hatYAxis = MotionEvent.AXIS_HAT_Y
        defaultContext.controllerNumber = 0
        defaultContext.assignedControllerNumber = true
        defaultContext.external = false
        defaultContext.ignoreBack = true

        initialControllers = getAttachedControllerMask(activityContext)
        inputManager.registerInputDeviceListener(this, null)
    }

    fun hasController(): Boolean = hasGameController

    override fun onInputDeviceAdded(deviceId: Int) {
        // Nothing happening here yet.
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val context = inputDeviceContexts[deviceId]
        if (context != null) {
            LimeLog.info("Removed controller: " + context.name + " (" + deviceId + ")")
            releaseControllerNumber(context)
            context.destroy()
            inputDeviceContexts.remove(deviceId)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        val existingContext = inputDeviceContexts[deviceId] ?: return

        LimeLog.info("Device changed: " + existingContext.name + " (" + deviceId + ")")

        val newContext = createInputDeviceContextForDevice(device)
        newContext.migrateContext(existingContext)
        inputDeviceContexts.put(deviceId, newContext)
    }

    fun stop() {
        if (stopped) {
            return
        }

        stopped = true
        buttonReleaseScheduler.cancelAll()
        inputManager.unregisterInputDeviceListener(this)

        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).destroy()
        }
        for (i in 0 until usbDeviceContexts.size()) {
            usbDeviceContexts.valueAt(i).destroy()
        }

        deviceVibrator.cancel()
    }

    fun destroy() {
        if (!stopped) {
            stop()
        }

        sceManager.stop()
        backgroundThreadHandler.removeCallbacksAndMessages(null)
        backgroundHandlerThread.quitSafely()
    }

    fun disableSensors() {
        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).disableSensors()
        }
    }

    fun enableSensors() {
        if (stopped) {
            return
        }

        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).enableSensors()
        }
    }

    private fun releaseControllerNumber(context: GenericControllerContext) {
        if (context.reservedControllerNumber) {
            LimeLog.info("Controller number " + context.controllerNumber + " is now available")
            currentControllers =
                (currentControllers.toInt() and (1 shl context.controllerNumber.toInt()).inv()).toShort()
        }

        if (context.assignedControllerNumber) {
            conn.sendControllerInput(
                context.controllerNumber,
                getActiveControllerMask(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        }
    }

    private fun isAssociatedJoystick(
        originalDevice: InputDevice,
        possibleAssociatedJoystick: InputDevice?,
    ): Boolean {
        if (possibleAssociatedJoystick == null) {
            return false
        }
        if ((possibleAssociatedJoystick.sources and InputDevice.SOURCE_JOYSTICK) !=
            InputDevice.SOURCE_JOYSTICK
        ) {
            return false
        }
        if (possibleAssociatedJoystick.name == originalDevice.name) {
            return false
        }
        if (possibleAssociatedJoystick.descriptor != originalDevice.descriptor) {
            return false
        }
        return true
    }

    private fun assignControllerNumberIfNeeded(context: GenericControllerContext) {
        if (context.assignedControllerNumber) {
            return
        }

        if (context is InputDeviceContext) {
            if (context is UsbDeviceContext) {
                if (prefConfig.multiController) {
                    LimeLog.info("Reserving the next available controller number for USB device")
                    reserveNextControllerNumber(context)
                } else {
                    LimeLog.info("Not reserving a controller number")
                    context.controllerNumber = 0
                }

                if (prefConfig.gamepadMotionSensorsFallbackToDevice &&
                    context.controllerNumber.toInt() == 0 &&
                    (prefConfig.forceMotionSensorsFallbackToDevice || context.sensorManager == null)
                ) {
                    context.sensorManager = deviceSensorManager
                }
            } else {
                LimeLog.info(context.name + " (" + context.id + ") needs a controller number assigned")
                if (!context.external) {
                    LimeLog.info("Built-in buttons hardcoded as controller 0")
                    context.controllerNumber = 0
                } else if (prefConfig.multiController && context.hasJoystickAxes) {
                    LimeLog.info("Reserving the next available controller number")
                    reserveNextControllerNumber(context)
                } else if (!context.hasJoystickAxes) {
                    context.controllerNumber = 0

                    var associatedDevice = InputDevice.getDevice(context.id + 1)
                    if (!isAssociatedJoystick(context.inputDevice, associatedDevice)) {
                        associatedDevice = InputDevice.getDevice(context.id - 1)
                        if (!isAssociatedJoystick(context.inputDevice, associatedDevice)) {
                            LimeLog.info("No associated joystick device found")
                            associatedDevice = null
                        }
                    }

                    if (associatedDevice != null) {
                        var associatedDeviceContext = inputDeviceContexts[associatedDevice.id]
                        if (associatedDeviceContext == null) {
                            associatedDeviceContext = createInputDeviceContextForDevice(associatedDevice)
                            inputDeviceContexts.put(associatedDevice.id, associatedDeviceContext)
                        }

                        if (!associatedDeviceContext.assignedControllerNumber) {
                            assignControllerNumberIfNeeded(associatedDeviceContext)
                        }

                        context.controllerNumber = associatedDeviceContext.controllerNumber
                        LimeLog.info("Propagated controller number from " + associatedDeviceContext.name)
                    }
                } else {
                    LimeLog.info("Not reserving a controller number")
                    context.controllerNumber = 0
                }

                if (prefConfig.gamepadMotionSensorsFallbackToDevice &&
                    context.controllerNumber.toInt() == 0 &&
                    (prefConfig.forceMotionSensorsFallbackToDevice || context.sensorManager == null)
                ) {
                    context.sensorManager = deviceSensorManager
                }
            }
        }

        LimeLog.info("Assigned as controller " + context.controllerNumber)
        context.assignedControllerNumber = true
        context.sendControllerArrival()
    }

    private fun reserveNextControllerNumber(context: GenericControllerContext) {
        for (controllerIndex in 0 until MAX_GAMEPADS.toInt()) {
            if ((currentControllers.toInt() and (1 shl controllerIndex)) == 0) {
                currentControllers = (currentControllers.toInt() or (1 shl controllerIndex)).toShort()
                initialControllers = (initialControllers.toInt() and (1 shl controllerIndex).inv()).toShort()
                context.controllerNumber = controllerIndex.toShort()
                context.reservedControllerNumber = true
                break
            }
        }
    }

    private fun createUsbDeviceContextForDevice(device: AbstractController): UsbDeviceContext {
        val context = UsbDeviceContext()
        context.id = device.getControllerId()
        context.device = device
        context.external = true
        context.vendorId = device.getVendorId()
        context.productId = device.getProductId()
        context.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        context.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        context.triggerDeadzone = 0.13f
        return context
    }

    private fun shouldIgnoreBack(dev: InputDevice): Boolean {
        val devName = dev.name

        if (devName.contains("Razer Serval")) {
            return true
        }

        if (!hasJoystickAxes(dev) && devName.lowercase().contains("remote")) {
            return true
        }

        return if (!isExternal(dev)) {
            val im = activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager
            var foundInternalGamepad = false
            var foundInternalSelect = false

            for (id in im.inputDeviceIds) {
                val currentDev = im.getInputDevice(id)
                if (currentDev == null || isExternal(currentDev)) {
                    continue
                }

                if (currentDev.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0]) {
                    foundInternalSelect = true
                }
                if (hasGamepadButtons(currentDev)) {
                    foundInternalGamepad = true
                }
            }

            !foundInternalGamepad || foundInternalSelect
        } else {
            !hasJoystickAxes(dev) && !hasGamepadButtons(dev)
        }
    }

    private fun createInputDeviceContextForDevice(dev: InputDevice): InputDeviceContext {
        val context = InputDeviceContext()
        val devName = dev.name

        LimeLog.info("Creating controller context for device: " + devName)
        LimeLog.info("Vendor ID: " + dev.vendorId)
        LimeLog.info("Product ID: " + dev.productId)
        LimeLog.info(dev.toString())

        context.inputDevice = dev
        context.name = devName
        context.id = dev.id
        context.external = isExternal(dev)
        context.vendorId = dev.vendorId
        context.productId = dev.productId

        context.hasPaddles = MoonBridge.guessControllerHasPaddles(context.vendorId, context.productId)
        context.hasShare = MoonBridge.guessControllerHasShareButton(context.vendorId, context.productId)

        if (prefConfig.enableDeviceRumble) {
            context.vibrator = deviceVibrator
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                hasQuadAmplitudeControlledRumbleVibrators(dev.vibratorManager)
            ) {
                context.vibratorManager = dev.vibratorManager
                context.quadVibrators = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                hasDualAmplitudeControlledRumbleVibrators(dev.vibratorManager)
            ) {
                context.vibratorManager = dev.vibratorManager
                context.quadVibrators = false
            } else if (dev.vibrator.hasVibrator()) {
                context.vibrator = dev.vibrator
            } else if (!context.external) {
                val vm = deviceVibratorManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    vm != null &&
                    hasQuadAmplitudeControlledRumbleVibrators(vm)
                ) {
                    context.vibratorManager = vm
                    context.quadVibrators = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    vm != null &&
                    hasDualAmplitudeControlledRumbleVibrators(vm)
                ) {
                    context.vibratorManager = vm
                    context.quadVibrators = false
                } else if (deviceVibrator.hasVibrator()) {
                    context.vibrator = deviceVibrator
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                (Build.VERSION.SDK_INT == Build.VERSION_CODES.S &&
                    (context.vendorId == 0x054c || context.vendorId == 0x057e))) &&
            prefConfig.gamepadMotionSensors
        ) {
            val sensorManager = dev.sensorManager
            if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            ) {
                context.sensorManager = sensorManager
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (light in dev.lightsManager.lights) {
                if (light.hasRgbControl()) {
                    context.hasRgbLed = true
                    break
                }
            }
        }

        val buttons = dev.hasKeys(
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK,
            0,
        )
        context.hasMode = buttons[0]
        context.hasSelect = buttons[1] || buttons[2]

        context.touchpadXRange = dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadYRange = dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadPressureRange =
            dev.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)

        context.leftStickXAxis = MotionEvent.AXIS_X
        context.leftStickYAxis = MotionEvent.AXIS_Y
        if (getMotionRangeForJoystickAxis(dev, context.leftStickXAxis) != null &&
            getMotionRangeForJoystickAxis(dev, context.leftStickYAxis) != null
        ) {
            hasGameController = true
            context.hasJoystickAxes = true
        }

        context.isDualShockStandaloneTouchpad =
            context.vendorId == 0x054c &&
            devName.endsWith(" Touchpad") &&
            dev.sources == (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE)

        val leftTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_LTRIGGER)
        val rightTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RTRIGGER)
        val brakeRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_BRAKE)
        val gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS)
        val throttleRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_THROTTLE)

        if (leftTriggerRange != null && rightTriggerRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_LTRIGGER
            context.rightTriggerAxis = MotionEvent.AXIS_RTRIGGER
        } else if (brakeRange != null && gasRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_GAS
        } else if (brakeRange != null && throttleRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_THROTTLE
        } else {
            val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
            val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)
            if (rxRange != null && ryRange != null) {
                if (dev.vendorId == 0x054c) {
                    if (dev.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]) {
                        LimeLog.info("Detected non-standard DualShock 4 mapping")
                        context.isNonStandardDualShock4 = true
                    } else {
                        LimeLog.info("Detected DualShock 4 (Linux standard mapping)")
                        context.usesLinuxGamepadStandardFaceButtons = true
                    }
                }

                if (context.isNonStandardDualShock4) {
                    context.leftTriggerAxis = MotionEvent.AXIS_RX
                    context.rightTriggerAxis = MotionEvent.AXIS_RY
                    context.hasSelect = true
                    context.hasMode = true
                } else {
                    context.rightStickXAxis = MotionEvent.AXIS_RX
                    context.rightStickYAxis = MotionEvent.AXIS_RY

                    if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z) != null &&
                        getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ) != null
                    ) {
                        context.leftTriggerAxis = MotionEvent.AXIS_Z
                        context.rightTriggerAxis = MotionEvent.AXIS_RZ
                    }
                }

                context.triggersIdleNegative = true
            }
        }

        if (context.rightStickXAxis == -1 && context.rightStickYAxis == -1) {
            val zRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z)
            val rzRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ)
            if (zRange != null && rzRange != null) {
                context.rightStickXAxis = MotionEvent.AXIS_Z
                context.rightStickYAxis = MotionEvent.AXIS_RZ
            } else {
                val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
                val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)
                if (rxRange != null && ryRange != null) {
                    context.rightStickXAxis = MotionEvent.AXIS_RX
                    context.rightStickYAxis = MotionEvent.AXIS_RY
                }
            }
        }

        val hatXRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_X)
        val hatYRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_Y)
        if (hatXRange != null && hatYRange != null) {
            context.hatXAxis = MotionEvent.AXIS_HAT_X
            context.hatYAxis = MotionEvent.AXIS_HAT_Y
        }

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            context.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            context.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        }
        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            val ltRange = getMotionRangeForJoystickAxis(dev, context.leftTriggerAxis)!!
            val rtRange = getMotionRangeForJoystickAxis(dev, context.rightTriggerAxis)!!
            context.triggerDeadzone = max(abs(ltRange.flat), abs(rtRange.flat))
            if (context.triggerDeadzone < 0.13f || context.triggerDeadzone > 0.30f) {
                context.triggerDeadzone = 0.13f
            }
        }

        if (dev.vendorId == 0x18d1 && dev.productId == 0x2c40) {
            context.backIsStart = true
            context.modeIsSelect = true
            context.triggerDeadzone = 0.30f
            context.hasSelect = true
            context.hasMode = false
        }

        context.ignoreBack = shouldIgnoreBack(dev)

        if (devName.contains("ASUS Gamepad")) {
            val hasStartKey = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, 0)
            if (!hasStartKey[0] && !hasStartKey[1]) {
                context.backIsStart = true
                context.modeIsSelect = true
                context.hasSelect = true
                context.hasMode = false
            }
            context.triggerDeadzone = 0.30f
        } else if (devName.contains("SHIELD") || devName.contains("NVIDIA Controller")) {
            if (devName.contains("NVIDIA Controller v01.03") ||
                devName.contains("NVIDIA Controller v01.04")
            ) {
                context.searchIsMode = true
                context.hasMode = true
            }
        } else if (devName.contains("Razer Serval")) {
            context.isServal = true
            context.hasMode = true
            context.hasSelect = true
        } else if (devName == "Xbox Wireless Controller") {
            if (gasRange == null) {
                context.isNonStandardXboxBtController = true
                context.hasMode = true
                context.hasSelect = true
            }
        }

        if (dev.vendorId == 0x044f && dev.productId == 0xb328) {
            context.hasMode = false
        }

        LimeLog.info(
            "Analog stick deadzone: " +
                context.leftStickDeadzoneRadius +
                " " +
                context.rightStickDeadzoneRadius,
        )
        LimeLog.info("Trigger deadzone: " + context.triggerDeadzone)

        return context
    }

    private fun getContextForEvent(event: InputEvent): InputDeviceContext? {
        if (stopped) {
            return null
        } else if (event.deviceId == 0) {
            return defaultContext
        } else if (event.device == null) {
            return null
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && event.deviceId == -1) {
            return defaultContext
        }

        inputDeviceContexts[event.deviceId]?.let { return it }

        val device = event.device ?: return null
        val context = createInputDeviceContextForDevice(device)
        inputDeviceContexts.put(event.deviceId, context)
        return context
    }

    private fun maxByMagnitude(a: Byte, b: Byte): Byte =
        if (abs(a.toInt()) > abs(b.toInt())) a else b

    private fun maxByMagnitude(a: Short, b: Short): Short =
        if (abs(a.toInt()) > abs(b.toInt())) a else b

    private fun getActiveControllerMask(): Short =
        if (prefConfig.multiController) {
            (currentControllers.toInt() or
                initialControllers.toInt() or
                if (prefConfig.onscreenController) 1 else 0).toShort()
        } else {
            1
        }

    private fun sendControllerBatteryPacket(context: InputDeviceContext) {
        var currentBatteryStatus = BatteryState.STATUS_FULL
        var currentBatteryCapacity = 0f
        var batteryPresent = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val batteryState = context.inputDevice.batteryState
            batteryPresent = batteryState.isPresent
            if (batteryPresent) {
                currentBatteryStatus = batteryState.status
                currentBatteryCapacity = batteryState.capacity
            }
        }

        if (!batteryPresent) {
            if (sceManager.isRecognizedDevice(context.inputDevice)) {
                val batteryPercentage = sceManager.getBatteryPercentage(context.inputDevice)
                currentBatteryCapacity =
                    if (batteryPercentage < 0) {
                        Float.NaN
                    } else {
                        batteryPercentage / 100f
                    }

                val connectionType = sceManager.getConnectionType(context.inputDevice)
                val chargingState = sceManager.getChargingState(context.inputDevice)

                currentBatteryStatus =
                    if (connectionType == SceConnectionType.WIRED ||
                        connectionType == SceConnectionType.BOTH
                    ) {
                        if (batteryPercentage == 100) {
                            BatteryState.STATUS_FULL
                        } else if (chargingState == SceChargingState.NOT_CHARGING) {
                            BatteryState.STATUS_NOT_CHARGING
                        } else {
                            BatteryState.STATUS_CHARGING
                        }
                    } else if (connectionType == SceConnectionType.WIRELESS) {
                        if (chargingState == SceChargingState.CHARGING) {
                            BatteryState.STATUS_CHARGING
                        } else {
                            BatteryState.STATUS_DISCHARGING
                        }
                    } else {
                        if (batteryPercentage == 100) {
                            BatteryState.STATUS_FULL
                        } else if (chargingState == SceChargingState.NOT_CHARGING) {
                            BatteryState.STATUS_DISCHARGING
                        } else if (chargingState == SceChargingState.CHARGING) {
                            BatteryState.STATUS_CHARGING
                        } else {
                            BatteryState.STATUS_UNKNOWN
                        }
                    }
            } else {
                return
            }
        }

        if (currentBatteryStatus != context.lastReportedBatteryStatus ||
            !areBatteryCapacitiesEqual(currentBatteryCapacity, context.lastReportedBatteryCapacity)
        ) {
            val state =
                when (currentBatteryStatus) {
                    BatteryState.STATUS_UNKNOWN -> MoonBridge.LI_BATTERY_STATE_UNKNOWN
                    BatteryState.STATUS_CHARGING -> MoonBridge.LI_BATTERY_STATE_CHARGING
                    BatteryState.STATUS_DISCHARGING -> MoonBridge.LI_BATTERY_STATE_DISCHARGING
                    BatteryState.STATUS_NOT_CHARGING -> MoonBridge.LI_BATTERY_STATE_NOT_CHARGING
                    BatteryState.STATUS_FULL -> MoonBridge.LI_BATTERY_STATE_FULL
                    else -> return
                }

            val percentage =
                if (currentBatteryCapacity.isNaN()) {
                    MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN
                } else {
                    (currentBatteryCapacity * 100).toInt().toByte()
                }

            conn.sendControllerBatteryEvent(context.controllerNumber.toByte(), state, percentage)
            context.lastReportedBatteryStatus = currentBatteryStatus
            context.lastReportedBatteryCapacity = currentBatteryCapacity
        }
    }

    private fun sendControllerInputPacket(originalContext: GenericControllerContext) {
        assignControllerNumberIfNeeded(originalContext)

        val controllerNumber = originalContext.controllerNumber
        var inputMap = 0
        var leftTrigger: Byte = 0
        var rightTrigger: Byte = 0
        var leftStickX: Short = 0
        var leftStickY: Short = 0
        var rightStickX: Short = 0
        var rightStickY: Short = 0

        for (i in 0 until inputDeviceContexts.size()) {
            val context = inputDeviceContexts.valueAt(i)
            if (context.assignedControllerNumber &&
                context.controllerNumber == controllerNumber &&
                context.mouseEmulationActive == originalContext.mouseEmulationActive
            ) {
                inputMap = inputMap or context.inputMap
                leftTrigger = (leftTrigger.toInt() or maxByMagnitude(leftTrigger, context.leftTrigger).toInt()).toByte()
                rightTrigger =
                    (rightTrigger.toInt() or maxByMagnitude(rightTrigger, context.rightTrigger).toInt()).toByte()
                leftStickX =
                    (leftStickX.toInt() or maxByMagnitude(leftStickX, context.leftStickX).toInt()).toShort()
                leftStickY =
                    (leftStickY.toInt() or maxByMagnitude(leftStickY, context.leftStickY).toInt()).toShort()
                rightStickX =
                    (rightStickX.toInt() or maxByMagnitude(rightStickX, context.rightStickX).toInt()).toShort()
                rightStickY =
                    (rightStickY.toInt() or maxByMagnitude(rightStickY, context.rightStickY).toInt()).toShort()
            }
        }
        for (i in 0 until usbDeviceContexts.size()) {
            val context = usbDeviceContexts.valueAt(i)
            if (context.assignedControllerNumber &&
                context.controllerNumber == controllerNumber &&
                context.mouseEmulationActive == originalContext.mouseEmulationActive
            ) {
                inputMap = inputMap or context.inputMap
                leftTrigger = (leftTrigger.toInt() or maxByMagnitude(leftTrigger, context.leftTrigger).toInt()).toByte()
                rightTrigger =
                    (rightTrigger.toInt() or maxByMagnitude(rightTrigger, context.rightTrigger).toInt()).toByte()
                leftStickX =
                    (leftStickX.toInt() or maxByMagnitude(leftStickX, context.leftStickX).toInt()).toShort()
                leftStickY =
                    (leftStickY.toInt() or maxByMagnitude(leftStickY, context.leftStickY).toInt()).toShort()
                rightStickX =
                    (rightStickX.toInt() or maxByMagnitude(rightStickX, context.rightStickX).toInt()).toShort()
                rightStickY =
                    (rightStickY.toInt() or maxByMagnitude(rightStickY, context.rightStickY).toInt()).toShort()
            }
        }
        if (defaultContext.controllerNumber == controllerNumber) {
            inputMap = inputMap or defaultContext.inputMap
            leftTrigger =
                (leftTrigger.toInt() or maxByMagnitude(leftTrigger, defaultContext.leftTrigger).toInt()).toByte()
            rightTrigger =
                (rightTrigger.toInt() or maxByMagnitude(rightTrigger, defaultContext.rightTrigger).toInt()).toByte()
            leftStickX =
                (leftStickX.toInt() or maxByMagnitude(leftStickX, defaultContext.leftStickX).toInt()).toShort()
            leftStickY =
                (leftStickY.toInt() or maxByMagnitude(leftStickY, defaultContext.leftStickY).toInt()).toShort()
            rightStickX =
                (rightStickX.toInt() or maxByMagnitude(rightStickX, defaultContext.rightStickX).toInt()).toShort()
            rightStickY =
                (rightStickY.toInt() or maxByMagnitude(rightStickY, defaultContext.rightStickY).toInt()).toShort()
        }

        if (originalContext.mouseEmulationActive) {
            val changedMask = inputMap xor originalContext.mouseEmulationLastInputMap
            val aDown = (inputMap and ControllerPacket.A_FLAG) != 0
            val bDown = (inputMap and ControllerPacket.B_FLAG) != 0
            val xDown = (inputMap and ControllerPacket.X_FLAG) != 0
            val yDown = (inputMap and ControllerPacket.Y_FLAG) != 0

            originalContext.mouseEmulationLastInputMap = inputMap

            if ((changedMask and ControllerPacket.X_FLAG) != 0) {
                originalContext.mouseEmulationXDown = xDown
            }
            if ((changedMask and ControllerPacket.Y_FLAG) != 0 && yDown) {
                originalContext.mouseEmulationPixelMultiplier *= 2
                if (originalContext.mouseEmulationPixelMultiplier > 255) {
                    originalContext.mouseEmulationPixelMultiplier = 1
                }
            }
            if ((changedMask and ControllerPacket.A_FLAG) != 0) {
                if (aDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                } else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                }
            }
            if ((changedMask and ControllerPacket.B_FLAG) != 0) {
                if (bDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                } else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                }
            }
            if ((changedMask and ControllerPacket.UP_FLAG) != 0 &&
                (inputMap and ControllerPacket.UP_FLAG) != 0
            ) {
                conn.sendMouseScroll(1)
            }
            if ((changedMask and ControllerPacket.DOWN_FLAG) != 0 &&
                (inputMap and ControllerPacket.DOWN_FLAG) != 0
            ) {
                conn.sendMouseScroll((-1).toByte())
            }
            if ((changedMask and ControllerPacket.RIGHT_FLAG) != 0 &&
                (inputMap and ControllerPacket.RIGHT_FLAG) != 0
            ) {
                conn.sendMouseHScroll(1)
            }
            if ((changedMask and ControllerPacket.LEFT_FLAG) != 0 &&
                (inputMap and ControllerPacket.LEFT_FLAG) != 0
            ) {
                conn.sendMouseHScroll((-1).toByte())
            }

            conn.sendControllerInput(controllerNumber, getActiveControllerMask(), 0, 0, 0, 0, 0, 0, 0)
        } else {
            conn.sendControllerInput(
                controllerNumber,
                getActiveControllerMask(),
                inputMap,
                leftTrigger,
                rightTrigger,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
            )
        }
    }

    private fun handleRemapping(context: InputDeviceContext, event: KeyEvent): Int {
        if (context.ignoreBack && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return REMAP_IGNORE
        }

        if (context.hasShare &&
            event.keyCode == KeyEvent.KEYCODE_UNKNOWN &&
            event.scanCode == 167
        ) {
            return KeyEvent.KEYCODE_MEDIA_RECORD
        }

        if (context.vendorId == 0x054c &&
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT &&
            (event.scanCode == 317 || context.isDualShockStandaloneTouchpad)
        ) {
            return KeyEvent.KEYCODE_BUTTON_1
        }

        if (context.vendorId == 0x2dc8 && event.scanCode == 306) {
            return KeyEvent.KEYCODE_BUTTON_MODE
        }

        if ((context.vendorId == 0x057e &&
                context.productId == 0x2009 &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ||
            (context.vendorId == 0x0f0d && context.productId == 0x00c1)
        ) {
            when (event.scanCode) {
                0x130 -> return KeyEvent.KEYCODE_BUTTON_A
                0x131 -> return KeyEvent.KEYCODE_BUTTON_B
                0x132 -> return KeyEvent.KEYCODE_BUTTON_X
                0x133 -> return KeyEvent.KEYCODE_BUTTON_Y
                0x134 -> return KeyEvent.KEYCODE_BUTTON_L1
                0x135 -> return KeyEvent.KEYCODE_BUTTON_R1
                0x136 -> return KeyEvent.KEYCODE_BUTTON_L2
                0x137 -> return KeyEvent.KEYCODE_BUTTON_R2
                0x138 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                0x139 -> return KeyEvent.KEYCODE_BUTTON_START
                0x13A -> return KeyEvent.KEYCODE_BUTTON_THUMBL
                0x13B -> return KeyEvent.KEYCODE_BUTTON_THUMBR
                0x13D -> return KeyEvent.KEYCODE_BUTTON_MODE
            }
        }

        if (prefConfig.enableJoyConFix && context.vendorId == 0x057e && context.productId == 0x2006) {
            when (event.scanCode) {
                546 -> return KeyEvent.KEYCODE_DPAD_LEFT
                547 -> return KeyEvent.KEYCODE_DPAD_RIGHT
                544 -> return KeyEvent.KEYCODE_DPAD_UP
                545 -> return KeyEvent.KEYCODE_DPAD_DOWN
                309 -> return KeyEvent.KEYCODE_BUTTON_MODE
                310 -> return KeyEvent.KEYCODE_BUTTON_L1
                312 -> return KeyEvent.KEYCODE_BUTTON_L2
                314 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                317 -> return KeyEvent.KEYCODE_BUTTON_THUMBL
            }
        }

        if (prefConfig.enableJoyConFix && context.vendorId == 0x057e && context.productId == 0x2007) {
            when (event.scanCode) {
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_X
                304 -> return KeyEvent.KEYCODE_BUTTON_A
                305 -> return KeyEvent.KEYCODE_BUTTON_B
                311 -> return KeyEvent.KEYCODE_BUTTON_R1
                313 -> return KeyEvent.KEYCODE_BUTTON_R2
                315 -> return KeyEvent.KEYCODE_BUTTON_START
                316 -> return KeyEvent.KEYCODE_BUTTON_MODE
                318 -> return KeyEvent.KEYCODE_BUTTON_THUMBR
            }
        }

        if (context.usesLinuxGamepadStandardFaceButtons) {
            when (event.scanCode) {
                304 -> return KeyEvent.KEYCODE_BUTTON_A
                305 -> return KeyEvent.KEYCODE_BUTTON_B
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_X
            }
        }

        if (context.isNonStandardDualShock4) {
            when (event.scanCode) {
                304 -> return KeyEvent.KEYCODE_BUTTON_X
                305 -> return KeyEvent.KEYCODE_BUTTON_A
                306 -> return KeyEvent.KEYCODE_BUTTON_B
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_L1
                309 -> return KeyEvent.KEYCODE_BUTTON_R1
                312 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                313 -> return KeyEvent.KEYCODE_BUTTON_START
                314 -> return KeyEvent.KEYCODE_BUTTON_THUMBL
                315 -> return KeyEvent.KEYCODE_BUTTON_THUMBR
                316 -> return KeyEvent.KEYCODE_BUTTON_MODE
                else -> return REMAP_CONSUME
            }
        } else if (context.isServal && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            when (event.scanCode) {
                314 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                315 -> return KeyEvent.KEYCODE_BUTTON_START
            }
        } else if (context.isNonStandardXboxBtController) {
            when (event.scanCode) {
                306 -> return KeyEvent.KEYCODE_BUTTON_X
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_L1
                309 -> return KeyEvent.KEYCODE_BUTTON_R1
                310 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                311 -> return KeyEvent.KEYCODE_BUTTON_START
                312 -> return KeyEvent.KEYCODE_BUTTON_THUMBL
                313 -> return KeyEvent.KEYCODE_BUTTON_THUMBR
                139 -> return KeyEvent.KEYCODE_BUTTON_MODE
            }

            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                return KeyEvent.KEYCODE_BUTTON_MODE
            }
        } else if (context.vendorId == 0x0b05 &&
            (context.productId == 0x7900 || context.productId == 0x7902)
        ) {
            when (event.scanCode) {
                264, 266 -> return KeyEvent.KEYCODE_BUTTON_START
                265, 267 -> return KeyEvent.KEYCODE_BUTTON_SELECT
            }
        }

        if (context.hatXAxis == -1 &&
            context.hatYAxis == -1 &&
            event.keyCode == KeyEvent.KEYCODE_UNKNOWN
        ) {
            when (event.scanCode) {
                704 -> return KeyEvent.KEYCODE_DPAD_LEFT
                705 -> return KeyEvent.KEYCODE_DPAD_RIGHT
                706 -> return KeyEvent.KEYCODE_DPAD_UP
                707 -> return KeyEvent.KEYCODE_DPAD_DOWN
            }
        }

        var keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            !event.hasNoModifiers() &&
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0
        ) {
            keyCode = KeyEvent.KEYCODE_BUTTON_B
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU) {
            context.backIsStart = false
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            context.modeIsSelect = false
        } else if (context.backIsStart && keyCode == KeyEvent.KEYCODE_BACK) {
            return KeyEvent.KEYCODE_BUTTON_START
        } else if (context.modeIsSelect && keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            return KeyEvent.KEYCODE_BUTTON_SELECT
        } else if (context.searchIsMode && keyCode == KeyEvent.KEYCODE_SEARCH) {
            return KeyEvent.KEYCODE_BUTTON_MODE
        }

        return keyCode
    }

    private fun handleFlipFaceButtons(keyCode: Int): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }

    private fun populateCachedVector(x: Float, y: Float): Vector2d {
        inputVector.initialize(x, y)
        return inputVector
    }

    private fun handleDeadZone(stickVector: Vector2d, deadzoneRadius: Float) {
        if (deadzoneRadius > 0) {
            if (stickVector.getMagnitude() <= deadzoneRadius) {
                stickVector.initialize(0f, 0f)
            }
        } else {
            val currentMagnitude = stickVector.getMagnitude()
            if (currentMagnitude < 0.01) {
                stickVector.initialize(0f, 0f)
                return
            }
            val remainingMagnitude = 1 + deadzoneRadius
            val normalizedMagnitude = -deadzoneRadius + currentMagnitude * remainingMagnitude
            if (normalizedMagnitude >= 1) {
                return
            }
            val scaleFactor = normalizedMagnitude / currentMagnitude
            stickVector.setX((stickVector.getX() * scaleFactor).toFloat())
            stickVector.setY((stickVector.getY() * scaleFactor).toFloat())
        }
    }

    private fun handleAxisSet(
        context: InputDeviceContext,
        lsX: Float,
        lsY: Float,
        rsX: Float,
        rsY: Float,
        ltInput: Float,
        rtInput: Float,
        hatX: Float,
        hatY: Float,
    ) {
        var lt = ltInput
        var rt = rtInput

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            val leftStickVector = populateCachedVector(lsX, lsY)
            handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius)
            context.leftStickX = (leftStickVector.getX() * 0x7FFE).toInt().toShort()
            context.leftStickY = (-leftStickVector.getY() * 0x7FFE).toInt().toShort()
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            val rightStickVector = populateCachedVector(rsX, rsY)
            handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius)
            context.rightStickX = (rightStickVector.getX() * 0x7FFE).toInt().toShort()
            context.rightStickY = (-rightStickVector.getY() * 0x7FFE).toInt().toShort()
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            if (lt != 0f) {
                context.leftTriggerAxisUsed = true
            }
            if (rt != 0f) {
                context.rightTriggerAxisUsed = true
            }
            if (context.triggersIdleNegative) {
                if (context.leftTriggerAxisUsed) {
                    lt = (lt + 1) / 2
                }
                if (context.rightTriggerAxisUsed) {
                    rt = (rt + 1) / 2
                }
            }

            if (lt <= context.triggerDeadzone) {
                lt = 0f
            }
            if (rt <= context.triggerDeadzone) {
                rt = 0f
            }

            context.leftTrigger = (lt * 0xFF).toInt().toByte()
            context.rightTrigger = (rt * 0xFF).toInt().toByte()
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            context.inputMap = context.inputMap and (ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            if (hatX < -0.5) {
                context.inputMap = context.inputMap or ControllerPacket.LEFT_FLAG
                context.hatXAxisUsed = true
            } else if (hatX > 0.5) {
                context.inputMap = context.inputMap or ControllerPacket.RIGHT_FLAG
                context.hatXAxisUsed = true
            }

            context.inputMap = context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG).inv()
            if (hatY < -0.5) {
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG
                context.hatYAxisUsed = true
            } else if (hatY > 0.5) {
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG
                context.hatYAxisUsed = true
            }
        }

        sendControllerInputPacket(context)
    }

    private fun normalizeRawValueWithRange(valueInput: Float, range: InputDevice.MotionRange): Float {
        var value = max(valueInput, range.min)
        value = min(value, range.max)
        value -= range.min
        return value / range.range
    }

    private fun sendTouchpadEventForPointer(
        context: InputDeviceContext,
        event: MotionEvent,
        touchType: Byte,
        pointerIndex: Int,
    ): Boolean {
        val normalizedX = normalizeRawValueWithRange(event.getX(pointerIndex), context.touchpadXRange!!)
        val normalizedY = normalizeRawValueWithRange(event.getY(pointerIndex), context.touchpadYRange!!)
        val normalizedPressure =
            context.touchpadPressureRange?.let {
                normalizeRawValueWithRange(event.getPressure(pointerIndex), it)
            } ?: 0f

        return conn.sendControllerTouchEvent(
            context.controllerNumber.toByte(),
            touchType,
            event.getPointerId(pointerIndex),
            normalizedX,
            normalizedY,
            normalizedPressure,
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    fun tryHandleTouchpadEvent(event: MotionEvent): Boolean {
        if (event.source != InputDevice.SOURCE_TOUCHPAD && event.source != InputDevice.SOURCE_MOUSE) {
            return false
        }

        val context = inputDeviceContexts[event.deviceId] ?: return false

        if (event.source == InputDevice.SOURCE_MOUSE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                    sendControllerInputPacket(context)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                    sendControllerInputPacket(context)
                }
            }

            return !prefConfig.gamepadTouchpadAsMouse
        }

        val touchType =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                -> MoonBridge.LI_TOUCH_EVENT_DOWN

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                -> {
                    if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                        MoonBridge.LI_TOUCH_EVENT_CANCEL
                    } else {
                        MoonBridge.LI_TOUCH_EVENT_UP
                    }
                }

                MotionEvent.ACTION_MOVE -> MoonBridge.LI_TOUCH_EVENT_MOVE
                MotionEvent.ACTION_CANCEL -> MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        event.actionButton == MotionEvent.BUTTON_PRIMARY
                    ) {
                        context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                        sendControllerInputPacket(context)
                        return !prefConfig.gamepadTouchpadAsMouse
                    }
                    return false
                }

                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        event.actionButton == MotionEvent.BUTTON_PRIMARY
                    ) {
                        context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                        sendControllerInputPacket(context)
                        return !prefConfig.gamepadTouchpadAsMouse
                    }
                    return false
                }

                else -> return false
            }

        if (prefConfig.gamepadTouchpadAsMouse) {
            return false
        }
        if (context.touchpadXRange == null || context.touchpadYRange == null) {
            return false
        }

        return if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.pointerCount) {
                if (!sendTouchpadEventForPointer(context, event, touchType, i)) {
                    return false
                }
            }
            true
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            conn.sendControllerTouchEvent(
                context.controllerNumber.toByte(),
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
                0,
                0f,
                0f,
                0f,
            ) != MoonBridge.LI_ERR_UNSUPPORTED
        } else {
            sendTouchpadEventForPointer(context, event, touchType, event.actionIndex)
        }
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val context = getContextForEvent(event) ?: return true

        var lsX = 0f
        var lsY = 0f
        var rsX = 0f
        var rsY = 0f
        var rt = 0f
        var lt = 0f
        var hatX = 0f
        var hatY = 0f

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            lsX = event.getAxisValue(context.leftStickXAxis)
            lsY = event.getAxisValue(context.leftStickYAxis)
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            rsX = event.getAxisValue(context.rightStickXAxis)
            rsY = event.getAxisValue(context.rightStickYAxis)
        }
        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            lt = event.getAxisValue(context.leftTriggerAxis)
            rt = event.getAxisValue(context.rightTriggerAxis)
        }
        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        }

        handleAxisSet(context, lsX, lsY, rsX, rsY, lt, rt, hatX, hatY)
        return true
    }

    private fun convertRawStickAxisToPixelMovement(stickX: Short, stickY: Short): Vector2d {
        val vector = Vector2d()
        vector.initialize(stickX.toFloat(), stickY.toFloat())
        vector.scalarMultiply(1 / 32766.0)
        vector.scalarMultiply(4.0)
        if (vector.getMagnitude() > 0) {
            vector.scalarMultiply(Math.pow(vector.getMagnitude(), 2.0))
        }
        return vector
    }

    private fun sendEmulatedMouseMove(
        x: Short,
        y: Short,
        mouseEmulationXDown: Boolean,
        mouseEmulationPixelMultiplier: Int,
    ) {
        val vector = convertRawStickAxisToPixelMovement(x, y)
        if (vector.getMagnitude() >= 1) {
            if (mouseEmulationXDown) {
                conn.sendMouseMove(
                    (Integer.signum(vector.getX().toInt()) * mouseEmulationPixelMultiplier).toShort(),
                    (Integer.signum((-vector.getY()).toInt()) * mouseEmulationPixelMultiplier).toShort(),
                )
            } else {
                conn.sendMouseMove(vector.getX().toInt().toShort(), (-vector.getY()).toInt().toShort())
            }
        }
    }

    private fun sendEmulatedMouseScroll(x: Short, y: Short) {
        val vector = convertRawStickAxisToPixelMovement(x, y)
        if (vector.getMagnitude() >= 1) {
            conn.sendMouseHighResScroll(vector.getY().toInt().toShort())
            conn.sendMouseHighResHScroll(vector.getX().toInt().toShort())
        }
    }

    @TargetApi(31)
    private fun hasDualAmplitudeControlledRumbleVibrators(vm: VibratorManager): Boolean {
        val vibratorIds = vm.vibratorIds
        if (vibratorIds.size != 2) {
            return false
        }
        for (vid in vibratorIds) {
            if (!vm.getVibrator(vid).hasAmplitudeControl()) {
                return false
            }
        }
        return true
    }

    @TargetApi(31)
    private fun rumbleDualVibrators(
        vm: VibratorManager,
        lowFreqMotorInput: Short,
        highFreqMotorInput: Short,
    ) {
        val highFreqMotor = ((highFreqMotorInput.toInt() shr 8) and 0xFF).toShort()
        val lowFreqMotor = ((lowFreqMotorInput.toInt() shr 8) and 0xFF).toShort()

        if (lowFreqMotor.toInt() == 0 && highFreqMotor.toInt() == 0) {
            vm.cancel()
            return
        }

        val vibratorIds = vm.vibratorIds
        val vibratorAmplitudes = intArrayOf(highFreqMotor.toInt(), lowFreqMotor.toInt())
        val combo = CombinedVibration.startParallel()

        for (i in vibratorIds.indices) {
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(
                    vibratorIds[i],
                    VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]),
                )
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vm.vibrate(combo.combine(), vibrationAttributes.build())
    }

    @TargetApi(31)
    private fun hasQuadAmplitudeControlledRumbleVibrators(vm: VibratorManager): Boolean {
        val vibratorIds = vm.vibratorIds
        if (vibratorIds.size != 4) {
            return false
        }
        for (vid in vibratorIds) {
            if (!vm.getVibrator(vid).hasAmplitudeControl()) {
                return false
            }
        }
        return true
    }

    @TargetApi(31)
    private fun rumbleQuadVibrators(
        vm: VibratorManager,
        lowFreqMotorInput: Short,
        highFreqMotorInput: Short,
        leftTriggerInput: Short,
        rightTriggerInput: Short,
    ) {
        val highFreqMotor = ((highFreqMotorInput.toInt() shr 8) and 0xFF).toShort()
        val lowFreqMotor = ((lowFreqMotorInput.toInt() shr 8) and 0xFF).toShort()
        val leftTrigger = ((leftTriggerInput.toInt() shr 8) and 0xFF).toShort()
        val rightTrigger = ((rightTriggerInput.toInt() shr 8) and 0xFF).toShort()

        if (lowFreqMotor.toInt() == 0 &&
            highFreqMotor.toInt() == 0 &&
            leftTrigger.toInt() == 0 &&
            rightTrigger.toInt() == 0
        ) {
            vm.cancel()
            return
        }

        val vibratorIds = vm.vibratorIds
        val vibratorAmplitudes =
            intArrayOf(highFreqMotor.toInt(), lowFreqMotor.toInt(), leftTrigger.toInt(), rightTrigger.toInt())
        val combo = CombinedVibration.startParallel()

        for (i in vibratorIds.indices) {
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(
                    vibratorIds[i],
                    VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]),
                )
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vm.vibrate(combo.combine(), vibrationAttributes.build())
    }

    private fun rumbleSingleVibrator(vibrator: Vibrator, lowFreqMotor: Short, highFreqMotor: Short) {
        val lowFreqMotorMSB = ((lowFreqMotor.toInt() shr 8) and 0xFF).toShort()
        val highFreqMotorMSB = ((highFreqMotor.toInt() shr 8) and 0xFF).toShort()
        val simulatedAmplitude =
            min(255, (lowFreqMotorMSB * 0.80 + highFreqMotorMSB * 0.33).toInt())

        if (simulatedAmplitude == 0) {
            vibrator.cancel()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
            val effect = VibrationEffect.createOneShot(60000, simulatedAmplitude)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val vibrationAttributes =
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_MEDIA)
                        .build()
                vibrator.vibrate(effect, vibrationAttributes)
            } else {
                val audioAttributes =
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build()
                vibrator.vibrate(effect, audioAttributes)
            }
            return
        }

        val pwmPeriod = 20L
        val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
        val offTime = pwmPeriod - onTime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttributes =
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, onTime, offTime), 0), vibrationAttributes)
        } else {
            val audioAttributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build()
            vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
        }
    }

    fun handleRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        var foundMatchingDevice = false
        var vibrated = false

        if (stopped) {
            return
        }

        for (i in 0 until inputDeviceContexts.size()) {
            val deviceContext = inputDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = true
                deviceContext.lowFreqMotor = lowFreqMotor
                deviceContext.highFreqMotor = highFreqMotor

                val vibratorManager = deviceContext.vibratorManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
                    vibrated = true
                    if (deviceContext.quadVibrators) {
                        rumbleQuadVibrators(
                            vibratorManager,
                            deviceContext.lowFreqMotor,
                            deviceContext.highFreqMotor,
                            deviceContext.leftTriggerMotor,
                            deviceContext.rightTriggerMotor,
                        )
                    } else {
                        rumbleDualVibrators(vibratorManager, deviceContext.lowFreqMotor, deviceContext.highFreqMotor)
                    }
                } else if (sceManager.rumble(
                        deviceContext.inputDevice,
                        deviceContext.lowFreqMotor.toInt(),
                        deviceContext.highFreqMotor.toInt(),
                    )
                ) {
                    vibrated = true
                } else {
                    val vibrator = deviceContext.vibrator
                    if (vibrator != null) {
                        vibrated = true
                        rumbleSingleVibrator(vibrator, deviceContext.lowFreqMotor, deviceContext.highFreqMotor)
                    }
                }
            }
        }

        for (i in 0 until usbDeviceContexts.size()) {
            val deviceContext = usbDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = true
                vibrated = true
                deviceContext.device.rumble(lowFreqMotor, highFreqMotor)
            }
        }

        if (controllerNumber.toInt() == 0) {
            if (!foundMatchingDevice && prefConfig.onscreenController && !prefConfig.onlyL3R3 && prefConfig.vibrateOsc) {
                rumbleSingleVibrator(deviceVibrator, lowFreqMotor, highFreqMotor)
            } else if (foundMatchingDevice && !vibrated && prefConfig.vibrateFallbackToDevice) {
                val lowFreqMotorAdjusted =
                    min(
                        ((lowFreqMotor.toInt() and 0xffff) * prefConfig.vibrateFallbackToDeviceStrength) / 100,
                        Short.MAX_VALUE * 2,
                    ).toShort()
                val highFreqMotorAdjusted =
                    min(
                        ((highFreqMotor.toInt() and 0xffff) * prefConfig.vibrateFallbackToDeviceStrength) / 100,
                        Short.MAX_VALUE * 2,
                    ).toShort()

                rumbleSingleVibrator(deviceVibrator, lowFreqMotorAdjusted, highFreqMotorAdjusted)
            }
        }
    }

    fun handleRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        if (stopped) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until inputDeviceContexts.size()) {
                val deviceContext = inputDeviceContexts.valueAt(i)
                if (deviceContext.controllerNumber == controllerNumber) {
                    deviceContext.leftTriggerMotor = leftTrigger
                    deviceContext.rightTriggerMotor = rightTrigger

                    val vibratorManager = deviceContext.vibratorManager
                    if (deviceContext.quadVibrators && vibratorManager != null) {
                        rumbleQuadVibrators(
                            vibratorManager,
                            deviceContext.lowFreqMotor,
                            deviceContext.highFreqMotor,
                            deviceContext.leftTriggerMotor,
                            deviceContext.rightTriggerMotor,
                        )
                    }
                }
            }
        }

        for (i in 0 until usbDeviceContexts.size()) {
            val deviceContext = usbDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                deviceContext.device.rumbleTriggers(leftTrigger, rightTrigger)
            }
        }
    }

    private fun createSensorListener(
        controllerNumber: Short,
        motionType: Byte,
        needsDeviceOrientationCorrection: Boolean,
    ): SensorEventListener =
        object : SensorEventListener {
            private val lastValues = FloatArray(3)

            override fun onSensorChanged(sensorEvent: SensorEvent) {
                if (sensorEvent.values[0] == lastValues[0] &&
                    sensorEvent.values[1] == lastValues[1] &&
                    sensorEvent.values[2] == lastValues[2]
                ) {
                    return
                } else {
                    lastValues[0] = sensorEvent.values[0]
                    lastValues[1] = sensorEvent.values[1]
                    lastValues[2] = sensorEvent.values[2]
                }

                var x = 0
                var y = 1
                var z = 2
                var xFactor = 1
                var yFactor = 1
                var zFactor = 1

                if (needsDeviceOrientationCorrection) {
                    val deviceRotation = activityContext.windowManager.defaultDisplay.rotation
                    when (deviceRotation) {
                        Surface.ROTATION_0,
                        Surface.ROTATION_180,
                        -> {
                            x = 0
                            y = 2
                            z = 1
                        }

                        Surface.ROTATION_90,
                        Surface.ROTATION_270,
                        -> {
                            x = 1
                            y = 2
                            z = 0
                        }
                    }

                    when (deviceRotation) {
                        Surface.ROTATION_0 -> zFactor = -1
                        Surface.ROTATION_90 -> {
                            xFactor = -1
                            zFactor = -1
                        }
                        Surface.ROTATION_180 -> xFactor = -1
                        Surface.ROTATION_270 -> Unit
                    }
                }

                if (motionType == MoonBridge.LI_MOTION_TYPE_GYRO) {
                    conn.sendControllerMotionEvent(
                        controllerNumber.toByte(),
                        motionType,
                        sensorEvent.values[x] * xFactor * 57.2957795f,
                        sensorEvent.values[y] * yFactor * 57.2957795f,
                        sensorEvent.values[z] * zFactor * 57.2957795f,
                    )
                } else {
                    conn.sendControllerMotionEvent(
                        controllerNumber.toByte(),
                        motionType,
                        sensorEvent.values[x] * xFactor,
                        sensorEvent.values[y] * yFactor,
                        sensorEvent.values[z] * zFactor,
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

    fun handleSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHzInput: Short) {
        if (stopped) {
            return
        }

        val reportRateHz = min(200, reportRateHzInput.toInt()).toShort()

        for (i in 0 until inputDeviceContexts.size() + usbDeviceContexts.size()) {
            val deviceContext =
                if (i < inputDeviceContexts.size()) {
                    inputDeviceContexts.valueAt(i)
                } else {
                    usbDeviceContexts.valueAt(i - inputDeviceContexts.size())
                }

            if (deviceContext.controllerNumber == controllerNumber) {
                when (motionType) {
                    MoonBridge.LI_MOTION_TYPE_ACCEL -> deviceContext.accelReportRateHz = reportRateHz
                    MoonBridge.LI_MOTION_TYPE_GYRO -> deviceContext.gyroReportRateHz = reportRateHz
                }

                backgroundThreadHandler.removeCallbacks(deviceContext.enableSensorRunnable)

                val sm = deviceContext.sensorManager ?: continue

                when (motionType) {
                    MoonBridge.LI_MOTION_TYPE_ACCEL -> {
                        if (deviceContext.accelListener != null) {
                            sm.unregisterListener(deviceContext.accelListener)
                            deviceContext.accelListener = null
                        }

                        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        if (reportRateHz.toInt() != 0 && accelSensor != null) {
                            deviceContext.accelListener =
                                createSensorListener(controllerNumber, motionType, sm == deviceSensorManager)
                            sm.registerListener(deviceContext.accelListener, accelSensor, 1000000 / reportRateHz)
                        }
                    }

                    MoonBridge.LI_MOTION_TYPE_GYRO -> {
                        if (deviceContext.gyroListener != null) {
                            sm.unregisterListener(deviceContext.gyroListener)
                            deviceContext.gyroListener = null
                        }

                        val gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                        if (reportRateHz.toInt() != 0 && gyroSensor != null) {
                            deviceContext.gyroListener =
                                createSensorListener(controllerNumber, motionType, sm == deviceSensorManager)
                            sm.registerListener(deviceContext.gyroListener, gyroSensor, 1000000 / reportRateHz)
                        }
                    }
                }
                break
            }
        }
    }

    fun handleSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        if (stopped) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until inputDeviceContexts.size()) {
                val deviceContext = inputDeviceContexts.valueAt(i)
                if (deviceContext.controllerNumber == controllerNumber && deviceContext.hasRgbLed) {
                    if (deviceContext.lightsSession == null) {
                        deviceContext.lightsSession = deviceContext.inputDevice.lightsManager.openSession()
                    }

                    val argbValue =
                        -0x1000000 or
                            ((r.toInt() shl 16) and 0xFF0000) or
                            ((g.toInt() shl 8) and 0xFF00) or
                            (b.toInt() and 0xFF)
                    val lightState = LightState.Builder().setColor(argbValue).build()
                    val lightsRequestBuilder = LightsRequest.Builder()
                    for (light in deviceContext.inputDevice.lightsManager.lights) {
                        if (light.hasRgbControl()) {
                            lightsRequestBuilder.addLight(light, lightState)
                        }
                    }

                    deviceContext.lightsSession!!.requestLights(lightsRequestBuilder.build())
                }
            }
        }
    }

    fun handleButtonUp(event: KeyEvent): Boolean {
        val context = getContextForEvent(event) ?: return true
        var keyCode = handleRemapping(context, event)
        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        val releaseKey = ControllerButtonReleaseScheduler.ReleaseKey(keyCode, event.scanCode)
        if (buttonReleaseScheduler.scheduleIfNeeded(
                owner = context,
                key = releaseKey,
                downTimeMs = event.downTime,
                eventTimeMs = event.eventTime,
                shouldSkip = { stopped || context.destroyed },
                release = { applyButtonUp(context, keyCode, event.scanCode, event.eventTime) },
            )
        ) {
            return true
        }

        return applyButtonUp(context, keyCode, event.scanCode, event.eventTime)
    }

    private fun applyButtonUp(
        context: InputDeviceContext,
        keyCode: Int,
        scanCode: Int,
        eventTime: Long,
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
            }

            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU,
            -> {
                context.startUpTime = eventTime
                if ((context.inputMap and ControllerPacket.PLAY_FLAG) != 0 &&
                    context.startUpTime - context.startDownTime > START_DOWN_TIME_MOUSE_MODE_MS
                ) {
                    if (prefConfig.enableBackMenu && context.backMenuPending) {
                        context.backMenuPending = false
                        gestures.showGameMenu(context)
                    } else if (prefConfig.mouseEmulation) {
                        context.toggleMouseEmulation()
                    }
                }
                context.inputMap = context.inputMap and ControllerPacket.PLAY_FLAG.inv()
            }

            KeyEvent.KEYCODE_BACK -> {
                if (prefConfig.backAsGuide) {
                    context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
                } else {
                    context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
                }
            }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.LEFT_FLAG.inv()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.RIGHT_FLAG.inv()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.UP_FLAG.inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.DOWN_FLAG.inv()
            }

            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap =
                    context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG).inv()
            }

            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap =
                    context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap =
                    context.inputMap and (ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG).inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap =
                    context.inputMap and (ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            }

            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap = context.inputMap and ControllerPacket.B_FLAG.inv()
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> context.inputMap = context.inputMap and ControllerPacket.A_FLAG.inv()

            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap = context.inputMap and ControllerPacket.X_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap = context.inputMap and ControllerPacket.Y_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                context.inputMap = context.inputMap and ControllerPacket.LB_FLAG.inv()
                context.lastLbUpTime = eventTime
            }

            KeyEvent.KEYCODE_BUTTON_R1 -> {
                context.inputMap = context.inputMap and ControllerPacket.RB_FLAG.inv()
                context.lastRbUpTime = eventTime
            }

            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                context.inputMap = context.inputMap and ControllerPacket.LS_CLK_FLAG.inv()
            }

            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                context.inputMap = context.inputMap and ControllerPacket.RS_CLK_FLAG.inv()
            }

            KeyEvent.KEYCODE_MEDIA_RECORD -> {
                context.inputMap = context.inputMap and ControllerPacket.MISC_FLAG.inv()
            }

            KeyEvent.KEYCODE_BUTTON_1 -> {
                context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
            }

            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.leftTriggerAxisUsed) return true
                context.leftTrigger = 0
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.rightTriggerAxisUsed) return true
                context.rightTrigger = 0
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                if (context.hasPaddles) {
                    when (scanCode) {
                        0x2c4 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE1_FLAG.inv()
                        0x2c5 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE2_FLAG.inv()
                        0x2c6 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE3_FLAG.inv()
                        0x2c7 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE4_FLAG.inv()
                        else -> return false
                    }
                } else {
                    return false
                }
            }

            else -> return false
        }

        if ((context.emulatingButtonFlags and EMULATING_SELECT) != 0) {
            if ((context.inputMap and ControllerPacket.PLAY_FLAG) == 0 ||
                (context.inputMap and ControllerPacket.LB_FLAG) == 0
            ) {
                context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_SELECT.inv()
            }
        }

        if ((context.emulatingButtonFlags and EMULATING_SPECIAL) != 0) {
            if ((context.inputMap and ControllerPacket.PLAY_FLAG) == 0 ||
                ((context.inputMap and ControllerPacket.BACK_FLAG) == 0 &&
                    (context.inputMap and ControllerPacket.RB_FLAG) == 0)
            ) {
                context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_SPECIAL.inv()
            }
        }

        if ((context.emulatingButtonFlags and EMULATING_TOUCHPAD) != 0) {
            if ((context.inputMap and ControllerPacket.BACK_FLAG) == 0 ||
                (context.inputMap and ControllerPacket.LB_FLAG) == 0
            ) {
                context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_TOUCHPAD.inv()
            }
        }

        sendControllerInputPacket(context)

        if (context.pendingExit && context.inputMap == 0) {
            activityContext.finish()
        }

        return true
    }

    fun handleButtonDown(event: KeyEvent): Boolean {
        val context = getContextForEvent(event) ?: return true
        var keyCode = handleRemapping(context, event)
        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        buttonReleaseScheduler.flushPendingRelease(
            context,
            ControllerButtonReleaseScheduler.ReleaseKey(keyCode, event.scanCode),
        )

        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                context.hasMode = true
                context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
            }

            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU,
            -> {
                if (event.repeatCount == 0) {
                    context.startDownTime = event.eventTime
                    context.backMenuPending =
                        context.startDownTime - context.startUpTime <= QUICK_MENU_FIRST_STAGE_MS
                }
                context.inputMap = context.inputMap or ControllerPacket.PLAY_FLAG
            }

            KeyEvent.KEYCODE_BACK -> {
                if (prefConfig.backAsGuide) {
                    context.hasSelect = true
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                } else {
                    context.hasSelect = true
                    context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
                }
            }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                context.hasSelect = true
                context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.LEFT_FLAG
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.RIGHT_FLAG
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG
            }

            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG
            }

            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG
            }

            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG
            }

            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG
            }

            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap = context.inputMap or ControllerPacket.B_FLAG
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> context.inputMap = context.inputMap or ControllerPacket.A_FLAG

            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap = context.inputMap or ControllerPacket.X_FLAG
            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap = context.inputMap or ControllerPacket.Y_FLAG
            KeyEvent.KEYCODE_BUTTON_L1 -> context.inputMap = context.inputMap or ControllerPacket.LB_FLAG
            KeyEvent.KEYCODE_BUTTON_R1 -> context.inputMap = context.inputMap or ControllerPacket.RB_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.inputMap = context.inputMap or ControllerPacket.LS_CLK_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.inputMap = context.inputMap or ControllerPacket.RS_CLK_FLAG
            KeyEvent.KEYCODE_MEDIA_RECORD -> context.inputMap = context.inputMap or ControllerPacket.MISC_FLAG
            KeyEvent.KEYCODE_BUTTON_1 -> context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.leftTriggerAxisUsed) return true
                context.leftTrigger = 0xFF.toByte()
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.rightTriggerAxisUsed) return true
                context.rightTrigger = 0xFF.toByte()
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                if (context.hasPaddles) {
                    when (event.scanCode) {
                        0x2c4 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE1_FLAG
                        0x2c5 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE2_FLAG
                        0x2c6 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE3_FLAG
                        0x2c7 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE4_FLAG
                        else -> return false
                    }
                } else {
                    return false
                }
            }

            else -> return false
        }

        if (context.inputMap ==
            (ControllerPacket.BACK_FLAG or
                ControllerPacket.PLAY_FLAG or
                ControllerPacket.LB_FLAG or
                ControllerPacket.RB_FLAG)
        ) {
            context.pendingExit = true
        }

        if (!context.hasSelect) {
            if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG) ||
                (context.inputMap == ControllerPacket.PLAY_FLAG &&
                    event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
            ) {
                context.inputMap =
                    context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG).inv()
                context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SELECT
            }
        } else if (context.needsClickpadEmulation) {
            if (context.inputMap == (ControllerPacket.BACK_FLAG or ControllerPacket.LB_FLAG) ||
                (context.inputMap == ControllerPacket.BACK_FLAG &&
                    event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
            ) {
                context.inputMap =
                    context.inputMap and (ControllerPacket.BACK_FLAG or ControllerPacket.LB_FLAG).inv()
                context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_TOUCHPAD
            }
        }

        if (!context.hasMode) {
            if (context.hasSelect) {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG)) {
                    context.inputMap =
                        context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG).inv()
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            } else {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG) ||
                    (context.inputMap == ControllerPacket.PLAY_FLAG &&
                        event.eventTime - context.lastRbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
                ) {
                    context.inputMap =
                        context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG).inv()
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            }
        }

        sendControllerInputPacket(context)
        return true
    }

    fun reportOscState(
        buttonFlags: Int,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short,
        leftTrigger: Byte,
        rightTrigger: Byte,
    ) {
        defaultContext.leftStickX = leftStickX
        defaultContext.leftStickY = leftStickY
        defaultContext.rightStickX = rightStickX
        defaultContext.rightStickY = rightStickY
        defaultContext.leftTrigger = leftTrigger
        defaultContext.rightTrigger = rightTrigger
        defaultContext.inputMap = buttonFlags
        sendControllerInputPacket(defaultContext)
    }

    override fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
    ) {
        val context = usbDeviceContexts[controllerId] ?: return

        val leftStickVector = populateCachedVector(leftStickX, leftStickY)
        handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius)
        context.leftStickX = (leftStickVector.getX() * 0x7FFE).toInt().toShort()
        context.leftStickY = (-leftStickVector.getY() * 0x7FFE).toInt().toShort()

        val rightStickVector = populateCachedVector(rightStickX, rightStickY)
        handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius)
        context.rightStickX = (rightStickVector.getX() * 0x7FFE).toInt().toShort()
        context.rightStickY = (-rightStickVector.getY() * 0x7FFE).toInt().toShort()

        var lt = leftTrigger
        var rt = rightTrigger
        if (lt <= context.triggerDeadzone) {
            lt = 0f
        }
        if (rt <= context.triggerDeadzone) {
            rt = 0f
        }

        context.leftTrigger = (lt * 0xFF).toInt().toByte()
        context.rightTrigger = (rt * 0xFF).toInt().toByte()
        context.inputMap = buttonFlags
        sendControllerInputPacket(context)
    }

    override fun reportControllerMotion(
        controllerId: Int,
        motionType: Byte,
        motionX: Float,
        motionY: Float,
        motionZ: Float,
    ) {
        val context = usbDeviceContexts[controllerId] ?: return
        conn.sendControllerMotionEvent(context.controllerNumber.toByte(), motionType, motionX, motionY, motionZ)
    }

    override fun deviceRemoved(controller: AbstractController) {
        val context = usbDeviceContexts[controller.getControllerId()]
        if (context != null) {
            LimeLog.info("Removed controller: " + controller.getControllerId())
            releaseControllerNumber(context)
            context.destroy()
            usbDeviceContexts.remove(controller.getControllerId())
        }
    }

    override fun deviceAdded(controller: AbstractController) {
        if (stopped) {
            return
        }

        val context = createUsbDeviceContextForDevice(controller)
        usbDeviceContexts.put(controller.getControllerId(), context)
    }

    inner open class GenericControllerContext : GameInputDevice {
        var id = 0
        var external = false
        var vendorId = 0
        var productId = 0
        var leftStickDeadzoneRadius = 0f
        var rightStickDeadzoneRadius = 0f
        var triggerDeadzone = 0f
        var assignedControllerNumber = false
        var reservedControllerNumber = false
        var controllerNumber: Short = 0
        var inputMap = 0
        var leftTrigger: Byte = 0
        var rightTrigger: Byte = 0
        var rightStickX: Short = 0
        var rightStickY: Short = 0
        var leftStickX: Short = 0
        var leftStickY: Short = 0
        var mouseEmulationActive = false
        var destroyed = false
        var mouseEmulationXDown = false
        var mouseEmulationPixelMultiplier = 1
        var mouseEmulationLastInputMap = 0
        val mouseEmulationReportPeriod = 50
        val mouseEmulationRunnable: Runnable =
            object : Runnable {
                override fun run() {
                    if (!mouseEmulationActive) {
                        return
                    }

                    when (prefConfig.analogStickForScrolling) {
                        PreferenceConfiguration.AnalogStickForScrolling.RIGHT -> {
                            sendEmulatedMouseMove(
                                leftStickX,
                                leftStickY,
                                mouseEmulationXDown,
                                mouseEmulationPixelMultiplier,
                            )
                            sendEmulatedMouseScroll(rightStickX, rightStickY)
                        }

                        PreferenceConfiguration.AnalogStickForScrolling.LEFT -> {
                            sendEmulatedMouseMove(
                                rightStickX,
                                rightStickY,
                                mouseEmulationXDown,
                                mouseEmulationPixelMultiplier,
                            )
                            sendEmulatedMouseScroll(leftStickX, leftStickY)
                        }

                        else -> {
                            sendEmulatedMouseMove(
                                leftStickX,
                                leftStickY,
                                mouseEmulationXDown,
                                mouseEmulationPixelMultiplier,
                            )
                            sendEmulatedMouseMove(
                                rightStickX,
                                rightStickY,
                                mouseEmulationXDown,
                                mouseEmulationPixelMultiplier,
                            )
                        }
                    }

                    mainThreadHandler.postDelayed(this, mouseEmulationReportPeriod.toLong())
                }
            }

        override fun getGameMenuOptions(): List<GameMenu.MenuOption> {
            val options = ArrayList<GameMenu.MenuOption>()
            options.add(
                GameMenu.MenuOption(
                    activityContext.getString(
                        if (mouseEmulationActive) {
                            R.string.game_menu_toggle_mouse_off
                        } else {
                            R.string.game_menu_toggle_mouse_on
                        },
                    ),
                    true,
                    Runnable { toggleMouseEmulation() },
                ),
            )
            return options
        }

        override fun supportsControllerMouseEmulation(): Boolean = true

        override fun isControllerMouseEmulationActive(): Boolean = mouseEmulationActive

        override fun setControllerMouseEmulationActive(active: Boolean) {
            setMouseEmulationActive(active, false)
        }

        fun toggleMouseEmulation() {
            setMouseEmulationActive(!mouseEmulationActive, true)
        }

        private fun setMouseEmulationActive(active: Boolean, showToast: Boolean) {
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable)
            mouseEmulationActive = active
            if (showToast) {
                Toast.makeText(
                    activityContext,
                    "Mouse emulation is: " + if (mouseEmulationActive) "ON" else "OFF",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            if (mouseEmulationActive) {
                mainThreadHandler.postDelayed(mouseEmulationRunnable, mouseEmulationReportPeriod.toLong())
            }
        }

        open fun destroy() {
            destroyed = true
            buttonReleaseScheduler.cancelOwner(this)
            mouseEmulationActive = false
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable)
        }

        open fun sendControllerArrival() = Unit
    }

    inner open class InputDeviceContext : GenericControllerContext() {
        var name = ""
        var vibratorManager: VibratorManager? = null
        var vibrator: Vibrator? = null
        var quadVibrators = false
        var lowFreqMotor: Short = 0
        var highFreqMotor: Short = 0
        var leftTriggerMotor: Short = 0
        var rightTriggerMotor: Short = 0
        var sensorManager: SensorManager? = null
        var gyroListener: SensorEventListener? = null
        var gyroReportRateHz: Short = 0
        var accelListener: SensorEventListener? = null
        var accelReportRateHz: Short = 0
        lateinit var inputDevice: InputDevice
        var hasRgbLed = false
        var lightsSession: LightsManager.LightsSession? = null
        var lastReportedBatteryStatus = 0
        var lastReportedBatteryCapacity = 0f
        var leftStickXAxis = -1
        var leftStickYAxis = -1
        var rightStickXAxis = -1
        var rightStickYAxis = -1
        var leftTriggerAxis = -1
        var rightTriggerAxis = -1
        var triggersIdleNegative = false
        var leftTriggerAxisUsed = false
        var rightTriggerAxisUsed = false
        var hatXAxis = -1
        var hatYAxis = -1
        var hatXAxisUsed = false
        var hatYAxisUsed = false
        var touchpadXRange: InputDevice.MotionRange? = null
        var touchpadYRange: InputDevice.MotionRange? = null
        var touchpadPressureRange: InputDevice.MotionRange? = null
        var isNonStandardDualShock4 = false
        var usesLinuxGamepadStandardFaceButtons = false
        var isNonStandardXboxBtController = false
        var isServal = false
        var backIsStart = false
        var modeIsSelect = false
        var searchIsMode = false
        var ignoreBack = false
        var hasJoystickAxes = false
        var pendingExit = false
        var isDualShockStandaloneTouchpad = false
        var emulatingButtonFlags = 0
        var hasSelect = false
        var hasMode = false
        var hasPaddles = false
        var hasShare = false
        var needsClickpadEmulation = false
        var lastLbUpTime = 0L
        var lastRbUpTime = 0L
        var startDownTime = 0L
        var startUpTime = 0L
        var backMenuPending = false
        val batteryStateUpdateRunnable: Runnable =
            object : Runnable {
                override fun run() {
                    if (stopped || destroyed) {
                        return
                    }

                    sendControllerBatteryPacket(this@InputDeviceContext)
                    backgroundThreadHandler.postDelayed(this, BATTERY_RECHECK_INTERVAL_MS.toLong())
                }
            }
        val enableSensorRunnable =
            Runnable {
                if (stopped || destroyed) {
                    return@Runnable
                }

                if (accelReportRateHz.toInt() != 0 && accelListener == null) {
                    handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_ACCEL, accelReportRateHz)
                }
                if (gyroReportRateHz.toInt() != 0 && gyroListener == null) {
                    handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, gyroReportRateHz)
                }
            }

        override fun destroy() {
            super.destroy()

            val vm = vibratorManager
            val vib = vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vm != null) {
                vm.cancel()
            } else if (vib != null) {
                vib.cancel()
            }

            backgroundThreadHandler.removeCallbacks(enableSensorRunnable)

            val sm = sensorManager
            if (gyroListener != null) {
                sm?.unregisterListener(gyroListener)
            }
            if (accelListener != null) {
                sm?.unregisterListener(accelListener)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lightsSession?.close()
            }

            backgroundThreadHandler.removeCallbacks(batteryStateUpdateRunnable)
        }

        override fun sendControllerArrival() {
            val type =
                when (inputDevice.vendorId) {
                    0x045e -> MoonBridge.LI_CTYPE_XBOX
                    0x054c -> MoonBridge.LI_CTYPE_PS
                    0x057e -> MoonBridge.LI_CTYPE_NINTENDO
                    else -> MoonBridge.guessControllerType(inputDevice.vendorId, inputDevice.productId)
                }

            var supportedButtonFlags = 0
            for ((keyCode, buttonFlag) in ANDROID_TO_LI_BUTTON_MAP) {
                if (inputDevice.hasKeys(keyCode)[0]) {
                    supportedButtonFlags = supportedButtonFlags or buttonFlag
                }
            }

            if (hasPaddles) {
                supportedButtonFlags =
                    supportedButtonFlags or
                    ControllerPacket.PADDLE1_FLAG or
                    ControllerPacket.PADDLE2_FLAG or
                    ControllerPacket.PADDLE3_FLAG or
                    ControllerPacket.PADDLE4_FLAG
            }
            if (hasShare) {
                supportedButtonFlags = supportedButtonFlags or ControllerPacket.MISC_FLAG
            }

            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_X) != null) {
                supportedButtonFlags =
                    supportedButtonFlags or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG
            }
            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_Y) != null) {
                supportedButtonFlags =
                    supportedButtonFlags or ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG
            }

            var capabilities: Short = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities =
                    if (quadVibrators) {
                        (
                            capabilities.toInt() or
                                MoonBridge.LI_CCAP_RUMBLE.toInt() or
                                MoonBridge.LI_CCAP_TRIGGER_RUMBLE.toInt()
                        ).toShort()
                    } else if (vibratorManager != null || vibrator != null) {
                        (capabilities.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()
                    } else {
                        capabilities
                    }

                if (external) {
                    capabilities =
                        (capabilities.toInt() or MoonBridge.LI_CCAP_BATTERY_STATE.toInt()).toShort()
                }

                if (hasRgbLed &&
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                        type == MoonBridge.LI_CTYPE_PS)
                ) {
                    capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_RGB_LED.toInt()).toShort()
                }
            }

            if (leftTriggerAxis != -1 || rightTriggerAxis != -1) {
                capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt()).toShort()
            }

            val sm = sensorManager
            if (sm != null && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_ACCEL.toInt()).toShort()
            }
            if (sm != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_GYRO.toInt()).toShort()
            }

            val reportedType: Byte =
                if (type != MoonBridge.LI_CTYPE_PS && sensorManager != null) {
                    Toast.makeText(
                        activityContext,
                        activityContext.resources.getText(R.string.toast_controller_type_changed),
                        Toast.LENGTH_LONG,
                    ).show()
                    needsClickpadEmulation = true
                    MoonBridge.LI_CTYPE_UNKNOWN
                } else {
                    type
                }

            if (vibrator != null) {
                capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()
            }

            if (sceManager.isRecognizedDevice(inputDevice)) {
                capabilities =
                    (
                        capabilities.toInt() or
                            MoonBridge.LI_CCAP_RUMBLE.toInt() or
                            MoonBridge.LI_CCAP_BATTERY_STATE.toInt()
                    ).toShort()
            }

            if ((inputDevice.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_TOUCHPAD.toInt()).toShort()
                if (hasButtonUnderTouchpad(inputDevice, type)) {
                    supportedButtonFlags = supportedButtonFlags or ControllerPacket.TOUCHPAD_FLAG
                }
            }

            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(),
                getActiveControllerMask(),
                reportedType,
                supportedButtonFlags,
                capabilities,
            )

            if (prefConfig.enableBatteryReport) {
                backgroundThreadHandler.post(batteryStateUpdateRunnable)
            }
        }

        fun migrateContext(oldContext: InputDeviceContext) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lightsSession = oldContext.lightsSession
                oldContext.lightsSession = null
            }
            gyroReportRateHz = oldContext.gyroReportRateHz
            accelReportRateHz = oldContext.accelReportRateHz

            oldContext.destroy()

            assignedControllerNumber = oldContext.assignedControllerNumber
            reservedControllerNumber = oldContext.reservedControllerNumber
            controllerNumber = oldContext.controllerNumber

            if (oldContext.sensorManager == deviceSensorManager) {
                sensorManager = deviceSensorManager
            }

            needsClickpadEmulation = oldContext.needsClickpadEmulation
            enableSensors()
            backgroundThreadHandler.post(batteryStateUpdateRunnable)
        }

        fun disableSensors() {
            backgroundThreadHandler.removeCallbacks(enableSensorRunnable)

            val sm = sensorManager
            if (gyroListener != null) {
                sm?.unregisterListener(gyroListener)
                gyroListener = null
                conn.sendControllerMotionEvent(controllerNumber.toByte(), MoonBridge.LI_MOTION_TYPE_GYRO, 0f, 0f, 0f)
            }
            if (accelListener != null) {
                sm?.unregisterListener(accelListener)
                accelListener = null
            }
        }

        fun enableSensors() {
            if (stopped || destroyed) {
                return
            }

            backgroundThreadHandler.postDelayed(enableSensorRunnable, 1000)
        }
    }

    inner class UsbDeviceContext : InputDeviceContext() {
        lateinit var device: AbstractController

        override fun sendControllerArrival() {
            var type = device.getType()
            var capabilities = device.getCapabilities()

            val sm = sensorManager
            if (type != MoonBridge.LI_CTYPE_PS && type != MoonBridge.LI_CTYPE_NINTENDO && sm != null) {
                if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                    capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_GYRO.toInt()).toShort()
                }
                if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                    capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_ACCEL.toInt()).toShort()
                }

                type = MoonBridge.LI_CTYPE_UNKNOWN
            }

            if (type != MoonBridge.LI_CTYPE_PS &&
                (capabilities.toInt() and
                    (MoonBridge.LI_CCAP_GYRO.toInt() or MoonBridge.LI_CCAP_ACCEL.toInt())) != 0
            ) {
                activityContext.runOnUiThread {
                    Toast.makeText(
                        activityContext,
                        activityContext.resources.getText(R.string.toast_controller_type_changed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(),
                getActiveControllerMask(),
                type,
                device.getSupportedButtonFlags(),
                capabilities,
            )
        }
    }

    companion object {
        private const val VALVE_VENDOR_ID = 0x28de
        private const val STEAM_CONTROLLER_BLUETOOTH_PRODUCT_ID = 0x1303
        private const val STEAM_CONTROLLER_DEVICE_NAME_PREFIX = "Steam Ctrl"
        private const val MAXIMUM_BUMPER_UP_DELAY_MS = 100
        private const val START_DOWN_TIME_MOUSE_MODE_MS = 750
        private const val MINIMUM_BUTTON_DOWN_TIME_MS = 25
        private const val QUICK_MENU_FIRST_STAGE_MS = 200
        private const val EMULATING_SPECIAL = 0x1
        private const val EMULATING_SELECT = 0x2
        private const val EMULATING_TOUCHPAD = 0x4
        private const val MAX_GAMEPADS: Short = 16
        private const val BATTERY_RECHECK_INTERVAL_MS = 120 * 1000
        private const val REMAP_IGNORE = -1
        private const val REMAP_CONSUME = -2

        private val ANDROID_TO_LI_BUTTON_MAP =
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to ControllerPacket.A_FLAG,
                KeyEvent.KEYCODE_BUTTON_B to ControllerPacket.B_FLAG,
                KeyEvent.KEYCODE_BUTTON_X to ControllerPacket.X_FLAG,
                KeyEvent.KEYCODE_BUTTON_Y to ControllerPacket.Y_FLAG,
                KeyEvent.KEYCODE_DPAD_UP to ControllerPacket.UP_FLAG,
                KeyEvent.KEYCODE_DPAD_DOWN to ControllerPacket.DOWN_FLAG,
                KeyEvent.KEYCODE_DPAD_LEFT to ControllerPacket.LEFT_FLAG,
                KeyEvent.KEYCODE_DPAD_RIGHT to ControllerPacket.RIGHT_FLAG,
                KeyEvent.KEYCODE_DPAD_UP_LEFT to (ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG),
                KeyEvent.KEYCODE_DPAD_UP_RIGHT to (ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG),
                KeyEvent.KEYCODE_DPAD_DOWN_LEFT to (ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG),
                KeyEvent.KEYCODE_DPAD_DOWN_RIGHT to (ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG),
                KeyEvent.KEYCODE_BUTTON_L1 to ControllerPacket.LB_FLAG,
                KeyEvent.KEYCODE_BUTTON_R1 to ControllerPacket.RB_FLAG,
                KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerPacket.LS_CLK_FLAG,
                KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerPacket.RS_CLK_FLAG,
                KeyEvent.KEYCODE_BUTTON_START to ControllerPacket.PLAY_FLAG,
                KeyEvent.KEYCODE_MENU to ControllerPacket.PLAY_FLAG,
                KeyEvent.KEYCODE_BUTTON_SELECT to ControllerPacket.BACK_FLAG,
                KeyEvent.KEYCODE_BACK to ControllerPacket.BACK_FLAG,
                KeyEvent.KEYCODE_BUTTON_MODE to ControllerPacket.SPECIAL_BUTTON_FLAG,
                KeyEvent.KEYCODE_MEDIA_RECORD to ControllerPacket.MISC_FLAG,
                KeyEvent.KEYCODE_BUTTON_1 to ControllerPacket.TOUCHPAD_FLAG,
            )

        private fun getMotionRangeForJoystickAxis(
            dev: InputDevice,
            axis: Int,
        ): InputDevice.MotionRange? =
            dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                ?: dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)

        private fun hasJoystickAxes(device: InputDevice): Boolean =
            (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
                getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null

        private fun hasGamepadButtons(device: InputDevice): Boolean =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

        private fun isSteamControllerKeyboardMouseDevice(device: InputDevice): Boolean =
            device.vendorId == VALVE_VENDOR_ID &&
                device.productId == STEAM_CONTROLLER_BLUETOOTH_PRODUCT_ID &&
                device.name.contains(STEAM_CONTROLLER_DEVICE_NAME_PREFIX, ignoreCase = true) &&
                (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                (device.sources and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
                (device.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD

        @JvmStatic
        fun isGameControllerDevice(device: InputDevice?): Boolean {
            if (device == null) {
                return true
            }

            if (hasJoystickAxes(device) ||
                hasGamepadButtons(device) ||
                isSteamControllerKeyboardMouseDevice(device)
            ) {
                return true
            }

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && device.id == -1) {
                for (id in InputDevice.getDeviceIds()) {
                    val dev = InputDevice.getDevice(id) ?: continue
                    if (hasJoystickAxes(dev) ||
                        hasGamepadButtons(dev) ||
                        isSteamControllerKeyboardMouseDevice(dev)
                    ) {
                        return true
                    }
                }
            }

            return device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }

        @JvmStatic
        fun getAttachedControllerMask(context: Context): Short {
            var count = 0
            var mask: Short = 0

            val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            for (id in im.inputDeviceIds) {
                val dev = im.getInputDevice(id) ?: continue
                if (hasJoystickAxes(dev) || isSteamControllerKeyboardMouseDevice(dev)) {
                    LimeLog.info("Counting InputDevice: " + dev.name)
                    mask = (mask.toInt() or (1 shl count++)).toShort()
                }
            }

            if (PreferenceConfiguration.readPreferences(context).usbDriver) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager?
                if (usbManager != null) {
                    for (dev: UsbDevice in usbManager.deviceList.values) {
                        if (UsbDriverService.shouldClaimDevice(dev, false) &&
                            !UsbDriverService.isRecognizedInputDevice(dev)
                        ) {
                            LimeLog.info("Counting UsbDevice: " + dev.deviceName)
                            mask = (mask.toInt() or (1 shl count++)).toShort()
                        }
                    }
                }
            }

            if (PreferenceConfiguration.readPreferences(context).onscreenController) {
                LimeLog.info("Counting OSC gamepad")
                mask = (mask.toInt() or 1).toShort()
            }

            LimeLog.info("Enumerated " + count + " gamepads")
            return mask
        }

        private fun hasButtonUnderTouchpad(dev: InputDevice, type: Byte): Boolean {
            if ((dev.sources and InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
                return false
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
                try {
                    return dev.javaClass.getMethod("hasButtonUnderPad").invoke(dev) as Boolean
                } catch (e: NoSuchMethodException) {
                    e.printStackTrace()
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                } catch (e: InvocationTargetException) {
                    e.printStackTrace()
                } catch (e: ClassCastException) {
                    e.printStackTrace()
                }
            }

            return type == MoonBridge.LI_CTYPE_PS
        }

        private fun isExternal(dev: InputDevice): Boolean {
            if (Build.MODEL == "Tinker Board") {
                return true
            }

            val deviceName = dev.name
            if (deviceName.contains("gpio") ||
                deviceName.contains("joy_key") ||
                deviceName.contains("keypad") ||
                deviceName.equals("NVIDIA Corporation NVIDIA Controller v01.01", ignoreCase = true) ||
                deviceName.equals("NVIDIA Corporation NVIDIA Controller v01.02", ignoreCase = true) ||
                deviceName.equals("GR0006", ignoreCase = true)
            ) {
                LimeLog.info(dev.name + " is internal by hardcoded mapping")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return dev.isExternal
            } else {
                try {
                    return dev.javaClass.getMethod("isExternal").invoke(dev) as Boolean
                } catch (e: NoSuchMethodException) {
                    e.printStackTrace()
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                } catch (e: InvocationTargetException) {
                    e.printStackTrace()
                } catch (e: ClassCastException) {
                    e.printStackTrace()
                }
            }

            return true
        }

        private fun areBatteryCapacitiesEqual(first: Float, second: Float): Boolean =
            if (!first.isNaN() && !second.isNaN()) {
                first == second
            } else {
                first.isNaN() == second.isNaN()
            }
    }
}
