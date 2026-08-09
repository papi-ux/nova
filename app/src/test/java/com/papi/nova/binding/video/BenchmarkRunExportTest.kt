package com.papi.nova.binding.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field is checked against measurement-spec-v1.md 7.2's exact JSON
 * schema, by key name, not just "the function doesn't crash" - the whole
 * point of buildBenchmarkRunJson's own doc comment is that a wrong or
 * missing key compiles clean and nothing else catches it.
 */
class BenchmarkRunExportTest {
    private fun result(
        runId: String = "run-1",
        capture: BenchmarkStageCapture = BenchmarkStageCapture(),
        initialStreamGeneration: Int = 1,
        terminalStreamGeneration: Int = 1,
        expectedDurationNs: Long = 120_000_000_000L,
        durationToleranceNs: Long = 250_000_000L,
        drainGraceNs: Long = 2_000_000_000L,
        manifestSha256: String? = "a".repeat(64),
        startedElapsedRealtimeNs: Long = 1_000_000_000_000L,
        stoppedElapsedRealtimeNs: Long = 1_120_000_000_000L,
    ) = MediaCodecDecoderRenderer.BenchmarkRunResult(
        runId = runId,
        capture = capture,
        initialStreamGeneration = initialStreamGeneration,
        terminalStreamGeneration = terminalStreamGeneration,
        expectedDurationNs = expectedDurationNs,
        durationToleranceNs = durationToleranceNs,
        drainGraceNs = drainGraceNs,
        manifestSha256 = manifestSha256,
        startedElapsedRealtimeNs = startedElapsedRealtimeNs,
        stoppedElapsedRealtimeNs = stoppedElapsedRealtimeNs,
    )

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun allScalarAndConstantFieldsMatchSpecExactly() {
        val capture = BenchmarkStageCapture(capacity = 1)
        capture.record(aOffsetUs = 10, bOffsetUs = 20, windowEndUs = 1_000) // accepted
        capture.record(aOffsetUs = 30, bOffsetUs = 40, windowEndUs = 1_000) // overflow (capacity 1)
        capture.record(aOffsetUs = -5, bOffsetUs = 5, windowEndUs = 1_000) // excluded before window
        capture.record(aOffsetUs = 10, bOffsetUs = 1_000, windowEndUs = 1_000) // excluded after window
        capture.record(aOffsetUs = 20, bOffsetUs = 10, windowEndUs = 1_000) // invalid non-monotonic
        capture.recordMissing()

        val obj = parse(
            buildBenchmarkRunJson(
                result(runId = "run-42", capture = capture),
                collectorVersion = "nova-1.3.5-benchmark",
            ),
        )

        assertEquals(2, obj["schema_version"]!!.jsonPrimitive.int)
        assertEquals("nordstern-measurement-v1", obj["measurement_spec_id"]!!.jsonPrimitive.content)
        assertEquals("nova-1.3.5-benchmark", obj["collector_version"]!!.jsonPrimitive.content)
        assertEquals("run-42", obj["run_id"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(64), obj["manifest_sha256"]!!.jsonPrimitive.content)
        assertEquals("frozen", obj["state"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, obj["abort_reason"])
        assertEquals(1, obj["initial_stream_generation"]!!.jsonPrimitive.int)
        assertEquals(1, obj["terminal_stream_generation"]!!.jsonPrimitive.int)
        assertEquals(0, obj["generation_change_count"]!!.jsonPrimitive.int)
        assertEquals("android_CLOCK_MONOTONIC", obj["clock_domain"]!!.jsonPrimitive.content)
        assertEquals("reassembly_to_decoder_output", obj["stage"]!!.jsonPrimitive.content)
        assertEquals(1, obj["sample_count"]!!.jsonPrimitive.int)
        assertEquals(1, obj["excluded_started_before_window"]!!.jsonPrimitive.int)
        assertEquals(1, obj["excluded_completed_after_window"]!!.jsonPrimitive.int)
        assertEquals(0, obj["started_in_window_without_terminal_count"]!!.jsonPrimitive.int)
        assertEquals(1, obj["missing_timestamp_count"]!!.jsonPrimitive.int)
        assertEquals(1, obj["invalid_duration_count"]!!.jsonPrimitive.int)
        assertEquals(1, obj["overflow_count"]!!.jsonPrimitive.int)
        assertEquals(1_000_000_000_000L, obj["started_elapsed_realtime_ns"]!!.jsonPrimitive.long)
        assertEquals(1_120_000_000_000L, obj["stopped_elapsed_realtime_ns"]!!.jsonPrimitive.long)
        assertEquals(1_120_000_000_000L, obj["frozen_elapsed_realtime_ns"]!!.jsonPrimitive.long)
        assertEquals(120_000_000_000L, obj["expected_duration_ns"]!!.jsonPrimitive.long)
        assertEquals(120_000_000_000L, obj["actual_duration_ns"]!!.jsonPrimitive.long)
        assertEquals(250_000_000L, obj["duration_tolerance_ns"]!!.jsonPrimitive.long)
        assertEquals(true, obj["duration_within_tolerance"]!!.jsonPrimitive.boolean)
        assertEquals(2_000_000_000L, obj["drain_grace_ns"]!!.jsonPrimitive.long)
    }

    @Test
    fun sampleArraysContainOnlyAcceptedObservationsInRecordOrder() {
        val capture = BenchmarkStageCapture()
        capture.record(aOffsetUs = 10, bOffsetUs = 25, windowEndUs = 1_000) // accepted, duration 15
        capture.record(aOffsetUs = -1, bOffsetUs = 5, windowEndUs = 1_000) // excluded, not in arrays
        capture.record(aOffsetUs = 100, bOffsetUs = 140, windowEndUs = 1_000) // accepted, duration 40

        val obj = parse(buildBenchmarkRunJson(result(capture = capture), collectorVersion = "v"))

        assertEquals(listOf(10, 100), obj["start_offset_us"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(25, 140), obj["end_offset_us"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(15, 40), obj["duration_us"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(2, obj["sample_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun emptyRunProducesEmptyArraysAndZeroCounters() {
        val obj = parse(buildBenchmarkRunJson(result(capture = BenchmarkStageCapture()), collectorVersion = "v"))

        assertTrue(obj["start_offset_us"]!!.jsonArray.isEmpty())
        assertTrue(obj["end_offset_us"]!!.jsonArray.isEmpty())
        assertTrue(obj["duration_us"]!!.jsonArray.isEmpty())
        assertEquals(0, obj["sample_count"]!!.jsonPrimitive.int)
        assertEquals(0, obj["missing_timestamp_count"]!!.jsonPrimitive.int)
        assertEquals(0, obj["invalid_duration_count"]!!.jsonPrimitive.int)
        assertEquals(0, obj["overflow_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun abortedWhenTerminalGenerationDiffersFromInitial() {
        val obj = parse(
            buildBenchmarkRunJson(
                result(initialStreamGeneration = 2, terminalStreamGeneration = 5),
                collectorVersion = "v",
            ),
        )

        assertEquals("aborted", obj["state"]!!.jsonPrimitive.content)
        assertEquals("stream_generation_changed", obj["abort_reason"]!!.jsonPrimitive.content)
        assertEquals(3, obj["generation_change_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun frozenWhenTerminalGenerationMatchesInitial() {
        val obj = parse(
            buildBenchmarkRunJson(
                result(initialStreamGeneration = 4, terminalStreamGeneration = 4),
                collectorVersion = "v",
            ),
        )

        assertEquals("frozen", obj["state"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, obj["abort_reason"])
        assertEquals(0, obj["generation_change_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun manifestSha256IsJsonNullWhenNotProvided() {
        val obj = parse(buildBenchmarkRunJson(result(manifestSha256 = null), collectorVersion = "v"))

        assertEquals(JsonNull, obj["manifest_sha256"])
        assertNull(obj["manifest_sha256"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun durationWithinToleranceTrueAtExactBoundary() {
        // actual - expected == tolerance exactly: spec's rule is <=, not <.
        val obj = parse(
            buildBenchmarkRunJson(
                result(
                    expectedDurationNs = 100_000_000_000L,
                    durationToleranceNs = 250_000_000L,
                    startedElapsedRealtimeNs = 0L,
                    stoppedElapsedRealtimeNs = 100_250_000_000L,
                ),
                collectorVersion = "v",
            ),
        )

        assertEquals(100_250_000_000L, obj["actual_duration_ns"]!!.jsonPrimitive.long)
        assertEquals(true, obj["duration_within_tolerance"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun durationWithinToleranceFalseJustOutsideBoundary() {
        val obj = parse(
            buildBenchmarkRunJson(
                result(
                    expectedDurationNs = 100_000_000_000L,
                    durationToleranceNs = 250_000_000L,
                    startedElapsedRealtimeNs = 0L,
                    stoppedElapsedRealtimeNs = 100_250_000_001L,
                ),
                collectorVersion = "v",
            ),
        )

        assertEquals(false, obj["duration_within_tolerance"]!!.jsonPrimitive.boolean)
    }
}
