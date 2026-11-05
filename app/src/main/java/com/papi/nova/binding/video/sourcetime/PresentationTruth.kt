package com.papi.nova.binding.video.sourcetime

import com.papi.nova.api.PolarisSessionVrr
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What this device can truthfully say about presenting the stream, once, for every surface that
 * says it: the host report, the client runtime snapshot, the HUD and the Command Center (the
 * shared contract's "same reducer and reasons everywhere").
 *
 * - [sourcePaced]: this device is placing frames by the host's source time, on a fixed or an
 *   adaptive refresh. It says nothing about the host or the display.
 * - [hostSourcePaced]: the host's own fresh record for this very stream says it is source-pacing.
 *   It never means this device presented on that cadence.
 * - [vrrRequested]: this device asked the host for variable refresh. Android has no request path in
 *   phase 2, so this is false.
 * - [vrrActive]: only with every piece of evidence at once: this device effectively presenting on
 *   adaptive refresh by source time, having asked for it, a display that supports adaptive refresh,
 *   the host fresh and source-pacing this generation, and presented-frame evidence for the same
 *   surface. Anything missing, stale or contradictory makes it false.
 * - [fallback]: the one reason to explain, when source time was asked for; [prominent] when the
 *   player asked explicitly (shown), not automatically (recorded only).
 * - [renderFps] and [droppedFrameRatio]: the renderer's last frame window, for client_presentation
 *   (design E.1), only when measured and only inside the ranges Polaris accepts.
 * - [targetDelayMs]: the presentation delay the renderer learned and adds to every mapped target so
 *   it reaches a refresh the display can still take (addendum 2), when measured.
 * - [report]: the renderer's generations and counts, for client_runtime (design E.2).
 */
data class PresentationTruth(
    /** The renderer's state, with the display's own reason in place of an unknown one. */
    val client: SourceTimePacingStatus,
    val displayModeHz: Int?,
    val displayArr: ArrSupport,
    val sourcePaced: Boolean,
    val hostRecord: HostRecord,
    val hostSourcePaced: Boolean,
    val vrrRequested: Boolean,
    val vrrActive: Boolean,
    val fallback: SourceTimeReason?,
    val prominent: Boolean,
    val report: SourceTimeReport? = null,
    /** Rendered frames per second of the last window, 0-1000; null when not measured. */
    val renderFps: Double? = null,
    /** Intentional drops over expected frames of the last window, 0-1; null without frames. */
    val droppedFrameRatio: Double? = null,
    /** The learned presentation delay in milliseconds; null when not measured. */
    val targetDelayMs: Double? = null,
) {
    /** What the host said about its record for this stream, as far as this device can trust it. */
    enum class HostRecord(val wire: String) {
        ABSENT("absent"),
        MALFORMED("malformed"),
        STALE("stale"),
        PRESENT("present"),
    }

    /** client_runtime.source_time_pacing: compact, no counters, tokens or identities. */
    fun toRuntimeJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("frame_pacing_state", client.toFramePacingState())
        put("requested", client.requested.wire)
        put("effective", client.effective.wire)
        put("fallback", client.fallback.wire)
        put("source_paced", sourcePaced)
        put("host_record", hostRecord.wire)
        if (hostRecord == HostRecord.PRESENT) put("host_source_paced", hostSourcePaced)
        put("vrr_requested", vrrRequested)
        put("vrr_active", vrrActive)
        put("display_arr", displayArr.wire)
        displayModeHz?.let { put("display_mode_hz", it) }
        targetDelayMs?.let { put("target_delay_ms", round(it, 10.0)) }
        report?.let { putReport(it) }
    }

    // Design E.2: the renderer's identity, generations, reserve and counts. Bounded: fixed keys and
    // a fixed reset vocabulary, no traces, tokens or identities.
    private fun JSONObject.putReport(report: SourceTimeReport) {
        put("renderer", report.renderer)
        report.connectionGeneration?.let { put("connection_gen", it) }
        report.decoderGeneration?.let { put("decoder_gen", it) }
        report.surfaceGeneration?.let { put("surface_gen", it) }
        report.reserveUs?.let { put("reserve_us", it) }
        report.counters?.let { counters ->
            put("offset_slew_events", counters.offsetSlewEvents)
            put("resets", JSONObject().apply { counters.resets.forEach { (reason, n) -> put(reason, n) } })
        }
        if (report.counters != null || report.scheduler != null) {
            put("counts", JSONObject().apply {
                report.scheduler?.let { scheduler ->
                    put("on_slot", scheduler.released - scheduler.releasedLate)
                    put("late_presented", scheduler.releasedLate)
                    put("late_superseded", scheduler.superseded)
                    put("queue_overflow", scheduler.overflow)
                }
                report.scheduler?.let { scheduler ->
                    put("stale_epoch_dropped", scheduler.staleDropped)
                }
                report.counters?.let { counters ->
                    put("source_time_missing", counters.sourceTimeMissing)
                    put("timing_metadata_missing", counters.timingMetadataMissing)
                    put("stale_epoch", counters.staleEpoch)
                }
            })
        }
        put("feedback_mode", report.feedbackMode)
        report.feedback?.coverage?.takeIf { it.isFinite() }?.let { put("feedback_coverage", round(it, 1000.0)) }
    }

    companion object {
        const val SCHEMA = 1

        @JvmStatic
        @JvmOverloads
        fun from(
            renderer: SourceTimePacingStatus?,
            display: DisplayPresentationCapability?,
            hostVrr: PolarisSessionVrr.Reading,
            /** The status holding [hostVrr] is current and for an active stream. */
            hostCurrent: Boolean,
            /** That status's session generation; the record must name the same one. */
            sessionGeneration: Long,
            displayModeHz: Float?,
            presentedFrameEvidence: Boolean = false,
            report: SourceTimeReport? = null,
        ): PresentationTruth {
            val raw = renderer ?: SourceTimePacingStatus.legacy(SourceTimeRequest.OFF, SourceTimeReason.NOT_REQUESTED)
            val client = if (raw.effective == SourceTimeEffective.SOURCE_TIME_FIXED &&
                raw.fallback == SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN && display != null
            ) {
                raw.copy(fallback = display.reason)
            } else {
                raw
            }

            val (hostRecord, record) = when (hostVrr) {
                PolarisSessionVrr.Reading.Absent -> HostRecord.ABSENT to null
                is PolarisSessionVrr.Reading.Malformed -> HostRecord.MALFORMED to null
                is PolarisSessionVrr.Reading.Present ->
                    if (hostCurrent && sessionGeneration > 0 && hostVrr.record.sessionGeneration == sessionGeneration) {
                        HostRecord.PRESENT to hostVrr.record
                    } else {
                        HostRecord.STALE to null
                    }
            }
            val hostSourcePaced = record?.hostSourcePaced == true
            val sourcePaced = client.effective == SourceTimeEffective.SOURCE_TIME_FIXED ||
                client.effective == SourceTimeEffective.SOURCE_TIME_VRR
            val arr = display?.arr ?: ArrSupport.UNKNOWN
            val vrrActive = client.effective == SourceTimeEffective.SOURCE_TIME_VRR &&
                client.vrrRequested && client.vrrEffective &&
                arr == ArrSupport.SUPPORTED &&
                hostSourcePaced &&
                presentedFrameEvidence
            val fallback = client.fallback.takeIf {
                client.requested != SourceTimeRequest.OFF && it != SourceTimeReason.NONE
            }
            return PresentationTruth(
                client = client,
                displayModeHz = displayModeHz?.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.takeIf { it > 0 },
                displayArr = arr,
                sourcePaced = sourcePaced,
                hostRecord = hostRecord,
                hostSourcePaced = hostSourcePaced,
                vrrRequested = client.vrrRequested,
                vrrActive = vrrActive,
                fallback = fallback,
                prominent = client.requested == SourceTimeRequest.SOURCE_TIME,
                report = report,
                renderFps = report?.window?.renderedFps
                    ?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_RENDER_FPS }
                    ?.let { round(it, 100.0) },
                droppedFrameRatio = report?.window?.takeIf { it.totalFrames > 0 && it.intentionalDrops >= 0 }
                    ?.let { round((it.intentionalDrops.toDouble() / it.totalFrames).coerceIn(0.0, 1.0), 10_000.0) },
                targetDelayMs = report?.targetDelayUs?.takeIf { it >= 0 }?.let { it / 1000.0 },
            )
        }

        /** Polaris refuses the whole client_presentation report beyond this (nvhttp.cpp). */
        const val MAX_RENDER_FPS = 1000.0

        private fun round(value: Double, scale: Double): Double = Math.round(value * scale) / scale

        /** The reason in plain words, for the HUD and the Command Center. */
        @JvmStatic
        fun plain(reason: SourceTimeReason): String = when (reason) {
            SourceTimeReason.NONE -> "None"
            SourceTimeReason.NOT_REQUESTED -> "Off"
            SourceTimeReason.HOST_STATUS_MISSING -> "Host status unavailable"
            SourceTimeReason.HOST_STATUS_STALE -> "Host status out of date"
            SourceTimeReason.SOURCE_TIMESTAMP_MISSING -> "Host sends no frame times"
            SourceTimeReason.SOURCE_DISCONTINUITY -> "Host timing restarted"
            SourceTimeReason.TIMING_METADATA_MISSING -> "Frame timing lost"
            SourceTimeReason.CLOCK_MAPPING_UNSTABLE -> "Re-syncing to host time"
            SourceTimeReason.DECODER_PRESSURE -> "Decoder falling behind"
            SourceTimeReason.BUFFER_LIMIT -> "Buffer at its limit"
            SourceTimeReason.DISPLAY_FIXED_REFRESH -> "Fixed-refresh display"
            SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN -> "Display support unknown"
            SourceTimeReason.COMPOSITOR_UNVERIFIED -> "Adaptive refresh not verified"
            SourceTimeReason.PRESENT_MODE_UNSUPPORTED -> "Presentation mode unsupported"
            SourceTimeReason.PRESENT_FEEDBACK_UNAVAILABLE -> "No display feedback"
            SourceTimeReason.PRESENT_FEEDBACK_STALE -> "Display feedback out of date"
            SourceTimeReason.SURFACE_RECREATED -> "Display surface changed"
            SourceTimeReason.RENDERER_NOT_PARTICIPATING -> "This decoder cannot pace"
            SourceTimeReason.PACING_MODE_INCOMPATIBLE -> "Needs Balanced frame pacing"
            SourceTimeReason.STALE_EPOCH -> "Re-syncing after a reset"
        }
    }
}
