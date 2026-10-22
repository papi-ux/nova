package com.papi.nova.binding.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.papi.nova.nvstream.input.ControllerPacket
import java.util.concurrent.atomic.AtomicInteger

/** In-process gamepad keys for one native channel and the Nova window that owns its presses. */
internal class NativeControllerUiKeys {
    private val deviceId = nextDeviceId.decrementAndGet()
    private val handler = Handler(Looper.getMainLooper())
    private var target: Any? = null
    private var deliver: ((KeyEvent) -> Unit)? = null
    private var physical = emptySet<Int>()
    private val suppressed = mutableSetOf<Int>()
    private val presses = linkedMapOf<Int, Long>()
    private val repeats = mutableMapOf<Int, Runnable>()

    fun suppress(buttonFlags: Int, leftStickX: Float = 0f, leftStickY: Float = 0f) {
        cancelPresses()
        physical = keys(buttonFlags, leftStickX, leftStickY)
        suppressed.addAll(physical)
    }

    fun update(target: Any, buttonFlags: Int, leftStickX: Float, leftStickY: Float,
               dispatch: (KeyEvent) -> Unit) {
        val next = keys(buttonFlags, leftStickX, leftStickY)
        val previouslyHeld = physical
        physical = next
        if (this.target !== target) {
            cancelPresses()
            suppressed.addAll(previouslyHeld)
            this.target = target
        }
        deliver = dispatch
        suppressed.retainAll(next)
        val accepted = next - suppressed
        for (key in presses.keys.toList()) if (key !in accepted) {
            release(key, canceled = false)
            if (this.target !== target) return
        }
        for (key in accepted) if (key !in presses) {
            val now = SystemClock.uptimeMillis()
            presses[key] = now
            dispatch(event(key, KeyEvent.ACTION_DOWN, now))
            if (this.target !== target) return
            if (key in DIRECTIONS && presses.containsKey(key)) repeat(key, target)
        }
    }

    fun cancel() {
        cancelPresses()
        target = null
        deliver = null
        physical = emptySet()
        suppressed.clear()
    }

    private fun cancelPresses() {
        for (key in presses.keys.toList()) release(key, canceled = true)
    }

    private fun release(key: Int, canceled: Boolean) {
        val down = presses.remove(key) ?: return
        repeats.remove(key)?.let(handler::removeCallbacks)
        deliver?.invoke(event(key, KeyEvent.ACTION_UP, down,
            flags = if (canceled) KeyEvent.FLAG_CANCELED else 0))
    }

    private fun repeat(key: Int, owner: Any) {
        var count = 0
        val tick = object : Runnable {
            override fun run() {
                val down = presses[key] ?: return
                if (target !== owner) return
                deliver?.invoke(event(key, KeyEvent.ACTION_DOWN, down, repeat = ++count))
                if (target === owner && key in presses) handler.postDelayed(this, 90)
            }
        }
        repeats[key] = tick
        handler.postDelayed(tick, 400)
    }

    private fun event(key: Int, action: Int, down: Long, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(down, SystemClock.uptimeMillis(), action, key, repeat, 0,
            deviceId, 0, flags, InputDevice.SOURCE_GAMEPAD)

    private fun keys(buttons: Int, x: Float, y: Float): Set<Int> = buildSet {
        for ((flag, key) in BUTTONS) if (buttons and flag != 0) add(key)
        if (x <= -0.5f) add(KeyEvent.KEYCODE_DPAD_LEFT)
        if (x >= 0.5f) add(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (y <= -0.5f) add(KeyEvent.KEYCODE_DPAD_UP)
        if (y >= 0.5f) add(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    companion object {
        private val nextDeviceId = AtomicInteger(-1024)
        private val DIRECTIONS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        private val BUTTONS = listOf(
            ControllerPacket.A_FLAG to KeyEvent.KEYCODE_BUTTON_A,
            ControllerPacket.B_FLAG to KeyEvent.KEYCODE_BUTTON_B,
            ControllerPacket.X_FLAG to KeyEvent.KEYCODE_BUTTON_X,
            ControllerPacket.Y_FLAG to KeyEvent.KEYCODE_BUTTON_Y,
            ControllerPacket.LB_FLAG to KeyEvent.KEYCODE_BUTTON_L1,
            ControllerPacket.RB_FLAG to KeyEvent.KEYCODE_BUTTON_R1,
            ControllerPacket.PLAY_FLAG to KeyEvent.KEYCODE_BUTTON_START,
            ControllerPacket.BACK_FLAG to KeyEvent.KEYCODE_BUTTON_SELECT,
            ControllerPacket.SPECIAL_BUTTON_FLAG to KeyEvent.KEYCODE_BUTTON_MODE,
            ControllerPacket.LS_CLK_FLAG to KeyEvent.KEYCODE_BUTTON_THUMBL,
            ControllerPacket.RS_CLK_FLAG to KeyEvent.KEYCODE_BUTTON_THUMBR,
            ControllerPacket.UP_FLAG to KeyEvent.KEYCODE_DPAD_UP,
            ControllerPacket.DOWN_FLAG to KeyEvent.KEYCODE_DPAD_DOWN,
            ControllerPacket.LEFT_FLAG to KeyEvent.KEYCODE_DPAD_LEFT,
            ControllerPacket.RIGHT_FLAG to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
