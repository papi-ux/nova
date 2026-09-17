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
