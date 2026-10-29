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
import java.util.UUID

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
    // An owner outside the stream opens only Steam Controllers Android already allows.
    private var promptForPermission = true
    private var steamControllersOnly = false
    /** What the current owner started with, so the same request again keeps its channels. */
    private var ownedSettings: OwnedSettings? = null
    private data class SteamUsbPermissionRequest(
        val owner: UsbDriverListener, val deviceName: String, val vendorId: Int, val productId: Int, val token: String,
    )
    private var steamUsbPermissionRequest: SteamUsbPermissionRequest? = null

    /** USB access is not proof of a native report, a player assignment, or working hardware. */
    enum class SteamControllerUsbAccessStatus {
        USB_DISABLED, OWNER_INACTIVE, NO_DEVICE, AMBIGUOUS, ANDROID_GAMEPAD,
        NEEDS_PERMISSION, REQUESTING_PERMISSION, PERMISSION_GRANTED, NATIVE_INPUT_ACTIVE, REQUEST_FAILED,
    }

    private fun steamUsbSetupDevice(owner: UsbDriverListener): Pair<SteamControllerUsbAccessStatus, UsbDevice?> {
        if (Looper.myLooper() != Looper.getMainLooper() || !started || listener !== owner ||
            !steamControllersOnly || promptForPermission) return SteamControllerUsbAccessStatus.OWNER_INACTIVE to null
        if (!prefConfig.usbDriver) return SteamControllerUsbAccessStatus.USB_DISABLED to null
        val devices = try {
            usbManager.deviceList.values.filter { SteamControllerUsbTransport.canClaimDevice(it) }
        } catch (_: SecurityException) { return SteamControllerUsbAccessStatus.REQUEST_FAILED to null }
        if (devices.isEmpty()) return SteamControllerUsbAccessStatus.NO_DEVICE to null
        if (devices.size != 1) return SteamControllerUsbAccessStatus.AMBIGUOUS to null
        val device = devices.single()
        if (hasKernelGamepad(device)) return SteamControllerUsbAccessStatus.ANDROID_GAMEPAD to null
        if (SteamControllerUsbTransport.candidateInterfaceIds(device).isEmpty())
            return SteamControllerUsbAccessStatus.REQUEST_FAILED to null
        return SteamControllerUsbAccessStatus.NEEDS_PERMISSION to device
    }

    private fun steamUsbAccessStatus(owner: UsbDriverListener): SteamControllerUsbAccessStatus {
        val (status, device) = steamUsbSetupDevice(owner)
        if (device == null) return status
        return try {
            when {
                usbManager.hasPermission(device) -> if (usbControllers[device.deviceName].orEmpty().any {
                    it is SteamController && it.hasNativeOwnership()
                }) SteamControllerUsbAccessStatus.NATIVE_INPUT_ACTIVE else SteamControllerUsbAccessStatus.PERMISSION_GRANTED
                steamUsbPermissionRequest?.let { it.owner === owner && it.deviceName == device.deviceName &&
                    it.vendorId == device.vendorId && it.productId == device.productId } == true ->
                    SteamControllerUsbAccessStatus.REQUESTING_PERMISSION
                else -> SteamControllerUsbAccessStatus.NEEDS_PERMISSION
            }
        } catch (_: SecurityException) { SteamControllerUsbAccessStatus.REQUEST_FAILED }
    }

    /** Only an explicit library action may call this; passive discovery still never prompts. */
    private fun requestSteamUsbAccess(owner: UsbDriverListener): SteamControllerUsbAccessStatus {
        val status = steamUsbAccessStatus(owner)
        if (status != SteamControllerUsbAccessStatus.NEEDS_PERMISSION) return status
        val device = steamUsbSetupDevice(owner).second ?: return steamUsbAccessStatus(owner)
        val request = SteamUsbPermissionRequest(owner, device.deviceName, device.vendorId, device.productId,
            UUID.randomUUID().toString())
        steamUsbPermissionRequest = request
        return try {
            val intent = Intent(ACTION_STEAM_USB_SETUP_PERMISSION).setPackage(packageName)
                .putExtra(EXTRA_STEAM_USB_SETUP_TOKEN, request.token)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            SteamControllerUsbAccessStatus.REQUESTING_PERMISSION
        } catch (_: SecurityException) {
            steamUsbPermissionRequest = null
            SteamControllerUsbAccessStatus.REQUEST_FAILED
        } catch (_: IllegalArgumentException) {
            // The receiver may have been unplugged after the current-device snapshot.
            steamUsbPermissionRequest = null
            SteamControllerUsbAccessStatus.REQUEST_FAILED
        }
    }

    private fun completeSteamUsbSetup(intent: Intent) {
        val request = steamUsbPermissionRequest ?: return
        if (intent.getStringExtra(EXTRA_STEAM_USB_SETUP_TOKEN) != request.token) return
        steamUsbPermissionRequest = null
        @Suppress("DEPRECATION")
        val returned = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (returned.deviceName != request.deviceName || returned.vendorId != request.vendorId ||
            returned.productId != request.productId ||
            !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
        val current = steamUsbSetupDevice(request.owner).second ?: return
        if (current.deviceName != request.deviceName || current.vendorId != request.vendorId ||
            current.productId != request.productId) return
        try {
            if (usbManager.hasPermission(current)) handleUsbDeviceState(current)
        } catch (_: SecurityException) { /* Permission was revoked; a later status read reports it. */ }
        // This is not a stream permission request: never touch a replacement Game's PiP listener.
    }

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

    override fun nativeControllerUnavailable(controllerId: Int) {
        deliver {
            val usbName = usbControllers.entries.firstOrNull { (_, pads) ->
                pads.any { it.getControllerId() == controllerId }
            }?.key
            controllers.removeAll { it.getControllerId() == controllerId }
            // A physical unplug may reach the read thread before its broadcast. It
            // is ordinary detach, not a connection failure the player needs to fix.
            if (started && (usbName == null || usbManager.deviceList.containsKey(usbName))) showSteamFallback()
        }
    }

    override fun controllerInputActive(controllerId: Int) {
        deliver { listener?.controllerInputActive(controllerId) }
    }

    inner class UsbEventReceiver : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null && steamUsbPermissionRequest?.deviceName == device.deviceName)
                    steamUsbPermissionRequest = null
                mainHandler.postDelayed({ if (started) handleUsbDeviceState(device) }, 1000)
            } else if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                if (steamUsbPermissionRequest?.deviceName == device.deviceName) steamUsbPermissionRequest = null
                usbControllers.remove(device.deviceName)?.toList()?.forEach { controller ->
                    controller.stop()
                    controllers.remove(controller)
                }
                if (device.vendorId == SteamControllerProtocol.VENDOR_ID) mainHandler.postDelayed({
                    if (started) usbManager.deviceList.values.filter { it.vendorId == device.vendorId &&
                        it.productId == device.productId }.forEach(::handleUsbDeviceState)
                }, 1200)
            } else if (action == ACTION_STEAM_USB_SETUP_PERMISSION) {
                completeSteamUsbSetup(intent)
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
            if (this@UsbDriverService.listener !== listener) steamUsbPermissionRequest = null
            this@UsbDriverService.listener = listener
            if (listener != null) {
                for (controller in addedControllers.toList()) listener.deviceAdded(controller)
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            this@UsbDriverService.stateListener = stateListener
        }

        /** Passive status for the currently resumed, permission-silent Steam USB library owner. */
        fun steamControllerUsbAccessStatus(owner: UsbDriverListener): SteamControllerUsbAccessStatus =
            steamUsbAccessStatus(owner)

        /** One explicit click asks Android for one unambiguous receiver; it changes no preference. */
        fun requestSteamControllerUsbAccess(owner: UsbDriverListener): SteamControllerUsbAccessStatus =
            requestSteamUsbAccess(owner)

        fun start() {
            this@UsbDriverService.start()
        }

        /** Launch settings already include the selected per-game profile. */
        fun start(config: PreferenceConfiguration) {
            if (!started) prefConfig = driverConfig(config)
            this@UsbDriverService.start()
        }

        /**
         * Gives the driver to [owner] with [config]. Another owner, or new settings, first retire
         * every channel the service holds while the old listener still hears their removal; the
         * new channels then open with [owner]'s settings, such as a game's own touchpad and grip
         * profile. The same owner and settings again change nothing.
         *
         * Without [promptForPermission] only devices Android already allows are opened, and with
         * [steamControllersOnly] an Xbox or Pro Controller stays an ordinary Android gamepad.
         */
        fun startOwned(
            owner: UsbDriverListener,
            stateListener: UsbDriverStateListener?,
            config: PreferenceConfiguration,
            promptForPermission: Boolean = true,
            steamControllersOnly: Boolean = false,
        ) {
            val settings = OwnedSettings.of(config, promptForPermission, steamControllersOnly)
            if (started && listener === owner && ownedSettings == settings) {
                this@UsbDriverService.stateListener = stateListener
                return
            }
            this@UsbDriverService.stop()
            this@UsbDriverService.listener = owner
            this@UsbDriverService.stateListener = stateListener
            prefConfig = driverConfig(config)
            this@UsbDriverService.promptForPermission = promptForPermission
            this@UsbDriverService.steamControllersOnly = steamControllersOnly
            ownedSettings = settings
            this@UsbDriverService.start()
        }

        fun stop() {
            this@UsbDriverService.stop()
        }

        /** A retiring Game must not stop channels now owned by another bound Game. */
        fun stop(owner: UsbDriverListener?) {
            if (owner != null && listener === owner) this@UsbDriverService.stop()
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice?) {
        if (device == null || !started || !prefConfig.usbDriver || usbControllers.containsKey(device.deviceName)) {
            return
        }
        if (!usbManager.deviceList.containsKey(device.deviceName)) return
        if (steamControllersOnly && !SteamControllerUsbTransport.canClaimDevice(device)) return
        if (shouldClaimDevice(device, prefConfig.bindAllUsb)) {
            if (!usbManager.hasPermission(device)) {
                if (!promptForPermission) return
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
        filter.addAction(ACTION_STEAM_USB_SETUP_PERMISSION)
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
        if (!prefConfig.steamControllerBluetooth) {
            LimeLog.info("Steam Controller BLE: off")
            return
        }
        if (hasKernelGamepad(SteamControllerProtocol.VENDOR_ID, SteamControllerProtocol.BLUETOOTH_PID)) {
            LimeLog.info("Steam Controller BLE: kernel gamepad")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            LimeLog.warning("Steam Controller BLE: permission denied")
            Toast.makeText(this, R.string.steam_controller_permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter?.isEnabled != true) {
                LimeLog.info("Steam Controller BLE: adapter off")
                return
            }
            val paired = adapter.bondedDevices.filter { device ->
                isSteamControllerBluetoothName(device.name)
            }
            LimeLog.info("Steam Controller BLE: ${paired.size} bonded candidates")
            // Without a stable Android InputDevice-to-GATT association, two paired
            // controllers cannot safely share the lizard suppression group.
            if (paired.size > 1) {
                LimeLog.warning("Steam Controller BLE: ambiguous bonded controllers")
                showSteamFallback()
                return
            }
            for (device in paired) {
                LimeLog.info("Steam Controller BLE: connecting")
                val controller = steamController(SteamControllerProtocol.BLUETOOTH_PID,
                    SteamControllerBleTransport(applicationContext, device), "ble:${device.address}")
                controllers.add(controller)
                if (!controller.start()) { controllers.remove(controller); showSteamFallback() }
            }
        } catch (_: SecurityException) {
            LimeLog.warning("Steam Controller BLE: permission denied")
            showSteamFallback()
        }
    }

    private fun stop() {
        steamUsbPermissionRequest = null
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
        // A later start(config) is a stream's again: it may ask for access and claim any pad.
        promptForPermission = true
        steamControllersOnly = false
        ownedSettings = null
    }

    /** The driver's own copy of [config], so a later change to it cannot reach open channels. */
    private fun driverConfig(config: PreferenceConfiguration) = PreferenceConfiguration().apply {
        usbDriver = config.usbDriver
        bindAllUsb = config.bindAllUsb
        steamControllerBluetooth = config.steamControllerBluetooth
        steamControllerGameTouchpad = config.steamControllerGameTouchpad
        steamControllerGripMappings = config.steamControllerGripMappings.copyOf()
    }

    /** The settings channels opened with under [UsbDriverBinder.startOwned]. */
    private data class OwnedSettings(
        val usbDriver: Boolean,
        val bindAllUsb: Boolean,
        val bluetooth: Boolean,
        val gameTouchpad: Boolean,
        val gripMappings: List<Int>,
        val promptForPermission: Boolean,
        val steamControllersOnly: Boolean,
    ) {
        companion object {
            fun of(config: PreferenceConfiguration, promptForPermission: Boolean, steamControllersOnly: Boolean) =
                OwnedSettings(config.usbDriver, config.bindAllUsb, config.steamControllerBluetooth,
                    config.steamControllerGameTouchpad, config.steamControllerGripMappings.toList(),
                    promptForPermission, steamControllersOnly)
        }
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
        internal fun isSteamControllerBluetoothName(name: String?): Boolean =
            name != null && (name.contains("Steam Controller", ignoreCase = true) ||
                name.contains("SteamCtrl", ignoreCase = true) || name.startsWith("Steam Ctrl", ignoreCase = true))

        private const val ACTION_USB_PERMISSION = "com.papi.nova.USB_PERMISSION"
        private const val ACTION_STEAM_USB_SETUP_PERMISSION = "com.papi.nova.STEAM_USB_SETUP_PERMISSION"
        private const val EXTRA_STEAM_USB_SETUP_TOKEN = "com.papi.nova.STEAM_USB_SETUP_TOKEN"

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

        private fun hasKernelGamepad(device: UsbDevice): Boolean = hasKernelGamepad(device.vendorId, device.productId)

        private fun hasKernelGamepad(vendorId: Int, productId: Int): Boolean = InputDevice.getDeviceIds().any { id ->
            val input = InputDevice.getDevice(id)
            input != null && input.vendorId == vendorId && input.productId == productId &&
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
