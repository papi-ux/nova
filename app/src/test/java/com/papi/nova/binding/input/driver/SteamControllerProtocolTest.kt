package com.papi.nova.binding.input.driver

import com.papi.nova.nvstream.input.ControllerPacket
import org.junit.Assert.*
import org.junit.Test

class SteamControllerProtocolTest {
    private fun report(id: Int = 0x42) = ByteArray(46).also { it[0] = id.toByte() }
    private fun short(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    @Test fun shortAndUnboundedReportsNeverProduceState() {
        for (length in 0 until 46) assertNull(SteamControllerProtocol.state(ByteArray(length) { 0x42 }))
        assertNull(SteamControllerProtocol.state(ByteArray(65) { 0x42 }))
    }

    @Test fun otherHidReportsCannotMoveControls() {
        for (id in listOf(0, 1, 0x43, 0x46, 0x79, 0x80)) {
            assertNull(SteamControllerProtocol.state(report(id)))
        }
    }

    @Test fun usbAndBleDecodeTheSamePayload() {
        val usb = report().also { it[1] = 0x45; it[2] = 1; short(it, 10, -32768) }
        val ble = usb.copyOf().also { it[0] = 0x45 }
        assertEquals(SteamControllerProtocol.state(usb), SteamControllerProtocol.state(ble))
        assertEquals(0x45, SteamControllerProtocol.state(ble)!!.sequence)
    }

    @Test fun independentFaceAndGripBitsReachTheirProtocolFields() {
        val bytes = report()
        // A, R4 and L5: raw 0x00040081, protocol A plus paddles 1 and 4.
        bytes[2] = 0x81.toByte(); bytes[4] = 4
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(ControllerPacket.A_FLAG or ControllerPacket.PADDLE1_FLAG or ControllerPacket.PADDLE4_FLAG, state.buttons)
        assertFalse(state.quickMenu)
    }

    @Test fun viewMenuAndQuickMenuAreNotConflated() {
        val bytes = report().also { it[2] = 0x50; it[3] = 0x40 }
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG, state.buttons)
        assertTrue(state.quickMenu)
        assertEquals(0, state.buttons and ControllerPacket.MISC_FLAG)
    }

    @Test fun nativeCapacitiveAndPadClickBitsAreNotInventedGamepadButtons() {
        val bytes = report().also { it[4] = 0x70; it[5] = 0x77 }
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(0, state.buttons)
        assertTrue(state.leftPad.touching); assertTrue(state.rightPad.touching)
        assertTrue(state.leftPad.clicking); assertTrue(state.rightPad.clicking)
    }

    @Test fun negativeAndPositiveAxisExtremesStayInRange() {
        val bytes = report()
        short(bytes, 6, -1); short(bytes, 8, 32767)
        short(bytes, 10, -32768); short(bytes, 12, -32768)
        short(bytes, 14, 32767); short(bytes, 16, 32767)
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(0f, state.leftTrigger, 0f); assertEquals(1f, state.rightTrigger, 0f)
        assertEquals(-1f, state.leftX, 0f); assertEquals(1f, state.leftY, 0f)
        assertEquals(1f, state.rightX, 0f); assertEquals(-1f, state.rightY, 0f)
    }

    @Test fun bothPadsKeepTheirCoordinatesAndPressureSeparate() {
        val bytes = report()
        short(bytes, 18, -32768); short(bytes, 20, 32767); short(bytes, 22, 16384)
        short(bytes, 24, 32767); short(bytes, 26, -32768); short(bytes, 28, 65535)
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(0f, state.leftPad.x, 0f); assertTrue(state.leftPad.y < 0.0001f)
        assertEquals(0.5f, state.leftPad.pressure, 0f)
        assertTrue(state.rightPad.x > 0.9999f); assertEquals(1f, state.rightPad.y, 0f)
        assertEquals(1f, state.rightPad.pressure, 0f)
    }

    @Test fun newTimestampReportDoesNotInterpretThePadTimestampAsCoordinates() {
        val bytes = report(0x47)
        short(bytes, 18, 0x1234); short(bytes, 20, -32768); short(bytes, 22, 32767)
        short(bytes, 24, 8192); short(bytes, 26, 16384); short(bytes, 28, -16384)
        short(bytes, 30, 16384); short(bytes, 32, 0xfedc)
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(0f, state.leftPad.x, 0f); assertEquals(0.25f, state.leftPad.pressure, 0f)
        assertEquals(0.75f, state.rightPad.x, 0f); assertEquals(0.75f, state.rightPad.y, 0f)
        assertEquals(0.5f, state.rightPad.pressure, 0f)
        assertEquals(0xfedcL, state.sensorTimestamp); assertEquals(16, state.sensorTimestampBits)
    }

    @Test fun oldSensorTimestampIsUnsigned() {
        val bytes = report().also { short(it, 30, 0xfedc); short(it, 32, 0xba98) }
        val state = SteamControllerProtocol.state(bytes)!!
        assertEquals(0xba98fedcL, state.sensorTimestamp); assertEquals(32, state.sensorTimestampBits)
    }

    @Test fun sensorsUseHostUnitsAndTheSdlAxisOrientation() {
        for (id in listOf(0x42, 0x45, 0x47)) {
            val bytes = report(id)
            short(bytes, 34, 16384); short(bytes, 36, -16384); short(bytes, 38, -32768)
            short(bytes, 40, 16384); short(bytes, 42, -32768); short(bytes, 44, -16384)
            val state = SteamControllerProtocol.state(bytes)!!
            assertEquals(SteamControllerProtocol.Vector(1000f, -1000f, 2000f), state.gyro)
            assertEquals(9.80665f, state.acceleration.x, 0.00001f)
            assertEquals(-19.6133f, state.acceleration.y, 0.00001f)
            assertEquals(9.80665f, state.acceleration.z, 0.00001f)
        }
    }

    @Test fun wirelessStateUsesValvesConnectAndDisconnectValues() {
        for (id in listOf(0x46, 0x79)) {
            assertEquals(false, SteamControllerProtocol.wirelessConnected(byteArrayOf(id.toByte(), 1)))
            assertEquals(true, SteamControllerProtocol.wirelessConnected(byteArrayOf(id.toByte(), 2)))
            assertNull(SteamControllerProtocol.wirelessConnected(byteArrayOf(id.toByte(), 0)))
            assertNull(SteamControllerProtocol.wirelessConnected(byteArrayOf(id.toByte())))
        }
        assertNull(SteamControllerProtocol.wirelessConnected(byteArrayOf(0x42, 1)))
    }

    @Test fun settingsAreFullFeatureReportsAndDoNotOverwriteOtherSettings() {
        val off = SteamControllerProtocol.lizardMode(false)
        assertEquals(64, off.size)
        assertArrayEquals(byteArrayOf(1, 0x87.toByte(), 3, 9, 0, 0), off.copyOf(6))
        assertTrue(off.drop(6).all { it == 0.toByte() })
        assertEquals(1, SteamControllerProtocol.lizardMode(true)[4].toInt())
        assertArrayEquals(byteArrayOf(1, 0x87.toByte(), 3, 48, 0x18, 0), SteamControllerProtocol.motionEnabled(true).copyOf(6))
        assertEquals(0, SteamControllerProtocol.motionEnabled(false)[4].toInt())
    }

    @Test fun rumbleUsesSeparateFullUnsignedSpeedsAndZeroGains() {
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0x34, 0x12, 0, 0xdc.toByte(), 0xfe.toByte(), 0),
            SteamControllerProtocol.rumble(0x1234, 0xfedc.toShort()),
        )
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0), SteamControllerProtocol.rumble(0, 0))
    }
}
