package com.papi.nova.binding.video

import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val SCHEMA_VERSION = 2
private const val MEASUREMENT_SPEC_ID = "nordstern-measurement-v1"
private const val CLOCK_DOMAIN = "android_CLOCK_MONOTONIC"
private const val STAGE = "reassembly_to_decoder_output"
private const val ABORT_REASON_STREAM_GENERATION_CHANGED = "stream_generation_changed"

/**
 * Serializes one stopped Nordstern P0-4A run to measurement-spec-v1.md
 * 7.2's exact JSON schema. Pure function, no Android framework dependency,
 * so it is covered by the ordinary debug-variant unit test suite even
 * though it is only ever called from the benchmark-only control path
 * (app/src/benchmark/BenchmarkControlReceiver, piece 4). Every field below
 * is hand-cross-checked against the spec's own literal example, in the
 * same order it appears there - a wrong or missing JSON key compiles clean
 * and nothing else catches it.
 *
 * Uses explicit put(key, JsonPrimitive(value)) / put(key, JsonNull)
 * throughout rather than kotlinx.serialization's nullable convenience
 * put() overloads, so every line has the same unambiguous shape and is
 * trivially diffable against the spec field-by-field.
 *
 * state/abort_reason: the spec ties generation mismatch to aborting -
 * "Any generation mismatch or nonzero change count transitions the
 * collector to aborted... emits its terminal file and reason but never
 * gate-authoritative percentiles." Nova's simpler run model (see
 * MediaCodecDecoderRenderer.BenchmarkRunState's doc comment) has no other
 * abort trigger to detect, so this is the only condition checked here.
 */
fun buildBenchmarkRunJson(result: MediaCodecDecoderRenderer.BenchmarkRunResult, collectorVersion: String): String {
    val aborted = result.terminalStreamGeneration != result.initialStreamGeneration
    val actualDurationNs = result.stoppedElapsedRealtimeNs - result.startedElapsedRealtimeNs
    val withinTolerance = abs(actualDurationNs - result.expectedDurationNs) <= result.durationToleranceNs

    val json = buildJsonObject {
        put("schema_version", JsonPrimitive(SCHEMA_VERSION))
        put("measurement_spec_id", JsonPrimitive(MEASUREMENT_SPEC_ID))
        put("collector_version", JsonPrimitive(collectorVersion))
        put("run_id", JsonPrimitive(result.runId))
        put("manifest_sha256", result.manifestSha256?.let { JsonPrimitive(it) } ?: JsonNull)
        put("state", JsonPrimitive(if (aborted) "aborted" else "frozen"))
        put("abort_reason", if (aborted) JsonPrimitive(ABORT_REASON_STREAM_GENERATION_CHANGED) else JsonNull)
        put("initial_stream_generation", JsonPrimitive(result.initialStreamGeneration))
        put("terminal_stream_generation", JsonPrimitive(result.terminalStreamGeneration))
        put(
            "generation_change_count",
            JsonPrimitive(result.terminalStreamGeneration - result.initialStreamGeneration),
        )
        put("clock_domain", JsonPrimitive(CLOCK_DOMAIN))
        put("stage", JsonPrimitive(STAGE))
        put("start_offset_us", JsonArray(result.capture.startOffsetUs().map { JsonPrimitive(it) }))
        put("end_offset_us", JsonArray(result.capture.endOffsetUs().map { JsonPrimitive(it) }))
        put("duration_us", JsonArray(result.capture.durationUs().map { JsonPrimitive(it) }))
        put("sample_count", JsonPrimitive(result.capture.acceptedCount))
        put("excluded_started_before_window", JsonPrimitive(result.capture.excludedStartedBeforeWindow))
        put("excluded_completed_after_window", JsonPrimitive(result.capture.excludedCompletedAfterWindow))
        // Always 0: Nova's classifier, like Polaris's own equivalent, is
        // only ever called once both boundary endpoints are already known,
        // so there is no "waiting for the terminal timestamp" state to
        // count separately.
        put("started_in_window_without_terminal_count", JsonPrimitive(0))
        put("missing_timestamp_count", JsonPrimitive(result.capture.missingTimestampCount))
        put("invalid_duration_count", JsonPrimitive(result.capture.invalidDurationCount))
        put("overflow_count", JsonPrimitive(result.capture.overflowCount))
        put("started_elapsed_realtime_ns", JsonPrimitive(result.startedElapsedRealtimeNs))
        put("stopped_elapsed_realtime_ns", JsonPrimitive(result.stoppedElapsedRealtimeNs))
        // Nova has no separate drain/freeze step after stop (see
        // BenchmarkRunResult's doc comment) - stop and freeze are the same
        // instant here, so this reuses stoppedElapsedRealtimeNs rather than
        // taking a further, functionally-redundant clock read.
        put("frozen_elapsed_realtime_ns", JsonPrimitive(result.stoppedElapsedRealtimeNs))
        put("expected_duration_ns", JsonPrimitive(result.expectedDurationNs))
        put("actual_duration_ns", JsonPrimitive(actualDurationNs))
        put("duration_tolerance_ns", JsonPrimitive(result.durationToleranceNs))
        put("duration_within_tolerance", JsonPrimitive(withinTolerance))
        put("drain_grace_ns", JsonPrimitive(result.drainGraceNs))
    }
    return json.toString()
}
