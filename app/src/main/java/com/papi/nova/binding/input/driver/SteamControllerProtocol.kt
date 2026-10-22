/*
 * Protocol layout and scaling adapted from Simple DirectMedia Layer, commit
 * d0183f0ee3b2497f49c6322c62354d114c22af29, SDL_hidapi_steam_triton.c and
 * steam/controller_structs.h. This is an altered Kotlin implementation.
 * Copyright (C) 1997-2026 Sam Lantinga <slouken@libsdl.org>
 * Copyright (C) 2020 Valve Corporation
 *
 * This software is provided 'as-is', without any express or implied warranty.
 * In no event will the authors be held liable for any damages arising from
 * the use of this software.
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */
package com.papi.nova.binding.input.driver

import com.papi.nova.nvstream.input.ControllerPacket

/** Raw reports include their HID report ID on both USB and the adapted BLE path. */
internal object SteamControllerProtocol {
    const val VENDOR_ID = 0x28de
    const val WIRED_PID = 0x1302
    const val BLUETOOTH_PID = 0x1303
    const val PUCK_PID = 0x1304
    const val CONTROLLER_TYPE: Byte = 4
    const val STATE_SIZE = 46

    data class Vector(val x: Float, val y: Float, val z: Float)
    data class Pad(val touching: Boolean, val clicking: Boolean, val x: Float, val y: Float, val pressure: Float)
    data class State(
        val sequence: Int,
        val buttons: Int,
        val quickMenu: Boolean,
        val leftTrigger: Float,
        val rightTrigger: Float,
        val leftX: Float,
        val leftY: Float,
        val rightX: Float,
        val rightY: Float,
        val leftPad: Pad,
        val rightPad: Pad,
        val gyro: Vector,
        val acceleration: Vector,
        val sensorTimestamp: Long,
        val sensorTimestampBits: Int,
    )

    private val buttonMap = arrayOf(
        0x00000001 to ControllerPacket.A_FLAG,
        0x00000002 to ControllerPacket.B_FLAG,
        0x00000004 to ControllerPacket.X_FLAG,
        0x00000008 to ControllerPacket.Y_FLAG,
        0x00000020 to ControllerPacket.RS_CLK_FLAG,
        // Valve's View/Menu mapping follows SDL, not the button's visual position.
        0x00000040 to ControllerPacket.PLAY_FLAG,
        0x00000080 to ControllerPacket.PADDLE1_FLAG,
        0x00000100 to ControllerPacket.PADDLE3_FLAG,
        0x00000200 to ControllerPacket.RB_FLAG,
        0x00000400 to ControllerPacket.DOWN_FLAG,
        0x00000800 to ControllerPacket.RIGHT_FLAG,
        0x00001000 to ControllerPacket.LEFT_FLAG,
        0x00002000 to ControllerPacket.UP_FLAG,
        0x00004000 to ControllerPacket.BACK_FLAG,
        0x00008000 to ControllerPacket.LS_CLK_FLAG,
        0x00010000 to ControllerPacket.SPECIAL_BUTTON_FLAG,
        0x00020000 to ControllerPacket.PADDLE2_FLAG,
        0x00040000 to ControllerPacket.PADDLE4_FLAG,
        0x00080000 to ControllerPacket.LB_FLAG,
    )
    val supportedButtons: Int = buttonMap.fold(0) { result, pair -> result or pair.second }

    fun state(report: ByteArray): State? {
        if (report.size < STATE_SIZE || report.size > 64) return null
        val id = u8(report, 0)
        if (id != 0x42 && id != 0x45 && id != 0x47) return null
        val raw = u16(report, 2) or (u16(report, 4) shl 16)
        val buttons = buttonMap.fold(0) { result, pair ->
            if (raw and pair.first != 0) result or pair.second else result
        }
        // 0x47 adds a two-byte pad timestamp and uses a two-byte IMU timestamp.
        // Both packed report layouts place the actual accelerometer/gyro at 34/40.
        val padOffset = if (id == 0x47) 20 else 18
        val timestamp = if (id == 0x47) u16(report, 32).toLong() else u32(report, 30)
        fun pad(offset: Int, touch: Int, click: Int) = Pad(
            raw and touch != 0,
            raw and click != 0,
            s16(report, offset) / 65536f + 0.5f,
            -s16(report, offset + 2) / 65536f + 0.5f,
            (u16(report, offset + 4) / 32768f).coerceIn(0f, 1f),
        )
        val gyroScale = 2000f / 32768f // Protocol motion uses degrees/second, not SDL radians.
        val accelScale = 2f * 9.80665f / 32768f
        return State(
            u8(report, 1), buttons, raw and 0x10 != 0,
            axis(s16(report, 6)).coerceAtLeast(0f), axis(s16(report, 8)).coerceAtLeast(0f),
            axis(s16(report, 10)), axis(-s16(report, 12)),
            axis(s16(report, 14)), axis(-s16(report, 16)),
            pad(padOffset, 0x02000000, 0x04000000),
            pad(padOffset + 6, 0x00200000, 0x00400000),
            Vector(s16(report, 40) * gyroScale, s16(report, 44) * gyroScale, -s16(report, 42) * gyroScale),
            Vector(s16(report, 34) * accelScale, s16(report, 38) * accelScale, -s16(report, 36) * accelScale),
            timestamp, if (id == 0x47) 16 else 32,
        )
    }

    /** null means unrelated or truncated; a wireless disconnect isn't a transport close. */
    fun wirelessConnected(report: ByteArray): Boolean? {
        if (report.size < 2 || report.size > 64) return null
        if (u8(report, 0) != 0x46 && u8(report, 0) != 0x79) return null
        return when (u8(report, 1)) { 1 -> false; 2 -> true; else -> null }
    }

    fun lizardMode(enabled: Boolean): ByteArray = setting(9, if (enabled) 1 else 0)
    fun motionEnabled(enabled: Boolean): ByteArray = setting(48, if (enabled) 0x18 else 0)

    private fun setting(number: Int, value: Int): ByteArray = ByteArray(64).also {
        it[0] = 1
        it[1] = 0x87.toByte()
        it[2] = 3
        it[3] = number.toByte()
        put16(it, 4, value)
    }

    fun rumble(low: Short, high: Short): ByteArray = ByteArray(10).also {
        it[0] = 0x80.toByte()
        // Type/intensity/gains remain zero; speed is a full unsigned 16-bit value.
        put16(it, 4, low.toInt() and 0xffff)
        put16(it, 7, high.toInt() and 0xffff)
    }

    private fun axis(value: Int): Float = (value / 32767f).coerceIn(-1f, 1f)
    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xff
    private fun u16(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)
    private fun s16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()
    private fun u32(data: ByteArray, offset: Int): Long =
        u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)
    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }
}
