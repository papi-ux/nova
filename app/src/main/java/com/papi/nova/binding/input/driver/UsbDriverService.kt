package com.papi.nova.binding.input.driver

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Looper
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.InputDevice
import android.widget.Toast
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration

class UsbDriverService : Service(), UsbDriverListener {
    private lateinit var usbManager: UsbManager
    private lateinit var prefConfig: PreferenceConfiguration
    private var started = false
    private val receiver = UsbEventReceiver()
    private val binder = UsbDriverBinder()
    private val controllers = ArrayList<AbstractController>()
    private var listener: UsbDriverListener? = null
    private var stateListener: UsbDriverStateListener? = null
    private var nextDeviceId = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbControllers = mutableMapOf<String, MutableList<AbstractController>>()
    private val addedControllers = mutableSetOf<AbstractController>()
    private var fallbackShown = false

    private fun deliver(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun steamController(productId: Int, transport: SteamControllerTransport, key: String): SteamController =
        SteamController(nextDeviceId++, this, productId, transport, key,
            prefConfig.steamControllerGameTouchpad, prefConfig.steamControllerGripMappings)

    private fun showSteamFallback() {
        if (!fallbackShown) {
            fallbackShown = true
            Toast.makeText(this, R.string.steam_controller_fallback, Toast.LENGTH_LONG).show()
        }
    }

    override fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float
    ) {
        deliver { listener?.reportControllerState(
            controllerId,
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger
        ) }
    }

    override fun reportControllerMotion(
        controllerId: Int,
        motionType: Byte,
        motionX: Float,
        motionY: Float,
        motionZ: Float
    ) {
        deliver { listener?.reportControllerMotion(controllerId, motionType, motionX, motionY, motionZ) }
    }

    override fun deviceRemoved(controller: AbstractController) {
        deliver {
            addedControllers.remove(controller)
            if (controller !is SteamController || controller.isClosed()) controllers.remove(controller)
            listener?.deviceRemoved(controller)
        }
    }

    override fun deviceAdded(controller: AbstractController) {
        deliver {
            if (!started || controller is SteamController && !controller.hasNativeOwnership()) return@deliver
            addedControllers.add(controller)
            listener?.deviceAdded(controller)
        }
    }

    override fun reportControllerTouch(controllerId: Int, eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float) {
        deliver { listener?.reportControllerTouch(controllerId, eventType, pointerId, x, y, pressure) }
    }

    override fun reportControllerMouse(controllerId: Int, deltaX: Short, deltaY: Short, buttons: Int, scroll: Short) {
        deliver { listener?.reportControllerMouse(controllerId, deltaX, deltaY, buttons, scroll) }
    }

    override fun reportControllerQuickMenu(controllerId: Int) {
        deliver { listener?.reportControllerQuickMenu(controllerId) }
    }

    override fun resetControllerState(controllerId: Int) {
        deliver { listener?.resetControllerState(controllerId) }
    }

