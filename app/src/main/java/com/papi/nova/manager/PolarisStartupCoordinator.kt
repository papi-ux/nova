package com.papi.nova.manager

import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import java.io.IOException

enum class PolarisStartupStatus {
    READY,
    NEEDS_PAIRING,
    MISSING_MAC,
    WAKE_FAILED,
    TIMEOUT,
    POLARIS_UNAVAILABLE,
    POLARIS_NOT_RUNNING
}

data class PolarisStartupResult(
    val status: PolarisStartupStatus,
    val computer: ComputerDetails? = null
)

/**
 * Wake Host: the one thing a client can do for a host it cannot reach is send a wake
 * packet, then wait for Polaris to answer. It cannot start Polaris on a machine that
 * is already awake, so it says which of the two it is looking at instead of pretending.
 */
class PolarisStartupCoordinator(
    private val wakeSender: WakeSender,
    private val hostPoller: HostPoller,
    private val polarisProbe: PolarisProbe,
    private val sleeper: Sleeper = ThreadSleeper,
    private val reachabilityProbe: HostReachabilityProbe = HostReachabilityProbe.ASSUME_ASLEEP
) {
    interface WakeSender {
        @Throws(IOException::class)
        fun wake(computer: ComputerDetails)
    }

    interface HostPoller {
        fun poll(computer: ComputerDetails): ComputerDetails
    }

    interface PolarisProbe {
        fun hasGameLibrary(computer: ComputerDetails): Boolean
    }

    /**
     * Whether the machine itself is up, independent of Polaris. A host that is awake
     * with nothing listening refuses a TCP connect in milliseconds; a sleeping or
     * powered-off one answers nothing.
     */
    interface HostReachabilityProbe {
        fun isAwake(computer: ComputerDetails): Boolean

        companion object {
            /** The pre-probe behaviour: treat every offline host as asleep and wake it. */
            val ASSUME_ASLEEP: HostReachabilityProbe = object : HostReachabilityProbe {
                override fun isAwake(computer: ComputerDetails): Boolean = false
            }
        }
    }

    interface Sleeper {
        fun sleep(delayMs: Long)
    }

    fun start(
        computer: ComputerDetails,
        maxPollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
        pollDelayMs: Long = DEFAULT_POLL_DELAY_MS
    ): PolarisStartupResult {
        if (computer.pairState != PairingManager.PairState.PAIRED || computer.serverCert == null) {
            return PolarisStartupResult(PolarisStartupStatus.NEEDS_PAIRING, computer)
        }

        var current = ComputerDetails(computer)
        var hostAwake = current.state == ComputerDetails.State.ONLINE
        if (!hostAwake) {
            // Only a machine that answers nothing is a wake packet's job. One that refuses
            // the connect is up already; its Polaris is down, and no packet fixes that.
            hostAwake = reachabilityProbe.isAwake(current)
            if (!hostAwake) {
                if (current.macAddress.isNullOrBlank()) {
                    return PolarisStartupResult(PolarisStartupStatus.MISSING_MAC, current)
                }
                try {
                    wakeSender.wake(current)
                } catch (_: IOException) {
                    return PolarisStartupResult(PolarisStartupStatus.WAKE_FAILED, current)
                }
            }
        }

        var sawOnlineHost = current.state == ComputerDetails.State.ONLINE
        // A sleeping host needs the full budget to boot. An awake one with Polaris down
        // only needs long enough to catch a service that is starting right now; past
        // that, waiting is just delaying the answer.
        val budget = if (hostAwake && !sawOnlineHost) minOf(maxPollAttempts, AWAKE_POLL_ATTEMPTS) else maxPollAttempts
        val attempts = budget.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            // The cached state can be stale: Polaris may have quit since the list last
            // polled. Re-poll on every pass after the first so a host that went down is
            // noticed, instead of blaming its library for thirty seconds.
            if (attempt > 0 || current.state != ComputerDetails.State.ONLINE) {
                current = hostPoller.poll(current)
            }
            if (current.state == ComputerDetails.State.ONLINE) {
                sawOnlineHost = true
                if (polarisProbe.hasGameLibrary(current)) {
                    current.libraryState = ComputerDetails.LibraryState.AVAILABLE
                    return PolarisStartupResult(PolarisStartupStatus.READY, current)
                }
                current.libraryState = ComputerDetails.LibraryState.UNAVAILABLE
            }
            if (attempt < attempts - 1 && pollDelayMs > 0) {
                sleeper.sleep(pollDelayMs)
            }
        }

        return when {
            // Still answering as a host, just without a Polaris library: a plain Moonlight host.
            current.state == ComputerDetails.State.ONLINE ->
                PolarisStartupResult(PolarisStartupStatus.POLARIS_UNAVAILABLE, current)
            // The machine is up (it was online a moment ago, or it refused the connect) but
            // nothing answers as a host now: Polaris is not running there.
            hostAwake || sawOnlineHost ->
                PolarisStartupResult(PolarisStartupStatus.POLARIS_NOT_RUNNING, current)
            else -> PolarisStartupResult(PolarisStartupStatus.TIMEOUT, current)
        }
    }

    private object ThreadSleeper : Sleeper {
        override fun sleep(delayMs: Long) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_ATTEMPTS = 12
        const val DEFAULT_POLL_DELAY_MS = 2500L
        /** Four polls at the default delay is ten seconds, twice the service's own start delay. */
        const val AWAKE_POLL_ATTEMPTS = 4
    }
}
