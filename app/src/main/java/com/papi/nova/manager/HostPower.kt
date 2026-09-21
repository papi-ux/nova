package com.papi.nova.manager

import com.papi.nova.api.PolarisCapabilities

/**
 * What the host tile's power control should do right now.
 *
 * WAKE is the old one-way behavior and stays the fallback. A control that is
 * going to fail is worse than no control, so everything Nova cannot confirm
 * lands here instead of offering sleep: an unreachable host, a host that does
 * not speak host power at all, one whose owner has not turned sleep on, one
 * whose logind will refuse, and one this client may only watch.
 */
enum class HostPowerAction {
    WAKE,
    SLEEP,
}

object HostPowerPolicy {
    /**
     * @param reachable whether the host answered on its streaming port
     * @param capabilities what the host said it can do, or null when Nova has
     *   not asked yet or the host did not answer
     */
    @JvmStatic
    fun resolve(reachable: Boolean, capabilities: PolarisCapabilities?): HostPowerAction {
        if (!reachable) {
            return HostPowerAction.WAKE
        }
        val caps = capabilities ?: return HostPowerAction.WAKE
        // An older Polaris, or Sunshine, serves no host_power block at all.
        if (!caps.features.hostSleep) {
            return HostPowerAction.WAKE
        }
        val power = caps.hostPower
        val allowed = power.sleepSupported && power.sleepEnabled && power.sleepPermitted
        return if (allowed) HostPowerAction.SLEEP else HostPowerAction.WAKE
    }

    /**
     * What the control is called, which is a different question from what it may do.
     *
     * It was named for the action, so every host that did not offer sleep read Wake Host, and
     * that includes hosts that were plainly awake: a Sunshine host, an older Polaris, and, since
     * the host leaves host power out of what it tells a device assigned to a Space, every
     * handheld that plays in one. papi, 2026-09-21: "Wake Host should be Sleep Host if the
     * machine is already awake". A machine that answers is awake and the only thing left to
     * ask of it is sleep; one that does not answer can only be woken. Whether sleep will work
     * is still [resolve]'s answer, and where it will not the control says why instead of
     * trying.
     */
    @JvmStatic
    fun label(reachable: Boolean): HostPowerAction =
        if (reachable) HostPowerAction.SLEEP else HostPowerAction.WAKE

    /**
     * Why a host that speaks host power is not offering sleep, for a player who
     * wonders where Sleep Host went. Null when there is nothing to explain: the
     * host can sleep, Nova has not heard from it, it is not answering (Wake is
     * the whole story then), or it does not speak host power at all.
     */
    @JvmStatic
    fun unavailableReason(reachable: Boolean, capabilities: PolarisCapabilities?): HostSleepUnavailable? {
        if (!reachable) {
            return null
        }
        val caps = capabilities ?: return null
        if (!caps.features.hostSleep) {
            return null
        }
        val power = caps.hostPower
        return when {
            // The owner's choice first: nothing else matters until it is on, and
            // the console says whether the host could sleep right under it.
            !power.sleepEnabled -> HostSleepUnavailable.TurnedOff
            !power.sleepSupported -> HostSleepUnavailable.HostCannot(power.sleepBlockedMessage.trim())
            !power.sleepPermitted -> HostSleepUnavailable.WatchOnly
            else -> null
        }
    }
}

/** Why a reachable host that speaks host power offers Wake rather than Sleep. */
sealed interface HostSleepUnavailable {
    /** The owner has not turned on Allow Clients To Sleep This Host. */
    data object TurnedOff : HostSleepUnavailable

    /** The host cannot suspend for Polaris; its own sentence says why, and may be blank. */
    data class HostCannot(val message: String) : HostSleepUnavailable

    /** This device was paired to watch, not to control. */
    data object WatchOnly : HostSleepUnavailable
}

