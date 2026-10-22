package com.papi.nova.binding.input.driver

import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.jni.MoonBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One physical controller channel, including one of a Puck's four independent interfaces. */
internal class SteamController(
    id: Int,
    private val events: UsbDriverListener,
    productId: Int,
    private val transport: SteamControllerTransport,
    val sourceKey: String,
    private val gameTouchpad: Boolean = false,
    gripMappings: IntArray = IntArray(4),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "Nova-SteamController").apply { isDaemon = true }
    },
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractController(id, events, SteamControllerProtocol.VENDOR_ID, productId), SteamControllerTransport.Observer {
    private val lock = Any()
    private val grips = gripMappings.copyOf(4)
    private var started = false
    private var closed = false
    private var linkReady = false
    private var nativeReady = false
    private var added = false
    private var generation = 0L
    private var pendingState: SteamControllerProtocol.State? = null
    private var previousState: SteamControllerProtocol.State? = null
    private var quickMenuDown = false
    private val padDown = BooleanArray(2)
    private var mouseButtons = 0
    private var lowRumble: Short = 0
    private var highRumble: Short = 0
    private val motionRates = IntArray(2)
    private val lastMotion = LongArray(2)
    private var featurePending = false
    private var desiredMotion = false
    private var appliedMotion = false
    private var lastRefresh = 0L
    private var lastRumble = 0L
    private var controllerPresent = false
    private var activityNotified = false
    private var modeFailures = 0
    private var motionFailures = 0
    private var retryModeAt = 0L
    private var retryMotionAt = 0L

    init {
        type = SteamControllerProtocol.CONTROLLER_TYPE
        supportedButtonFlags = SteamControllerProtocol.supportedButtons and PADDLES.inv()
        for (mapping in grips) supportedButtonFlags = supportedButtonFlags or mapping
        if (gameTouchpad) supportedButtonFlags = supportedButtonFlags or ControllerPacket.TOUCHPAD_FLAG
        capabilities = (MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt() or
            MoonBridge.LI_CCAP_ACCEL.toInt() or MoonBridge.LI_CCAP_GYRO.toInt() or
            if (gameTouchpad) MoonBridge.LI_CCAP_TOUCHPAD.toInt() else 0).toShort()
    }

    fun hasNativeOwnership(): Boolean = synchronized(lock) { added && !closed }
    fun isClosed(): Boolean = synchronized(lock) { closed }

    override fun start(): Boolean = synchronized(lock) {
        if (started || closed) return false
        started = true
        if (!transport.start(this)) {
            closed = true
            transport.close()
            scheduler.shutdownNow()
            return false
        }
        if (!closed) scheduler.scheduleAtFixedRate({ maintain() }, 40, 40, TimeUnit.MILLISECONDS)
        !closed
    }

    override fun onReady() = synchronized(lock) {
        if (closed) return
        generation++
        linkReady = true
        nativeReady = false
        featurePending = false
        appliedMotion = false
        controllerPresent = getProductId() != SteamControllerProtocol.PUCK_PID
        modeFailures = 0; motionFailures = 0
        retryModeAt = 0; retryMotionAt = 0
        // An empty receiver interface can acknowledge a write without a controller.
        // Wait for that channel's actual connect/state before disabling lizard mode.
        if (getProductId() != SteamControllerProtocol.PUCK_PID) configureNativeMode()
    }

    private fun configureNativeMode() {
        if (closed || !linkReady || !controllerPresent || featurePending || nanoTime() < retryModeAt) return
        val epoch = generation
        featurePending = true
        transport.writeFeature(SteamControllerProtocol.lizardMode(false)) { ok ->
            synchronized(lock) {
                if (closed || generation != epoch) return@writeFeature
                featurePending = false
                if (!ok) {
                    modeFailures++
                    retryModeAt = nanoTime() + retryDelay(modeFailures)
                    if (modeFailures >= 3) {
                        losePlayer()
                        nativeReady = false
                    }
                    return@writeFeature
                }
                modeFailures = 0; retryModeAt = 0
                nativeReady = true
                lastRefresh = nanoTime()
                pendingState?.let { pendingState = null; publish(it) }
                configureMotion()
            }
        }
    }

    private fun configureMotion() {
        if (closed || !nativeReady || featurePending || desiredMotion == appliedMotion || nanoTime() < retryMotionAt) return
        val epoch = generation
        val wanted = desiredMotion
        featurePending = true
        transport.writeFeature(SteamControllerProtocol.motionEnabled(wanted)) { ok ->
            synchronized(lock) {
                if (closed || generation != epoch) return@writeFeature
                featurePending = false
                if (!ok) {
                    motionFailures++
                    retryMotionAt = nanoTime() + retryDelay(motionFailures)
                    return@writeFeature
                }
                motionFailures = 0; retryMotionAt = 0
                appliedMotion = wanted
                configureMotion()
            }
        }
    }

    override fun onReport(report: ByteArray) = synchronized(lock) {
        if (closed || !linkReady) return
        when (SteamControllerProtocol.wirelessConnected(report)) {
            false -> {
                generation++
                featurePending = false
                controllerPresent = false
                losePlayer()
                nativeReady = false
                return
            }
            true -> { controllerPresent = true; if (!nativeReady) configureNativeMode(); return }
            null -> Unit
        }
        val state = SteamControllerProtocol.state(report) ?: return
        controllerPresent = true
        if (nativeReady) publish(state) else {
            pendingState = state
            configureNativeMode()
        }
    }

    private fun publish(state: SteamControllerProtocol.State) {
        if (!added) {
            capabilities = ((capabilities.toInt() and MoonBridge.LI_CCAP_RUMBLE.toInt().inv()) or
                if (transport.supportsRumble()) MoonBridge.LI_CCAP_RUMBLE.toInt() else 0).toShort()
            added = true
            SteamControllerOwnership.acquire(sourceKey, getProductId())
            events.deviceAdded(this)
            if (closed || !added) return
        }
        if (!activityNotified && (state.leftPad.touching || state.rightPad.touching ||
                state.leftPad.clicking || state.rightPad.clicking)) {
            activityNotified = true
            events.controllerInputActive(getControllerId())
        }
        buttonFlags = mapGrips(state.buttons)
        if (gameTouchpad && (state.leftPad.clicking || state.rightPad.clicking)) {
            buttonFlags = buttonFlags or ControllerPacket.TOUCHPAD_FLAG
        }
        leftStickX = state.leftX; leftStickY = state.leftY
        rightStickX = state.rightX; rightStickY = state.rightY
        leftTrigger = state.leftTrigger; rightTrigger = state.rightTrigger
        reportInput()
        if (state.quickMenu && !quickMenuDown) events.reportControllerQuickMenu(getControllerId())
        quickMenuDown = state.quickMenu
        if (gameTouchpad) {
            touch(0, state.leftPad); touch(1, state.rightPad)
        } else {
            val old = previousState
            val dx = if (old?.rightPad?.touching == true && state.rightPad.touching) state.rightPad.x - old.rightPad.x else 0f
            val dy = if (old?.rightPad?.touching == true && state.rightPad.touching) state.rightPad.y - old.rightPad.y else 0f
            val scroll = if (old?.leftPad?.touching == true && state.leftPad.touching) old.leftPad.y - state.leftPad.y else 0f
            mouseButtons = (if (state.rightPad.clicking) ControllerPacket.A_FLAG else 0) or
                (if (state.leftPad.clicking) ControllerPacket.B_FLAG else 0)
            events.reportControllerMouse(getControllerId(), scaled(dx, 1200f), scaled(dy, 1200f), mouseButtons, scaled(scroll, 960f))
        }
        previousState = state
        val now = nanoTime()
        if (appliedMotion) {
            sendMotion(0, MoonBridge.LI_MOTION_TYPE_ACCEL, state.acceleration, now)
            sendMotion(1, MoonBridge.LI_MOTION_TYPE_GYRO, state.gyro, now)
        }
    }

    private fun mapGrips(buttons: Int): Int {
        var mapped = buttons and PADDLES.inv()
        for (i in 0..3) if (buttons and GRIP_FLAGS[i] != 0) mapped = mapped or grips[i]
        return mapped
    }

    private fun touch(index: Int, pad: SteamControllerProtocol.Pad) {
        if (pad.touching || padDown[index]) {
            val event = if (!pad.touching) MoonBridge.LI_TOUCH_EVENT_UP else if (padDown[index]) MoonBridge.LI_TOUCH_EVENT_MOVE else MoonBridge.LI_TOUCH_EVENT_DOWN
            // The pinned common-c API has no pad-index argument. Split one host pad and
            // use distinct contacts; do NOT advertise LI_CCAP_DUAL_TOUCHPAD.
            events.reportControllerTouch(getControllerId(), event, index, (pad.x + index) / 2f, pad.y, if (pad.touching) pad.pressure else 0f)
        }
        padDown[index] = pad.touching
    }

    private fun sendMotion(index: Int, kind: Byte, vector: SteamControllerProtocol.Vector, now: Long) {
        val rate = motionRates[index]
        if (rate == 0 || (lastMotion[index] != 0L && now - lastMotion[index] < 1_000_000_000L / rate)) return
        lastMotion[index] = now
        events.reportControllerMotion(getControllerId(), kind, vector.x, vector.y, vector.z)
    }

    override fun setMotionEventState(motionType: Byte, reportRateHz: Int) = synchronized(lock) {
        if (closed) return
        val index = when (motionType) { MoonBridge.LI_MOTION_TYPE_ACCEL -> 0; MoonBridge.LI_MOTION_TYPE_GYRO -> 1; else -> return }
        motionRates[index] = reportRateHz.coerceIn(0, 200)
        lastMotion[index] = 0
        desiredMotion = motionRates.any { it > 0 }
        configureMotion()
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = synchronized(lock) {
        if (closed || !added || !transport.supportsRumble()) return
        lowRumble = lowFreqMotor; highRumble = highFreqMotor
        transport.writeRumble(SteamControllerProtocol.rumble(lowRumble, highRumble))
        lastRumble = nanoTime()
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit

    private fun maintain() = synchronized(lock) {
        if (closed || !linkReady || !controllerPresent) return
        if (!nativeReady) { configureNativeMode(); return }
        val now = nanoTime()
        if (now - lastRefresh >= 3_000_000_000L) configureNativeMode()
        else configureMotion()
        if ((lowRumble.toInt() != 0 || highRumble.toInt() != 0) && now - lastRumble >= 40_000_000L) {
            transport.writeRumble(SteamControllerProtocol.rumble(lowRumble, highRumble))
            lastRumble = now
        }
    }

    override fun onLinkLost() = synchronized(lock) {
        if (closed) return
        generation++
        linkReady = false; nativeReady = false; featurePending = false; controllerPresent = false
        losePlayer()
    }

    override fun onClosed(failure: SteamControllerTransport.Failure) = synchronized(lock) { fail() }

    /** Reassign releases old contacts/feedback but keeps this physical channel owned. */
    fun releasePlayerInputs() = synchronized(lock) {
        if (closed) return
        previousState = null
        activityNotified = false
        events.reportControllerMouse(getControllerId(), 0, 0, 0, 0)
        for (i in 0..1) if (padDown[i]) {
            events.reportControllerTouch(getControllerId(), MoonBridge.LI_TOUCH_EVENT_UP, i, (0.5f + i) / 2f, 0.5f, 0f)
        }
        padDown.fill(false)
        lowRumble = 0; highRumble = 0
        if (linkReady && transport.supportsRumble()) transport.writeRumble(SteamControllerProtocol.rumble(0, 0))
        motionRates.fill(0); desiredMotion = false
        configureMotion()
    }

    private fun losePlayer() {
        SteamControllerOwnership.release(sourceKey)
        activityNotified = false
        pendingState = null; previousState = null; quickMenuDown = false
        motionRates.fill(0); lastMotion.fill(0); desiredMotion = false; appliedMotion = false
        lowRumble = 0; highRumble = 0
        if (!added) return
        buttonFlags = 0; leftTrigger = 0f; rightTrigger = 0f
        leftStickX = 0f; leftStickY = 0f; rightStickX = 0f; rightStickY = 0f
        events.resetControllerState(getControllerId())
        events.reportControllerMouse(getControllerId(), 0, 0, 0, 0)
        for (i in 0..1) if (padDown[i]) {
            events.reportControllerTouch(getControllerId(), MoonBridge.LI_TOUCH_EVENT_UP, i, (0.5f + i) / 2f, 0.5f, 0f)
        }
        padDown.fill(false); mouseButtons = 0
        events.reportControllerMotion(getControllerId(), MoonBridge.LI_MOTION_TYPE_GYRO, 0f, 0f, 0f)
        added = false
        events.deviceRemoved(this)
    }

    private fun fail(notify: Boolean = true) {
        if (closed) return
        generation++
        closed = true; nativeReady = false; linkReady = false
        losePlayer()
        transport.close()
        scheduler.shutdownNow()
        if (notify) events.nativeControllerUnavailable(getControllerId())
    }

    override fun stop() = synchronized(lock) {
        if (!closed && linkReady && transport.supportsRumble()) transport.writeRumble(SteamControllerProtocol.rumble(0, 0))
        fail(notify = false)
    }

    companion object {
        private val GRIP_FLAGS = intArrayOf(ControllerPacket.PADDLE1_FLAG, ControllerPacket.PADDLE2_FLAG, ControllerPacket.PADDLE3_FLAG, ControllerPacket.PADDLE4_FLAG)
        private val PADDLES = GRIP_FLAGS.fold(0) { mask, flag -> mask or flag }
        private fun scaled(delta: Float, scale: Float): Short = (delta * scale).toInt().coerceIn(-32767, 32767).toShort()
        private fun retryDelay(failures: Int): Long = 250_000_000L shl (failures - 1).coerceIn(0, 3)
    }
}
