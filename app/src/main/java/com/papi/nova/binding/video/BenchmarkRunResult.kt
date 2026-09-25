package com.papi.nova.binding.video

/**
 * Frozen result of one stopped Nordstern P0-4A run: the capture buffer plus everything the
 * exporter (piece 5) needs to fill measurement-spec-v1.md 7.2's JSON schema - the immutable
 * run_id, initial/terminal stream-generation pair (to derive generation_change_count and decide
 * whether the run aborts on a mismatch), the harness-provided run parameters echoed back
 * verbatim, and both elapsed-realtime endpoints.
 *
 * Top level rather than nested inside a renderer, because the benchmark is a property of a run
 * and not of the decoder that happened to serve it. It was nested while there was only one
 * renderer, and `buildBenchmarkRunJson` and Game's `@JvmStatic` entry point both named that
 * renderer to say it.
 */
class BenchmarkRunResult(
    val runId: String,
    val capture: BenchmarkStageCapture,
    val initialStreamGeneration: Int,
    val terminalStreamGeneration: Int,
    val expectedDurationNs: Long,
    val durationToleranceNs: Long,
    val drainGraceNs: Long,
    val manifestSha256: String?,
    val startedElapsedRealtimeNs: Long,
    val stoppedElapsedRealtimeNs: Long,
)
