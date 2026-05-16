package com.papi.nova.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.jni.MoonBridge
import java.nio.ByteBuffer
import java.util.Arrays

class XboxOneController(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractXboxController(device, connection, deviceId, listener) {
    private var seqNum: Byte = 0
    private var lowFreqMotor: Short = 0
    private var highFreqMotor: Short = 0
    private var leftTriggerMotor: Short = 0
    private var rightTriggerMotor: Short = 0

    init {
        capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_TRIGGER_RUMBLE.toInt()).toShort()
    }

    private fun processButtons(buffer: ByteBuffer) {
        var byteValue = buffer.get().toInt()
        setButtonFlag(ControllerPacket.PLAY_FLAG, byteValue and 0x04)
        setButtonFlag(ControllerPacket.BACK_FLAG, byteValue and 0x08)
        setButtonFlag(ControllerPacket.A_FLAG, byteValue and 0x10)
        setButtonFlag(ControllerPacket.B_FLAG, byteValue and 0x20)
        setButtonFlag(ControllerPacket.X_FLAG, byteValue and 0x40)
        setButtonFlag(ControllerPacket.Y_FLAG, byteValue and 0x80)

        byteValue = buffer.get().toInt()
        setButtonFlag(ControllerPacket.LEFT_FLAG, byteValue and 0x04)
        setButtonFlag(ControllerPacket.RIGHT_FLAG, byteValue and 0x08)
        setButtonFlag(ControllerPacket.UP_FLAG, byteValue and 0x01)
        setButtonFlag(ControllerPacket.DOWN_FLAG, byteValue and 0x02)
        setButtonFlag(ControllerPacket.LB_FLAG, byteValue and 0x10)
        setButtonFlag(ControllerPacket.RB_FLAG, byteValue and 0x20)
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, byteValue and 0x40)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, byteValue and 0x80)

        leftTrigger = buffer.short / 1023.0f
        rightTrigger = buffer.short / 1023.0f
        leftStickX = buffer.short / 32767.0f
        leftStickY = buffer.short.toInt().inv() / 32767.0f
        rightStickX = buffer.short / 32767.0f
        rightStickY = buffer.short.toInt().inv() / 32767.0f
    }

    private fun ackModeReport(seqNum: Byte) {
        val payload = byteArrayOf(
            0x01, 0x20, seqNum, 0x09, 0x00, 0x07, 0x20, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        connection.bulkTransfer(outEndpt, payload, payload.size, 3000)
    }

    override fun handleRead(buffer: ByteBuffer): Boolean {
        when (buffer.get().toInt()) {
            0x20 -> {
                if (buffer.remaining() < 17) {
                    LimeLog.severe("XBone button/axis read too small: " + buffer.remaining())
                    return false
                }
                buffer.position(buffer.position() + 3)
                processButtons(buffer)
                return true
            }
            0x07 -> {
                if (buffer.remaining() < 4) {
                    LimeLog.severe("XBone mode read too small: " + buffer.remaining())
                    return false
                }
                if (buffer.get().toInt() == 0x30) {
                    ackModeReport(buffer.get())
                    buffer.position(buffer.position() + 1)
                } else {
                    buffer.position(buffer.position() + 2)
                }
                setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get().toInt() and 0x01)
                return true
            }
        }
        return false
    }

    override fun doInit(): Boolean {
        for (packet in INIT_PKTS) {
            if (packet.vendorId != 0 && device.vendorId != packet.vendorId) {
                continue
            }
            if (packet.productId != 0 && device.productId != packet.productId) {
                continue
            }

            val data = Arrays.copyOf(packet.data, packet.data.size)
            data[2] = seqNum++
            val result = connection.bulkTransfer(outEndpt, data, data.size, 3000)
            if (result != data.size) {
                LimeLog.warning("Initialization transfer failed: $result")
                return false
            }
        }
        return true
    }

    private fun sendRumblePacket() {
        val data = byteArrayOf(
            0x09,
            0x00,
            seqNum++,
            0x09,
            0x00,
            0x0F,
            (leftTriggerMotor.toInt() shr 9).toByte(),
            (rightTriggerMotor.toInt() shr 9).toByte(),
            (lowFreqMotor.toInt() shr 9).toByte(),
            (highFreqMotor.toInt() shr 9).toByte(),
            0xFF.toByte(),
            0x00,
            0xFF.toByte()
        )
        val result = connection.bulkTransfer(outEndpt, data, data.size, 100)
        if (result != data.size) {
            LimeLog.warning("Rumble transfer failed: $result")
        }
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        this.lowFreqMotor = lowFreqMotor
        this.highFreqMotor = highFreqMotor
        sendRumblePacket()
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        leftTriggerMotor = leftTrigger
        rightTriggerMotor = rightTrigger
        sendRumblePacket()
    }

    private class InitPacket(val vendorId: Int, val productId: Int, val data: ByteArray)

    companion object {
        private const val XB1_IFACE_SUBCLASS = 71
        private const val XB1_IFACE_PROTOCOL = 208
        private val SUPPORTED_VENDORS = intArrayOf(
            0x045e, 0x0738, 0x0e6f, 0x0f0d, 0x1532, 0x20d6, 0x24c6, 0x2e24, 0x3537, 0x2dc8
        )
        private val FW2015_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x01, 0x00)
        private val ONE_S_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x0f, 0x06)
        private val HORI_INIT = byteArrayOf(
            0x01, 0x20, 0x00, 0x09, 0x00, 0x04, 0x20, 0x3a,
            0x00, 0x00, 0x00, 0x80.toByte(), 0x00
        )
        private val PDP_INIT1 = byteArrayOf(0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14)
        private val PDP_INIT2 = byteArrayOf(0x06, 0x20, 0x00, 0x02, 0x01, 0x00)
        private val RUMBLE_INIT1 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x1D, 0x1D, 0xFF.toByte(), 0x00, 0x00
        )
        private val RUMBLE_INIT2 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val INIT_PKTS = arrayOf(
            InitPacket(0x0e6f, 0x0165, HORI_INIT),
            InitPacket(0x0f0d, 0x0067, HORI_INIT),
            InitPacket(0x0000, 0x0000, FW2015_INIT),
            InitPacket(0x045e, 0x02ea, ONE_S_INIT),
            InitPacket(0x045e, 0x0b00, ONE_S_INIT),
            InitPacket(0x0e6f, 0x0000, PDP_INIT1),
            InitPacket(0x0e6f, 0x0000, PDP_INIT2),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT2),
            InitPacket(0x045e, 0x0b12, ONE_S_INIT),
            InitPacket(0x045e, 0x02fe, ONE_S_INIT),
            InitPacket(0x3537, 0x1012, ONE_S_INIT)
        )

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean =
            SUPPORTED_VENDORS.any { vendor ->
                device.vendorId == vendor &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB1_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB1_IFACE_PROTOCOL
            }
    }
}
