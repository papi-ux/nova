package com.papi.nova.runtime

/**
 * When Nova sends its client presentation report to Polaris (#242, review W26 F5).
 *
 * The report's state key (session, refresh rates, display mode, decoder, presentation status, the
 * st1 frame-pacing state, bitrate) decides a transition, and a transition is sent at once, as
 * before. Counters stay out of that key so a moving count never churns it, which on its own meant
 * they were never sent: a steady stream reported its state once, usually before it had a frame
 * window, and its runtime counts stayed at that first snapshot until something unrelated changed.
 *
 * So an unchanged key is also due again [refreshMs] after the last send: every five seconds while
 * the stream is reported on (Game asks once per one-second status poll), with the frame window and
 * counts current. And once more when the stream ends, whatever the key.
 *
 * Pure and synchronized: the status poll and the end of the stream ask from different threads.
 */
internal class PresentationReportCadence(private val refreshMs: Long = REFRESH_MS) {
    enum class Why { TRANSITION, REFRESH, END }

    private var lastKey: String? = null
    private var lastSentMs = 0L

    init {
        require(refreshMs > 0) { "refresh interval must be positive" }
    }

    /**
     * Why a report with [key] is due at [nowMs] (a monotonic clock), or null when it is not. The
     * stream [ending] is always due.
     */
    @Synchronized
    fun due(key: String, nowMs: Long, ending: Boolean = false): Why? = when {
        ending -> Why.END
        key != lastKey -> Why.TRANSITION
        nowMs - lastSentMs >= refreshMs -> Why.REFRESH
        else -> null
    }

    /** A report with [key] reached Polaris at [nowMs]. */
    @Synchronized
    fun sent(key: String, nowMs: Long) {
        lastKey = key
        lastSentMs = nowMs
    }

    companion object {
        /** An unchanged report is refreshed every five seconds while the stream is reported on. */
        const val REFRESH_MS = 5_000L
    }
}
