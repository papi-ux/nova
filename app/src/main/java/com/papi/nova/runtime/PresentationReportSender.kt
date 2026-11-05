package com.papi.nova.runtime

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends Nova's client presentation reports to Polaris one at a time, and the stream's last one
 * exactly once even when another report is in flight as the stream ends (#242, review W34 D3).
 *
 * An ordinary report, a transition or a refresh by [cadence], is skipped while another is in
 * flight, as before: the next one-second status poll asks again. The end of the stream has no next
 * poll. Before this, an end that found a refresh in flight returned and was never tried again, so
 * the stream's final frame window and counts never reached Polaris.
 *
 * So the end is two steps:
 * - [endRequested], at once: no ordinary report is sent from then on, so nothing older can follow
 *   the end's report.
 * - [endAsync], with a payload the caller has already captured: sent once, on a thread of its own
 *   that outlives the Activity's lifecycle scope, after the report in flight returns, or after
 *   [endWaitMs] if that one never does. Delivery stays best effort: a failed end is not retried.
 */
internal class PresentationReportSender(
    private val cadence: PresentationReportCadence,
    private val endWaitMs: Long = END_WAIT_MS,
) {
    // One report on the wire at a time. Fair, so an end waiting for it goes before anything else.
    private val gate = Semaphore(1, true)
    private val ending = AtomicBoolean(false)
    private val endSent = AtomicBoolean(false)

    init {
        require(endWaitMs > 0) { "the end's wait must be bounded and positive" }
    }

    /**
     * An ordinary report with [key] at [nowMs]: [send] runs only when the cadence says it is due, no
     * other report is in flight and the stream is not ending. [send] gets the reason and returns
     * whether Polaris took the report. Returns the reason when it was attempted, null when not.
     */
    fun report(key: String, nowMs: Long, send: (PresentationReportCadence.Why) -> Boolean): PresentationReportCadence.Why? {
        if (ending.get()) return null
        val why = cadence.due(key, nowMs) ?: return null
        if (!gate.tryAcquire()) return null
        try {
            // The end may have been requested since: its report is the newer one.
            if (ending.get()) return null
            if (send(why)) cadence.sent(key, nowMs)
            return why
        } finally {
            gate.release()
        }
    }

    /** The stream is ending: no ordinary report is sent from now on. Returns false if it already was. */
    fun endRequested(): Boolean = ending.compareAndSet(false, true)

    /**
     * Sends the end's report, which the caller captured before the decoder stopped, exactly once:
     * after the report in flight returns, or after [endWaitMs] if it does not. Blocks the calling
     * thread for at most that wait plus [send]. Returns whether Polaris took it, false when an end
     * was already sent.
     */
    fun end(key: String, nowMs: Long, send: (PresentationReportCadence.Why) -> Boolean): Boolean {
        endRequested()
        if (!endSent.compareAndSet(false, true)) return false
        val held = try {
            gate.tryAcquire(endWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        try {
            val sent = send(cadence.due(key, nowMs, ending = true) ?: PresentationReportCadence.Why.END)
            if (sent) cadence.sent(key, nowMs)
            return sent
        } finally {
            if (held) gate.release()
        }
    }

    /**
     * [end] on a daemon thread of its own, started here, so neither the caller nor the Activity's
     * teardown waits for the network. Returns the thread.
     */
    fun endAsync(key: String, nowMs: Long, send: (PresentationReportCadence.Why) -> Boolean): Thread =
        Thread({ end(key, nowMs, send) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }

    companion object {
        /**
         * The longest the end's report waits for one in flight. Longer than an ordinary report on a
         * working LAN; a report still on the wire after it (the API client's own timeouts are 5 s to
         * connect and 10 s to read) no longer holds the end back.
         */
        const val END_WAIT_MS = 5_000L

        const val THREAD_NAME = "NovaClientPresentationEnd"
    }
}
