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
    POLARIS_UNAVAILABLE
}

data class PolarisStartupResult(
    val status: PolarisStartupStatus,
    val computer: ComputerDetails? = null
)

class PolarisStartupCoordinator(
    private val wakeSender: WakeSender,
    private val hostPoller: HostPoller,
    private val polarisProbe: PolarisProbe,
    private val sleeper: Sleeper = ThreadSleeper
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
        if (current.state != ComputerDetails.State.ONLINE) {
            if (current.macAddress.isNullOrBlank()) {
                return PolarisStartupResult(PolarisStartupStatus.MISSING_MAC, current)
            }
            try {
                wakeSender.wake(current)
            } catch (_: IOException) {
                return PolarisStartupResult(PolarisStartupStatus.WAKE_FAILED, current)
            }
        }

        var sawOnlineHost = current.state == ComputerDetails.State.ONLINE
        val attempts = maxPollAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (current.state != ComputerDetails.State.ONLINE) {
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

        return if (sawOnlineHost) {
            PolarisStartupResult(PolarisStartupStatus.POLARIS_UNAVAILABLE, current)
        } else {
            PolarisStartupResult(PolarisStartupStatus.TIMEOUT, current)
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
    }
}