    inner class UsbEventReceiver : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                mainHandler.postDelayed({ if (started) handleUsbDeviceState(device) }, 1000)
            } else if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                usbControllers.remove(device.deviceName)?.toList()?.forEach { controller ->
                    controller.stop()
                    controllers.remove(controller)
                }
            } else if (action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                stateListener?.onUsbPermissionPromptCompleted()
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    handleUsbDeviceState(device)
                } else if (device?.vendorId == SteamControllerProtocol.VENDOR_ID) {
                    showSteamFallback()
                }
            }
        }
    }

    inner class UsbDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@UsbDriverService.listener = listener
            if (listener != null) {
                for (controller in addedControllers.toList()) listener.deviceAdded(controller)
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            this@UsbDriverService.stateListener = stateListener
        }

        fun start() {
            this@UsbDriverService.start()
        }

        fun stop() {
            this@UsbDriverService.stop()
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice?) {
        if (device == null || !started || !prefConfig.usbDriver || usbControllers.containsKey(device.deviceName)) {
            return
        }
        if (shouldClaimDevice(device, prefConfig.bindAllUsb)) {
            if (!usbManager.hasPermission(device)) {
                try {
                    stateListener?.onUsbPermissionPromptStarting()
                    var intentFlags = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        intentFlags = intentFlags or PendingIntent.FLAG_MUTABLE
                    }

                    val intent = Intent(ACTION_USB_PERMISSION)
                    intent.setPackage(packageName)
                    usbManager.requestPermission(
                        device,
                        PendingIntent.getBroadcast(this@UsbDriverService, 0, intent, intentFlags)
                    )
                } catch (_: SecurityException) {
                    Toast.makeText(this, getText(R.string.error_usb_prohibited), Toast.LENGTH_LONG).show()
                    stateListener?.onUsbPermissionPromptCompleted()
                }
                return
            }

            if (SteamControllerUsbTransport.canClaimDevice(device)) {
                // Android exposes no physical parent for lizard InputDevices. Refuse an
                // ambiguous group rather than suppress another controller's keyboard/mouse.
                if (usbManager.deviceList.values.count { it.vendorId == device.vendorId && it.productId == device.productId } != 1) {
                    usbControllers.entries.filter { (_, pads) -> pads.any { it.getProductId() == device.productId } }
                        .map { it.key }.forEach { key ->
                            usbControllers.remove(key)?.toList()?.forEach { it.stop(); controllers.remove(it) }
                        }
                    showSteamFallback()
                    return
                }
                val channels = mutableListOf<AbstractController>()
                usbControllers[device.deviceName] = channels
                for (interfaceId in SteamControllerUsbTransport.candidateInterfaceIds(device)) {
                    val connection = try { usbManager.openDevice(device) } catch (_: SecurityException) { null } ?: continue
                    val controller = steamController(device.productId,
                        SteamControllerUsbTransport(device, connection, interfaceId), "usb:${device.deviceName}:$interfaceId")
                    channels.add(controller)
                    controllers.add(controller)
                    if (!controller.start()) { controllers.remove(controller); channels.remove(controller); showSteamFallback() }
                }
                if (channels.isEmpty()) usbControllers.remove(device.deviceName)
                return
            }

            val connection: UsbDeviceConnection? = usbManager.openDevice(device)
            if (connection == null) {
                LimeLog.warning("Unable to open USB device: " + device.deviceName)
                return
            }

            val controller = when {
                XboxOneController.canClaimDevice(device) -> XboxOneController(device, connection, nextDeviceId++, this)
                Xbox360Controller.canClaimDevice(device) -> Xbox360Controller(device, connection, nextDeviceId++, this)
                Xbox360WirelessDongle.canClaimDevice(device) -> Xbox360WirelessDongle(device, connection, nextDeviceId++, this)
                ProConController.canClaimDevice(device) -> ProConController(device, connection, nextDeviceId++, this)
                else -> { connection.close(); return }
            }

            controllers.add(controller)
            usbControllers[device.deviceName] = mutableListOf(controller)
            if (!controller.start()) {
                connection.close()
                controllers.remove(controller)
                usbControllers.remove(device.deviceName)
                return
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun start() {
        if (started) {
            return
        }

        started = true
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        for (device in usbManager.deviceList.values) {
            if (shouldClaimDevice(device, prefConfig.bindAllUsb)) {
                handleUsbDeviceState(device)
            }
        }
        startPairedSteamControllers()
    }

    @SuppressLint("MissingPermission")
    private fun startPairedSteamControllers() {
        if (!prefConfig.steamControllerBluetooth) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.steam_controller_permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
            if (!adapter.isEnabled) return
            val paired = adapter.bondedDevices.filter { device ->
                val name = device.name.orEmpty()
                name.contains("Steam Controller", ignoreCase = true) || name.contains("SteamCtrl", ignoreCase = true)
            }
            // Without a stable Android InputDevice-to-GATT association, two paired
            // controllers cannot safely share the lizard suppression group.
            if (paired.size > 1) { showSteamFallback(); return }
            for (device in paired) {
                val controller = steamController(SteamControllerProtocol.BLUETOOTH_PID,
                    SteamControllerBleTransport(applicationContext, device), "ble:${device.address}")
                controllers.add(controller)
                if (!controller.start()) { controllers.remove(controller); showSteamFallback() }
            }
        } catch (_: SecurityException) { showSteamFallback() }
    }

    private fun stop() {
        if (!started) {
            return
        }

        started = false
        unregisterReceiver(receiver)
        mainHandler.removeCallbacksAndMessages(null)
        while (controllers.size > 0) {
            controllers.removeAt(0).stop()
        }
        usbControllers.clear()
        addedControllers.clear()
    }

    override fun onCreate() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefConfig = PreferenceConfiguration.readPreferences(this)
    }

    override fun onDestroy() {
        stop()
        listener = null
        stateListener = null
    }

    override fun onBind(intent: Intent): IBinder = binder

    interface UsbDriverStateListener {
        fun onUsbPermissionPromptStarting()
        fun onUsbPermissionPromptCompleted()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.papi.nova.USB_PERMISSION"

        @JvmStatic
        fun isRecognizedInputDevice(device: UsbDevice): Boolean {
            for (id in InputDevice.getDeviceIds()) {
                val inputDevice = InputDevice.getDevice(id) ?: continue
                if (inputDevice.vendorId == device.vendorId && inputDevice.productId == device.productId) {
                    return true
                }
            }
            return false
        }

        private fun hasKernelGamepad(device: UsbDevice): Boolean = InputDevice.getDeviceIds().any { id ->
            val input = InputDevice.getDevice(id)
            input != null && input.vendorId == device.vendorId && input.productId == device.productId &&
                ((input.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    (input.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
        }

        @JvmStatic
        fun kernelSupportsXboxOne(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            LimeLog.info("Kernel Version: $kernelVersion")
            return when {
                kernelVersion == null -> true
                kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") -> false
                kernelVersion.startsWith("4.4.") || kernelVersion.startsWith("4.9.") -> false
                else -> true
            }
        }

        @JvmStatic
        fun kernelSupportsXbox360W(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            if (kernelVersion != null) {
                if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") ||
                    kernelVersion.startsWith("4.0.") || kernelVersion.startsWith("4.1.")
                ) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun shouldClaimDevice(device: UsbDevice, claimAllAvailable: Boolean): Boolean {
            LimeLog.info("UsbDevice info: $device")
            return (SteamControllerUsbTransport.canClaimDevice(device) && !hasKernelGamepad(device)) || ((!kernelSupportsXboxOne() || !isRecognizedInputDevice(device) || claimAllAvailable) &&
                XboxOneController.canClaimDevice(device)) ||
                ((!isRecognizedInputDevice(device) || claimAllAvailable) && Xbox360Controller.canClaimDevice(device)) ||
                ((!kernelSupportsXbox360W() || claimAllAvailable) && Xbox360WirelessDongle.canClaimDevice(device)) ||
                ((!isRecognizedInputDevice(device) || claimAllAvailable) && ProConController.canClaimDevice(device))
        }
    }
}
