package com.papi.nova.binding.input.driver

import android.view.InputDevice

/** Only native-owned Valve lizard interfaces are filtered; other pads/keyboards are untouched. */
internal object SteamControllerOwnership {
    private val sources = mutableMapOf<String, Int>()

    @Synchronized fun acquire(key: String, productId: Int) { sources[key] = productId }
    @Synchronized fun release(key: String) { sources.remove(key) }

    fun isLizard(device: InputDevice): Boolean =
        device.vendorId == SteamControllerProtocol.VENDOR_ID &&
            device.productId in setOf(SteamControllerProtocol.WIRED_PID, SteamControllerProtocol.BLUETOOTH_PID, SteamControllerProtocol.PUCK_PID) &&
            (device.sources and InputDevice.SOURCE_CLASS_JOYSTICK) == 0 &&
            (device.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
            (device.sources and (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE)) != 0

    @Synchronized fun owns(device: InputDevice?): Boolean =
        device != null && isLizard(device) && sources.values.any { it == device.productId }
}
