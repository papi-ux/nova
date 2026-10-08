package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved report's closed, bounded, anonymized schema, and every model it refuses to write. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaStreamRecapReportTest {
    companion object {
        const val SECRET_HOST = "host-entry-secret-77"

        fun recap(generation: Long = 987_654L, samples: Boolean = true, end: End = End.END_REQUESTED, code: Int? = null): NovaStreamRecap.Recap {
            val acc = NovaStreamRecap.Accumulator(generation)
            acc.launch(launch(host = SECRET_HOST, reconnect = true))
            acc.connected(1_000L)
            if (samples) {
                acc.accept(sample(2_000L, expected = 100, lost = 0), 2_000L)
                acc.accept(sample(3_000L, expected = 160, lost = 3), 3_000L)
            }
            return acc.finish(end, code, 4_000L, "2026-10-08T14:00:00Z")!!
        }

        fun json(bytes: ByteArray): JSONObject {
            val text = String(bytes, StandardCharsets.UTF_8)
            return JSONObject(text.substringAfter("```json\n").substringBefore("\n```"))
        }
    }

    private fun render(recap: NovaStreamRecap.Recap) = NovaStreamRecapReport.render(recap, "1.4.15", "release")
    private fun keys(json: JSONObject) = json.keys().asSequence().toSet()

    @Test fun everyLevelHoldsExactlyTheClosedKeys() {
        val json = json(render(recap())!!)
        assertEquals(NovaStreamRecapReport.TOP_KEYS, keys(json))
        assertEquals(setOf("app", "version", "build_type"), keys(json.getJSONObject("client")))
        assertEquals(setOf("reason", "error_code"), keys(json.getJSONObject("end")))
        assertEquals(setOf("role", "reconnect_requested", "requested_settings"), keys(json.getJSONObject("launch")))
        assertEquals(setOf("width", "height", "fps", "bitrate_kbps", "codec_preference", "scope"),
            keys(json.getJSONObject("launch").getJSONObject("requested_settings")))
        assertEquals(setOf("scope", "connected_for_ms", "accepted_samples", "rejected_samples", "samples_covered_ms"),
            keys(json.getJSONObject("connection")))
        val readings = json.getJSONObject("readings")
        assertEquals(setOf("aggregation", "rendered_fps", "received_fps", "decode_time_ms", "host_processing_latency_ms",
            "control_rtt_ms", "control_rtt_scope"), keys(readings))
        assertEquals(setOf("count", "mean", "min", "max"), keys(readings.getJSONObject("rendered_fps")))
        val frames = json.getJSONObject("video_frames")
        assertEquals(setOf("between_samples", "between_samples_scope", "whole_connection", "whole_connection_available",
            "whole_connection_scope"), keys(frames))
        assertEquals(setOf("intervals", "frames_expected", "frames_lost", "pct"), keys(frames.getJSONObject("between_samples")))
        assertEquals(setOf("frames_expected", "frames_lost", "pct"), keys(frames.getJSONObject("whole_connection")))
        assertEquals(setOf("codecs", "dimensions_changed"), keys(json.getJSONObject("mixed")))
        assertEquals(setOf("age_at_end_ms", "codec", "width", "height", "rendered_fps", "received_fps", "decode_time_ms",
            "control_rtt_ms"), keys(json.getJSONObject("last_sample")))
    }

    @Test fun noIdentityGenerationPathOrDecoderTextIsWritten() {
        val text = String(render(recap())!!, StandardCharsets.UTF_8)
        assertFalse(text.contains(SECRET_HOST))
        assertFalse(text.contains("987654"))
        assertFalse("no process generation key", Regex("\"[a-z_]*generation[a-z_]*\":").containsMatchIn(text))
        assertFalse("raw decoder names stay out; only the closed family", text.contains("c2.qti"))
        assertFalse(text.contains("/data/") || text.contains("cache"))
        assertTrue(text.contains("\"reconnect_requested\": true"))
        val json = json(render(recap())!!)
        assertEquals("hevc", json.getJSONObject("last_sample").getString("codec"))
        assertEquals("end_requested", json.getJSONObject("end").getString("reason"))
    }

    @Test fun aRecapWithNoSamplesWritesNullsNotZeros() {
        val json = json(render(recap(samples = false))!!)
        val readings = json.getJSONObject("readings")
        listOf("rendered_fps", "received_fps", "decode_time_ms", "host_processing_latency_ms", "control_rtt_ms")
            .forEach { assertTrue(it, readings.isNull(it)) }
        assertTrue(json.isNull("last_sample"))
        assertTrue(json.getJSONObject("video_frames").isNull("between_samples"))
        assertFalse(json.getJSONObject("video_frames").getBoolean("whole_connection_available"))
        assertEquals(0, json.getJSONObject("connection").getInt("accepted_samples"))
        assertEquals(3_000L, json.getJSONObject("connection").getLong("connected_for_ms"))
    }

    @Test fun invalidModelsAreRefusedBeforeAnythingIsWritten() {
        val good = recap()
        val stat = good.renderedFps!!
        val invalid = listOf(
            good.copy(renderedFps = stat.copy(mean = Double.NaN)),
            good.copy(renderedFps = stat.copy(mean = stat.max + 5.0)),
            good.copy(renderedFps = stat.copy(min = 70.0, max = 50.0)),
            good.copy(renderedFps = stat.copy(count = 0)),
            good.copy(renderedFps = stat.copy(count = good.acceptedSamples + 1)),
            good.copy(controlRttMs = good.controlRttMs!!.copy(min = 0.0)),
            good.copy(connectedForMs = -1L),
            good.copy(samplesCoveredMs = 40L * 24 * 60 * 60 * 1000),
            good.copy(acceptedSamples = -1),
            good.copy(lossBetweenSamples = NovaStreamRecap.Loss(1, 10, 11)),
            good.copy(lossBetweenSamples = NovaStreamRecap.Loss(0, 10, 1)),
            good.copy(connectionLoss = NovaStreamRecap.Loss(2, 160, 3)),
            good.copy(codecs = listOf("hevc", "vp9")),
            good.copy(codecs = listOf("hevc", "hevc")),
            good.copy(errorCode = 22),
            good.copy(endedAtUtc = "2026-13-40T25:00:00Z"),
            good.copy(launch = good.launch.copy(requested = good.launch.requested.copy(width = 0))),
            good.copy(launch = good.launch.copy(requested = good.launch.requested.copy(codecPreference = "vp9"))),
            good.copy(last = good.last!!.copy(codec = "c2.qti.hevc.decoder")),
            good.copy(last = good.last!!.copy(renderedFps = Double.POSITIVE_INFINITY)),
            recap(samples = false).copy(last = good.last),
        )
        invalid.forEachIndexed { index, model -> assertNull("case $index", render(model)) }
        assertNotNull(render(good))
    }

    @Test fun partsThatDisagreeWithTheSampleCountAreRefused() {
        val good = recap()
        val none = recap(samples = false)
        val inconsistent = listOf(
            none.copy(renderedFps = good.renderedFps),
            none.copy(lossBetweenSamples = good.lossBetweenSamples),
            none.copy(connectionLoss = good.connectionLoss),
            none.copy(codecs = listOf("hevc")),
            none.copy(dimensionsChanged = true),
            good.copy(acceptedSamples = 1),                        // one sample cannot span time
            good.copy(samplesCoveredMs = null),                    // two samples must
            good.copy(lossBetweenSamples = NovaStreamRecap.Loss(2, 60, 3)), // more intervals than samples allow
            good.copy(last = null),
            good.copy(codecs = emptyList()),
        )
        inconsistent.forEachIndexed { index, model -> assertNull("case $index", render(model)) }
        assertNotNull(render(none))
        assertNotNull(render(good))
        assertNotNull("an error end may carry its code", render(recap(end = End.ERROR, code = -7)))
    }

    @Test fun theWholeFileIsBoundedIncludingItsEnvelope() {
        val bytes = render(recap())!!
        assertTrue(bytes.size <= NovaStreamRecapReport.MAX_BYTES)
        assertNull(NovaStreamRecapReport.render(recap(), "1.4.15", "release", maxBytes = bytes.size - 1))
        assertNotNull(NovaStreamRecapReport.render(recap(), "1.4.15", "release", maxBytes = bytes.size))
        // A caller can lower the limit but never raise it past the hard cap.
        assertNotNull(NovaStreamRecapReport.render(recap(), "1.4.15", "release", maxBytes = Int.MAX_VALUE))
        assertTrue(NovaStreamRecapReport.render(recap(), "1.4.15", "release", maxBytes = Int.MAX_VALUE)!!.size <= NovaStreamRecapReport.MAX_BYTES)
    }

    @Test fun clientSoftwareIsOnlyAClosedToken() {
        val json = json(NovaStreamRecapReport.render(recap(), "1.4 beta <script>", "release build")!!)
        assertEquals("unknown", json.getJSONObject("client").getString("version"))
        assertEquals("unknown", json.getJSONObject("client").getString("build_type"))
        assertEquals("Nova Android", json.getJSONObject("client").getString("app"))
    }

    @Test fun theReportStatesItsScopesAndGivesNoVerdict() {
        val text = String(render(recap())!!, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("# Nova stream recap report"))
        assertTrue(text.contains("Nothing was sent automatically"))
        assertTrue(text.contains("not measured throughput"))
        NovaStreamRecapReport.NOT_INCLUDED.forEach { assertTrue(it, text.contains(it)) }
        listOf("healthy", "regression", "excellent", "poor", "score").forEach {
            assertFalse(it, Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        val frames = json(render(recap())!!).getJSONObject("video_frames")
        assertEquals(1, frames.getJSONObject("between_samples").getInt("intervals"))
        assertEquals(60L, frames.getJSONObject("between_samples").getLong("frames_expected"))
        assertTrue(frames.getBoolean("whole_connection_available"))
    }
}
