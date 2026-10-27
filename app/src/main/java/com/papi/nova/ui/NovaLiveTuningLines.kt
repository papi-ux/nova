package com.papi.nova.ui

import com.papi.nova.api.LiveTuningStatus

/** One brief HUD line. It lasts from its emission, never from when a view first shows it. */
data class NovaLiveTuningLine(val serial: Long, val kind: Kind, val appliedKbps: Int, val emittedAtMs: Long) {
    enum class Kind { EASED, FULL }

    fun visibleAt(nowMs: Long): Boolean = nowMs - emittedAtMs in 0L until NovaLiveTuningLines.VISIBLE_MS
}

/**
 * Rare HUD lines from the Live Tuning status Nova already parses (#384). Only an acknowledged
 * applied rate settles a level: applying (requested != applied) and measuring readings discard
 * a waiting candidate and keep the announced level. The host's reason is never read, so a line
 * never claims a cause.
 */
class NovaLiveTuningLines {
    private data class Scope(val instance: String, val generation: Long, val session: String,
        val revision: String, val limitKbps: Int)
    /** Applied kbps below the quality limit, or [FULL]. */
    private var announced: Int? = null
    private var eased = false
    private var candidate: Int? = null
    private var candidateSinceMs = 0L
    private var scope: Scope? = null
    private var lastReadingAtMs: Long? = null
    private var lastLineAtMs: Long? = null
    private var serial = 0L
    var line: NovaLiveTuningLine? = null
        private set

    fun visibleLine(nowMs: Long): NovaLiveTuningLine? = line?.takeIf { it.visibleAt(nowMs) }

    fun reset() {
        announced = null; eased = false; candidate = null; scope = null
        lastReadingAtMs = null; lastLineAtMs = null; line = null
    }

    /** Returns a line only when this reading emits one. */
    fun observe(live: LiveTuningStatus?, codec: String, nowMs: Long): NovaLiveTuningLine? {
        if (lastReadingAtMs?.let { nowMs - it !in 0L..READING_GAP_MS } == true) reset()
        // Only these codecs' Live Tuning rate is the encoder's video bitrate the HUD shows as BIT.
        // Any other codec says nothing and drops the baseline, so no ease outlives the switch.
        if (live == null || !live.enabled || !live.supported || live.qualityLimitKbps <= 0 ||
            codec.lowercase() !in VIDEO_CODECS ||
            (live.state !in SETTLED_STATES && live.state !in HELD_STATES)) { reset(); return null }
        lastReadingAtMs = nowMs
        val next = Scope(live.hostInstance, live.sessionGeneration, live.appSessionId,
            live.configurationRevision, live.qualityLimitKbps)
        if (next != scope) { reset(); scope = next; lastReadingAtMs = nowMs }
        if (live.state !in SETTLED_STATES || live.appliedBitrateKbps <= 0) { candidate = null; return null }
        val level = if (live.appliedBitrateKbps >= live.qualityLimitKbps) FULL else live.appliedBitrateKbps
        val current = announced ?: run { announced = level; return null }
        val kind = when {
            current == FULL && level != FULL -> NovaLiveTuningLine.Kind.EASED
            current != FULL && level == FULL -> NovaLiveTuningLine.Kind.FULL
            current != FULL && level > current -> { announced = level; candidate = null; return null }
            current != FULL && level * 100L <= current * FURTHER_EASE_PERCENT -> NovaLiveTuningLine.Kind.EASED
            else -> { candidate = null; return null }
        }
        if (level != candidate) { candidate = level; candidateSinceMs = nowMs }
        if (nowMs - candidateSinceMs < (if (level == FULL) FULL_DWELL_MS else EASE_DWELL_MS)) return null
        if (lastLineAtMs?.let { nowMs - it < SPACING_MS } == true) return null
        candidate = null
        announced = level
        if (kind == NovaLiveTuningLine.Kind.FULL && !eased) return null
        eased = kind == NovaLiveTuningLine.Kind.EASED
        lastLineAtMs = nowMs
        return NovaLiveTuningLine(++serial, kind, live.appliedBitrateKbps, nowMs).also { line = it }
    }

    companion object {
        const val VISIBLE_MS = 4_000L
        const val FADE_MS = 180
        const val EASE_DWELL_MS = 2_000L
        const val FULL_DWELL_MS = 10_000L
        const val SPACING_MS = 30_000L
        /** Matches the HUD's host freshness; a longer silence starts over. */
        const val READING_GAP_MS = 5_000L
        private const val FURTHER_EASE_PERCENT = 90L
        private const val FULL = -1
        private val SETTLED_STATES = setOf("adjusting", "stable")
        private val HELD_STATES = setOf("applying", "measuring")
        private val VIDEO_CODECS = setOf("h264", "hevc", "av1")
    }
}
