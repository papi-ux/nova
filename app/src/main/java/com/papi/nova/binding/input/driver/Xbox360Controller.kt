package com.papi.nova.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.input.ControllerPacket
import java.nio.ByteBuffer

class Xbox360Controller(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractXboxController(device, connection, deviceId, listener) {
    private fun unsignByte(value: Byte): Int = if (value < 0) value + 256 else value.toInt()

    override fun handleRead(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 14) {
            LimeLog.severe("Read too small: " + buffer.remaining())
            return false
        }

        buffer.position(buffer.position() + 2)

        var byteValue = buffer.get().toInt()
        setButtonFlag(ControllerPacket.LEFT_FLAG, byteValue and 0x04)
        setButtonFlag(ControllerPacket.RIGHT_FLAG, byteValue and 0x08)
        setButtonFlag(ControllerPacket.UP_FLAG, byteValue and 0x01)
        setButtonFlag(ControllerPacket.DOWN_FLAG, byteValue and 0x02)
        setButtonFlag(ControllerPacket.PLAY_FLAG, byteValue and 0x10)
        setButtonFlag(ControllerPacket.BACK_FLAG, byteValue and 0x20)
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, byteValue and 0x40)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, byteValue and 0x80)

        byteValue = buffer.get().toInt()
        setButtonFlag(ControllerPacket.A_FLAG, byteValue and 0x10)
        setButtonFlag(ControllerPacket.B_FLAG, byteValue and 0x20)
        setButtonFlag(ControllerPacket.X_FLAG, byteValue and 0x40)
        setButtonFlag(ControllerPacket.Y_FLAG, byteValue and 0x80)
        setButtonFlag(ControllerPacket.LB_FLAG, byteValue and 0x01)
        setButtonFlag(ControllerPacket.RB_FLAG, byteValue and 0x02)
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, byteValue and 0x04)

        leftTrigger = unsignByte(buffer.get()) / 255.0f
        rightTrigger = unsignByte(buffer.get()) / 255.0f
        leftStickX = buffer.short / 32767.0f
        leftStickY = buffer.short.toInt().inv() / 32767.0f
        rightStickX = buffer.short / 32767.0f
        rightStickY = buffer.short.toInt().inv() / 32767.0f
        return true
    }

    private fun sendLedCommand(command: Byte): Boolean {
        val commandBuffer = byteArrayOf(0x01, 0x03, command)
        val result = connection.bulkTransfer(outEndpt, commandBuffer, commandBuffer.size, 3000)
        if (result != commandBuffer.size) {
            LimeLog.warning("LED set transfer failed: $result")
            return false
        }
        return true
    }

    override fun doInit(): Boolean {
        sendLedCommand((2 + (getControllerId() % 4)).toByte())
        return true
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val data = byteArrayOf(
            0x00,
            0x08,
            0x00,
            (lowFreqMotor.toInt() shr 8).toByte(),
            (highFreqMotor.toInt() shr 8).toByte(),
            0x00,
            0x00,
            0x00
        )
        val result = connection.bulkTransfer(outEndpt, data, data.size, 100)
        if (result != data.size) {
            LimeLog.warning("Rumble transfer failed: $result")
        }
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit

    companion object {
        private const val XB360_IFACE_SUBCLASS = 93
        private const val XB360_IFACE_PROTOCOL = 1
        private val SUPPORTED_VENDORS = intArrayOf(
            0x0079, 0x044f, 0x045e, 0x046d, 0x056e, 0x06a3, 0x0738, 0x07ff,
            0x0e6f, 0x0f0d, 0x1038, 0x11c9, 0x1209, 0x12ab, 0x1430, 0x146b,
            0x1532, 0x15e4, 0x162e, 0x1689, 0x1949, 0x1bad, 0x20d6, 0x24c6,
            0x2f24, 0x2dc8, 0x413d, 0x3537
        )

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean =
            SUPPORTED_VENDORS.any { vendor ->
                device.vendorId == vendor &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB360_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB360_IFACE_PROTOCOL
            }
    }
}