/**
 * One sleep request at a time, from the finished hold until the host has
 * answered. A second hold during the grace used to restart the countdown, and
 * one while Nova waited for the host to go down could send a second request
 * to a host already on its way. The control stays locked for the whole run.
 * Plain Kotlin, like HoldToConfirm, so the rule is tested rather than
 * demonstrated.
 */
class HostSleepSequence {
    enum class Phase {
        IDLE,
        COUNTING_DOWN,
        REQUESTING,
    }

    var phase: Phase = Phase.IDLE
        private set

    /** Counting down or waiting on the host. */
    val isBusy: Boolean
        get() = phase != Phase.IDLE

    /** @return false when a request is already counting down or out. */
    fun startCountdown(): Boolean {
        if (phase != Phase.IDLE) {
            return false
        }
        phase = Phase.COUNTING_DOWN
        return true
    }

    /** The grace ran out. @return false when it was called off first, so nothing may be sent. */
    fun countdownElapsed(): Boolean {
        if (phase != Phase.COUNTING_DOWN) {
            return false
        }
        phase = Phase.REQUESTING
        return true
    }

    /** The player cancelled, or left the screen. @return true when a countdown was stopped. */
    fun cancelCountdown(): Boolean {
        if (phase != Phase.COUNTING_DOWN) {
            return false
        }
        phase = Phase.IDLE
        return true
    }

    /** The host answered, or the request could not be sent at all. */
    fun finish() {
        phase = Phase.IDLE
    }
}

/**
 * Press and hold timing for a destructive control.
 *
 * Wake and sleep share one button in one place, so muscle memory on the way
 * into the library will eventually land on sleep. A tap cannot complete a
 * hold, which is the entire point. The timing lives here, away from Android,
 * so the rule that decides whether a press counted can be tested rather than
 * demonstrated on a device.
 */
class HoldToConfirm(private val holdMillis: Long = DEFAULT_HOLD_MILLIS) {
    private var pressedAtMillis: Long? = null

    val isHolding: Boolean
        get() = pressedAtMillis != null

    fun press(nowMillis: Long) {
        pressedAtMillis = nowMillis
    }

    /** 0f before a press, 1f once the press has lasted long enough to count. */
    fun progress(nowMillis: Long): Float {
        val start = pressedAtMillis ?: return 0f
        if (holdMillis <= 0L) {
            return 1f
        }
        val held = (nowMillis - start).coerceAtLeast(0L)
        return (held.toFloat() / holdMillis.toFloat()).coerceIn(0f, 1f)
    }

    fun isComplete(nowMillis: Long): Boolean = progress(nowMillis) >= 1f

    /** @return true when the press lasted long enough to count as a confirmation. */
    fun release(nowMillis: Long): Boolean {
        val completed = isComplete(nowMillis)
        pressedAtMillis = null
        return completed
    }

    fun cancel() {
        pressedAtMillis = null
    }

    companion object {
        /** Past any tap, short of a chore for a press somebody means. */
        const val DEFAULT_HOLD_MILLIS = 1000L

        /** How often the fill under the label is redrawn while holding. */
        const val HOLD_TICK_MILLIS = 50L

        /**
         * Grace between a completed hold and the request actually going out.
         * The host cannot be woken from the couch once it is down, so the undo
         * has to live in front of the request rather than after it.
         */
        const val SLEEP_GRACE_MILLIS = 5000L

        /**
         * After the request lands, the button waits for the host to stop
         * answering before it says Wake Host. Guessing from the response would
         * flip the label while the host is still on its way down, and a wake
         * packet sent then is a packet nothing hears.
         */
        const val SLEEP_CONFIRM_ATTEMPTS = 10
        const val SLEEP_CONFIRM_INTERVAL_MILLIS = 1000L

        /**
         * How long a host's answer about its own power is trusted. Long enough
         * not to be a poll, short enough that turning sleep on in the console
         * shows up in the app without restarting it.
         */
        const val POWER_PROBE_INTERVAL_MILLIS = 60000L
    }
}
