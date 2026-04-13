package com.papi.nova.manager

import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages connection resilience for Polaris streaming sessions.
 *
 * When a stream error occurs (typically error -1), instead of immediately
 * showing an error dialog (Moonlight default), this manager:
 *
 * 1. Absorbs the error immediately (non-blocking on native thread)
 * 2. Schedules a background check of server session status with backoff
 * 3. If alive → reconnects; if dead or max retries → lets Moonlight handle it
 *
 * Backoff sequence: 0ms, 1000ms, 3000ms, 7000ms (4 attempts max)
 */
class ConnectionResilienceManager @JvmOverloads constructor(
    private val apiClient: PolarisApiClient,
    private val onReconnect: Runnable,
    private val onGiveUp: Runnable? = null
) {
    private val backoffDelaysMs = longArrayOf(0, 1000, 3000, 7000)
    private var attemptIndex = 0
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Nova-Resilience").apply { isDaemon = true }
    }

    /**
     * Called from the JNI pre-termination hook.
     * Returns immediately — all I/O and waiting happens on a background thread.
     *
     * @param errorCode The Moonlight error code
     * @return true if the error should be absorbed (reconnect scheduled)
     */
    fun shouldAbsorbError(errorCode: Int): Boolean {
        if (!FeatureFlagManager.isPolarisServer) return false
        if (attemptIndex >= backoffDelaysMs.size) {
            LimeLog.info("Nova: Max reconnect attempts reached, showing error")
            return false
        }

        val delay = backoffDelaysMs[attemptIndex]
        attemptIndex++
        val attempt = attemptIndex

        LimeLog.info("Nova: Absorbing error $errorCode, scheduling reconnect check " +
            "in ${delay}ms (attempt $attempt/${backoffDelaysMs.size})")

        // Schedule the status check + reconnect on the background executor
        executor.schedule({
            try {
                val status = try {
                    apiClient.getSessionStatus()
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Session status query failed: ${e.message}")
                    null
                }

                if (status != null && status.isSessionAlive) {
                    LimeLog.info("Nova: Server session alive (state=${status.state}), reconnecting")
                    onReconnect.run()
                } else {
                    LimeLog.info("Nova: Server session not alive (status=${status?.state ?: "unreachable"})")
                    onGiveUp?.run()
                }
            } catch (e: Exception) {
                LimeLog.warning("Nova: Resilience check failed: ${e.message}")
                onGiveUp?.run()
            }
        }, delay, TimeUnit.MILLISECONDS)

        return true
    }

    /** Call on successful reconnect to reset the backoff counter */
    fun onReconnectSuccess() {
        if (attemptIndex > 0) {
            LimeLog.info("Nova: Reconnect succeeded, resetting backoff")
        }
        attemptIndex = 0
    }

    /** Call when the user dismisses the stream (normal disconnect) */
    fun onUserDisconnect() {
        attemptIndex = 0
    }

    /** Shutdown the background executor */
    fun shutdown() {
        executor.shutdownNow()
    }
}
