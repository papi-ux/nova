package com.papi.nova.manager

import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.jni.MoonBridge
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Manages connection resilience for Polaris streaming sessions.
 *
 * When moonlight-common-c ends a stream with an error, the native pre-termination hook asks this
 * manager first. An absorbed error never reaches Game.connectionTerminated, so every absorbed
 * error has to end in exactly one of the [Listener] outcomes: a reconnect, the host-ended
 * teardown, or the ordinary terminated dialog when the host never answers. Anything else leaves
 * the user on a frozen frame.
 *
 * 1. Absorbs the error immediately (non-blocking on the native thread)
 * 2. Checks the host session on a background thread, with backoff
 * 3. Still this stream: reconnect. Anything else: host-ended teardown. No answer: check again, then
 *    give the error back.
 *
 * "Still this stream" means the identity Game last saw for it while it played (see
 * [onSessionStatus]), not a state that reads alive: the host can still say streaming just after
 * it ended the stream, and reconnecting then would start the game it had just quit.
 *
 * The attempt budget spans relaunches: Game hands the attempts it already used to the next
 * instance, and only a stream that keeps rendering again earns a fresh budget (see
 * ReconnectRecoveryTracker). Errors a reconnect cannot help with are never absorbed.
 *
 * Backoff sequence: 0ms, 1000ms, 3000ms, 7000ms (4 attempts max)
 */
