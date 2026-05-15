package com.papi.nova.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.view.InputDevice
import com.papi.nova.LimeLog

class Xbox360WirelessDongle(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {
    private fun sendLedCommandToEndpoint(endpoint: UsbEndpoint, controllerIndex: Int) {
        val commandBuffer = byteArrayOf(
            0x00,
            0x00,
            0x08,
            (0x40 + (2 + (controllerIndex % 4))).toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
        val result = connection.bulkTransfer(endpoint, commandBuffer, commandBuffer.size, 3000)
        if (result != commandBuffer.size) {
            LimeLog.warning("LED set transfer failed: $result")
        }
    }

    private fun sendLedCommandToInterface(iface: UsbInterface, controllerIndex: Int) {
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim interface: " + iface.id)
            return
        }

        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                sendLedCommandToEndpoint(endpoint, controllerIndex)
                break
            }
        }

        connection.releaseInterface(iface)
    }

    override fun start(): Boolean {
        var controllerIndex = 0

        for (id in InputDevice.getDeviceIds()) {
            val inputDevice = InputDevice.getDevice(id) ?: continue
            if (inputDevice.vendorId == device.vendorId &&
                (inputDevice.productId == device.productId || inputDevice.productId == 0x02a1) &&
                inputDevice.controllerNumber > 0
            ) {
                controllerIndex = inputDevice.controllerNumber - 1
                break
            }
        }

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                iface.interfaceSubclass != XB360W_IFACE_SUBCLASS ||
                iface.interfaceProtocol != XB360W_IFACE_PROTOCOL
            ) {
                continue
            }
            sendLedCommandToInterface(iface, controllerIndex++)
        }

        return false
    }

    override fun stop() = Unit

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = Unit

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit

    companion object {
        private const val XB360W_IFACE_SUBCLASS = 93
        private const val XB360W_IFACE_PROTOCOL = 129
        private val SUPPORTED_VENDORS = intArrayOf(0x045e)

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean =
            SUPPORTED_VENDORS.any { vendor ->
                device.vendorId == vendor &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB360W_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB360W_IFACE_PROTOCOL
            }
    }
}
