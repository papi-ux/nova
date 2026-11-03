package com.papi.nova.api

import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.binding.video.FrameSubmissionReport
import org.json.JSONObject

/** Serialization only. The optional native population never supplies legacy counters. */
internal object LiveMediaTelemetryBody {
    fun frameSubmissionSupported(features: JSONObject?): Boolean =
        features?.opt("live_media_frame_submission_v1") == true

    fun build(sample: PerfOverlaySample, appSessionId: String, sessionGeneration: Long,
              targetFps: Double, refreshRateHz: Double, bitrateKbps: Int, topology: String, hdr: Boolean,
              frameSubmission: FrameSubmissionReport? = null,
              frameSubmissionSupported: Boolean = false): JSONObject = JSONObject().apply {
        put("app_session_id", appSessionId)
        put("session_generation", sessionGeneration)
        put("sample", JSONObject().apply {
            put("monotonic_timestamp_ms", sample.monotonicTimestampMs)
            put("decoder_generation", sample.sessionGeneration)
            put("frames_expected", sample.framesExpected)
            put("frames_received", sample.framesReceived)
            put("frames_rendered", sample.framesRendered)
            put("frames_lost", sample.framesLost)
            put("received_fps", sample.incomingFps)
            put("rendered_fps", sample.renderedFps)
            put("target_fps", targetFps)
            put("refresh_rate_hz", refreshRateHz)
            put("decode_latency_ms", sample.wireDecodeLatencyMs)
            put("decode_latency_available", sample.decodeLatencyAvailable)
            put("frame_output_source", sample.frameOutputSource)
            sample.hostProcessingLatencyMs?.let { put("host_processing_latency_ms", it) }
            put("width", sample.width)
            put("height", sample.height)
            put("codec", sample.codec)
            put("bitrate_kbps", bitrateKbps)
            put("topology", topology)
            put("hdr", hdr)
            if (frameSubmissionSupported) frameSubmission?.let { put("frame_submission", it.toJson()) }
        })
    }
}