class ConnectionResilienceManager internal constructor(
    private val sessionStatus: () -> PolarisSessionStatus?,
    private val listener: Listener,
    private val featureScope: Long,
    startingAttempt: Int,
    private val scheduler: Scheduler,
) {
    interface Listener {
        /** The stream is down and a check of the host session is scheduled. */
        fun onReconnectPending(attempt: Int, maxAttempts: Int)

        /** The host still holds this stream: tear it down and resume it. */
        fun onReconnect(errorCode: Int, attempt: Int, maxAttempts: Int)

        /** The host answered and no longer holds this stream. */
        fun onHostSessionEnded()

        /** The host never answered: end the stream the ordinary way, with its Reconnect button. */
        fun onHostUnreachable(errorCode: Int)
    }

    internal interface Scheduler {
        /** Runs [task] after [delayMs]. Returns false once the scheduler is shut down. */
        fun schedule(delayMs: Long, task: Runnable): Boolean

        fun shutdown()
    }

    constructor(
        apiClient: PolarisApiClient,
        listener: Listener,
        featureScope: Long,
        startingAttempt: Int,
    ) : this({ apiClient.getSessionStatus() }, listener, featureScope, startingAttempt, ExecutorScheduler())

    private var attemptsUsed = startingAttempt.coerceIn(0, MAX_ATTEMPTS)

    @Volatile
    private var shutDown = false

    /** The last status that named this stream while it played. A failed read does not clear it. */
    @Volatile
    private var liveStream: PolarisSessionStatus? = null

    /** Game passes each session status it reads during the stream. */
    fun onSessionStatus(status: PolarisSessionStatus?) {
        if (status != null && namesLiveStream(status)) liveStream = status
    }

    /**
     * Called from the JNI pre-termination hook.
     * Returns immediately — all I/O and waiting happens on a background thread.
     *
     * @param errorCode The Moonlight error code
     * @return true if the error should be absorbed (a host check is scheduled)
     */
    fun shouldAbsorbError(errorCode: Int): Boolean {
        if (!isRecoverable(errorCode)) return false
        if (FeatureFlagManager.capabilitiesForScope(featureScope) == null) return false
        // Taken before any check: every read from here on describes the host, not this stream.
        return scheduleHostCheck(errorCode, liveStream)
    }

    /** Call once a reconnected stream has held steady, so a later drop gets a full budget. */
    fun onReconnectSuccess() {
        synchronized(this) {
            if (attemptsUsed == 0) return
            attemptsUsed = 0
        }
        LimeLog.info("Nova: Reconnect succeeded, resetting backoff")
    }

    /** Shutdown the background executor */
    fun shutdown() {
        shutDown = true
        scheduler.shutdown()
    }

    private fun scheduleHostCheck(errorCode: Int, droppedStream: PolarisSessionStatus?): Boolean {
        val attempt: Int
        val delayMs: Long
        synchronized(this) {
            if (shutDown) {
                LimeLog.info("Nova: Resilience manager is shut down, not absorbing error $errorCode")
                return false
            }
            if (attemptsUsed >= MAX_ATTEMPTS) {
                LimeLog.info("Nova: Max reconnect attempts reached, showing error")
                return false
            }
            delayMs = BACKOFF_DELAYS_MS[attemptsUsed]
            attemptsUsed++
            attempt = attemptsUsed
        }

        LimeLog.info(
            "Nova: Absorbing error $errorCode, checking the host session in ${delayMs}ms " +
                "(attempt $attempt/$MAX_ATTEMPTS)"
        )
        // Before scheduling: a check with no delay can reach onReconnect on another thread
        // before a later onReconnectPending would.
        listener.onReconnectPending(attempt, MAX_ATTEMPTS)
        if (!scheduler.schedule(delayMs) { checkHostSession(errorCode, attempt, droppedStream) }) {
            LimeLog.info("Nova: Resilience manager is shut down, not absorbing error $errorCode")
            return false
        }
        return true
    }

    private fun checkHostSession(errorCode: Int, attempt: Int, droppedStream: PolarisSessionStatus?) {
        val status = try {
            sessionStatus()
        } catch (e: Exception) {
            LimeLog.warning("Nova: Session status query failed: ${e.message}")
            null
        }

        try {
            when {
                status == null -> {
                    LimeLog.info("Nova: Host did not answer the session check (attempt $attempt/$MAX_ATTEMPTS)")
                    // The next check spends another attempt. When none are left, or the manager
                    // was shut down, the error goes back to the ordinary terminated path.
                    if (!scheduleHostCheck(errorCode, droppedStream)) {
                        listener.onHostUnreachable(errorCode)
                    }
                }
                holdsDroppedStream(droppedStream, status) -> {
                    LimeLog.info("Nova: The host still holds this stream (state=${status.state}), reconnecting")
                    listener.onReconnect(errorCode, attempt, MAX_ATTEMPTS)
                }
                else -> {
                    LimeLog.info(
                        "Nova: The host no longer holds this stream (state=${status.state}, " +
                            "identity ${if (droppedStream == null) "never seen" else "not matched"})"
                    )
                    listener.onHostSessionEnded()
                }
            }
        } catch (e: Exception) {
            // An exception here would vanish into the executor and leave the stream frozen, which
            // is the failure this class exists to prevent. Fall back to the terminated dialog.
            LimeLog.warning("Nova: Stream recovery after error $errorCode failed: ${e.message}")
            try {
                listener.onHostUnreachable(errorCode)
            } catch (fallback: Exception) {
                LimeLog.warning("Nova: Could not end the stream after error $errorCode: ${fallback.message}")
            }
        }
    }

    private class ExecutorScheduler : Scheduler {
        private val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Nova-Resilience").apply { isDaemon = true }
        }

        override fun schedule(delayMs: Long, task: Runnable): Boolean = try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: RejectedExecutionException) {
            false
        }

        override fun shutdown() {
            executor.shutdownNow()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        private val BACKOFF_DELAYS_MS = longArrayOf(0, 1000, 3000, 7000)

        // A stream the host ended on purpose must not be taken back: the host may have handed the
        // session to another device or quit the app. Protected content and a frame conversion
        // failure come back the same way on every relaunch, and the terminated dialog explains them.
        private val NOT_RECOVERABLE = setOf(
            MoonBridge.ML_ERROR_GRACEFUL_TERMINATION,
            MoonBridge.ML_ERROR_PROTECTED_CONTENT,
            MoonBridge.ML_ERROR_FRAME_CONVERSION,
        )

        internal fun isRecoverable(errorCode: Int): Boolean = errorCode !in NOT_RECOVERABLE

        // The host names a live stream by its app session and, while the stream runs, a generation
        // that changes with every new stream, a resume of the same app included.
        private fun namesLiveStream(status: PolarisSessionStatus): Boolean =
            status.isSessionAlive && !status.isShuttingDown &&
                status.appSessionId.isNotBlank() && status.sessionGeneration > 0L

        // The same app session, not on its way out, and either the same stream or its app paused
        // for this resume. A paused session no longer has a generation, so its app session is the
        // whole identity there. A stream nobody saw named can't be told from one the host ended.
        internal fun holdsDroppedStream(dropped: PolarisSessionStatus?, current: PolarisSessionStatus): Boolean {
            if (dropped == null || current.isShuttingDown || !current.isSessionAlive) return false
            if (current.appSessionId != dropped.appSessionId) return false
            return current.isPausedForResume || current.sessionGeneration == dropped.sessionGeneration
        }
    }
}
