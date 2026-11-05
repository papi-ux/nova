package com.papi.nova.binding.video.sourcetime

/**
 * Per-stream source-time counts for `client_runtime.source_time_pacing` (design E.2), kept beside
 * the mapper rather than inside it: the mapper is the shared contract's port and stays byte-for-byte
 * its reducer. Everything here is read from what the mapper returns and from the renderer's own
 * reset sites.
 *
 * - A reset the renderer makes (frame restart, codec recovery, foreground, display change, a
 *   target out of bounds) is named at its site with [onReset].
 * - A reset the mapper makes itself (a discontinuity, an anchor going backwards, an offset
 *   correction one update cannot fund) shows as a newer epoch in the next point, named by that
 *   point's reason. A point from an epoch already counted is never counted again.
 * - An offset slew event is a warm point whose applied offset (baseUs - sourceUs) differs from the
 *   previous warm point's in the same epoch.
 * - Admissions say how each decoded frame was aimed: by source time, or by arrival because it had
 *   no timing record or no usable source time.
 *
 * Thread-safe: points arrive on the submit thread, admissions on the render thread, resets anywhere.
 * Bounded: reset reasons come from a fixed vocabulary.
 */
class SourceTimeCounters {
    enum class Admission { SOURCE_TIMED, SOURCE_TIME_MISSING, TIMING_METADATA_MISSING }

    data class Snapshot(
        /** Resets by reason, only those that happened, in stable (sorted) order. */
        val resets: Map<String, Long>,
        val offsetSlewEvents: Long,
        val sourceTimed: Long,
        val sourceTimeMissing: Long,
        val timingMetadataMissing: Long,
    )

    private val resets = java.util.TreeMap<String, Long>()
    private var offsetSlewEvents = 0L
    private var sourceTimed = 0L
    private var sourceTimeMissing = 0L
    private var timingMetadataMissing = 0L
    private var lastEpoch = 0L
    private var lastOffset = 0L
    private var lastWarm = false
    private var haveOffset = false

    /** The renderer reset the mapper at one of its own sites; [epoch] is the mapper's new epoch. */
    @Synchronized
    fun onReset(reason: String, epoch: Long) {
        bump(if (reason in RENDERER_RESETS) reason else RESET_UNATTRIBUTED)
        if (epoch > lastEpoch) lastEpoch = epoch
        haveOffset = false
    }

    /** One point the mapper returned for a submitted frame. */
    @Synchronized
    fun onPoint(point: SourceTimeMapper.Point) {
        if (point.epoch < lastEpoch) return
        if (point.epoch > lastEpoch) {
            // The first epoch of a stream is its start, not a reset.
            if (lastEpoch != 0L) bump(if (point.reason in MAPPER_RESETS) point.reason else RESET_UNATTRIBUTED)
            lastEpoch = point.epoch
            haveOffset = false
        }
        if (!point.valid) return
        val offset = point.baseUs - point.sourceUs
        if (haveOffset && lastWarm && point.active && offset != lastOffset) offsetSlewEvents++
        lastOffset = offset
        lastWarm = point.active
        haveOffset = true
    }

    @Synchronized
    fun onAdmitted(admission: Admission) {
        when (admission) {
            Admission.SOURCE_TIMED -> sourceTimed++
            Admission.SOURCE_TIME_MISSING -> sourceTimeMissing++
            Admission.TIMING_METADATA_MISSING -> timingMetadataMissing++
        }
    }

    /** A new stream: every count starts again. */
    @Synchronized
    fun clear() {
        resets.clear()
        offsetSlewEvents = 0
        sourceTimed = 0
        sourceTimeMissing = 0
        timingMetadataMissing = 0
        lastEpoch = 0
        lastOffset = 0
        lastWarm = false
        haveOffset = false
    }

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(LinkedHashMap(resets), offsetSlewEvents, sourceTimed, sourceTimeMissing, timingMetadataMissing)

    private fun bump(reason: String) {
        resets[reason] = (resets[reason] ?: 0L) + 1L
    }

    companion object {
        const val RESET_STREAM_SETUP = "stream_setup"
        const val RESET_FRAME_RESTART = "frame_restart"
        const val RESET_CODEC_RECOVERY = "codec_recovery"
        const val RESET_FOREGROUND = "foreground"
        const val RESET_DISPLAY_CHANGE = "display_change"
        const val RESET_TARGET_OUT_OF_BOUNDS = "target_out_of_bounds"
        const val RESET_UNATTRIBUTED = "unattributed"

        val RENDERER_RESETS = setOf(
            RESET_STREAM_SETUP, RESET_FRAME_RESTART, RESET_CODEC_RECOVERY, RESET_FOREGROUND,
            RESET_DISPLAY_CHANGE, RESET_TARGET_OUT_OF_BOUNDS,
        )
        val MAPPER_RESETS = setOf(
            SourceTimeMapper.REASON_SOURCE_DISCONTINUITY,
            SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE,
        )
    }
}

/**
 * The renderer's last statistics window, as the HUD already measures it (the last two one-second
 * windows): rendered frames per second, and the frames the renderer chose not to show (its
 * intentional drops: pushed out of a full queue or superseded at a refresh) against every frame
 * the window expected, lost ones included.
 */
data class FrameWindow(
    val renderedFps: Double,
    val intentionalDrops: Int,
    val totalFrames: Int,
)

/**
 * What a renderer reports about presenting the stream, by value, for the shared reducer
 * (PresentationTruth): its identity, generations, reserve, presentation delay, source-time counts, the scheduler's
 * and the feedback sink's own counters, and its last frame window. Null fields are not known for
 * this renderer or this stream and are left out of every report, never written as zero.
 */
data class SourceTimeReport(
    /** mediacodec or pyrowave. */
    val renderer: String,
    val connectionGeneration: Int? = null,
    val decoderGeneration: Int? = null,
    val surfaceGeneration: Int? = null,
    val reserveUs: Long? = null,
    /** The learned presentation delay added to each mapped target; null before it is measured. */
    val targetDelayUs: Long? = null,
    /** Null when source time is not active for this stream. */
    val counters: SourceTimeCounters.Snapshot? = null,
    val scheduler: SourceTimeScheduler.Counters? = null,
    val feedbackMode: String = FEEDBACK_OFF,
    /** Observe-only frame-rendered feedback; read for this report and by no pacing decision. */
    val feedback: PresentFeedbackSink.Counters? = null,
    val window: FrameWindow? = null,
) {
    companion object {
        const val RENDERER_MEDIACODEC = "mediacodec"
        const val RENDERER_PYROWAVE = "pyrowave"

        /** No frame-rendered sink is registered. */
        const val FEEDBACK_OFF = "off"

        /** A sink observes, before Android 14: a rendered frame may come back with no callback. */
        const val FEEDBACK_OBSERVE_PARTIAL = "observe_partial"

        /** A sink observes, Android 14 and later. */
        const val FEEDBACK_OBSERVE = "observe"

        /** Android 14 (API 34) is the first that reports every rendered frame. */
        const val FEEDBACK_COMPLETE_SDK = 34

        @JvmStatic
        fun feedbackMode(observing: Boolean, sdkInt: Int): String = when {
            !observing -> FEEDBACK_OFF
            sdkInt < FEEDBACK_COMPLETE_SDK -> FEEDBACK_OBSERVE_PARTIAL
            else -> FEEDBACK_OBSERVE
        }
    }
}
