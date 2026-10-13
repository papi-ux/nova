package com.papi.nova.binding.video

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.diagnostics.NovaMomentReport
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecapReport
import com.papi.nova.diagnostics.NovaStreamRecapTest
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures
import com.papi.nova.ui.NovaHudDiagnosticReport
import com.papi.nova.ui.NovaHudMode
import com.papi.nova.ui.NovaHudSessionStats
import com.papi.nova.ui.NovaHudSessionSummaryLog
import com.papi.nova.ui.NovaHudTone
import com.papi.nova.ui.NovaHudUiState
import com.papi.nova.ui.NovaStreamRecapText
import com.papi.nova.ui.hudMetricDescription
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PyroWaveLatencyProvenanceTest {
    private fun sample(codec: String = "PyroWave", decodeMs: Double = Double.NaN) =
        NovaStreamRecapTest.sample(at = 2_000, expected = 100, lost = 2, codec = codec, decode = decodeMs)

    private fun wire(sample: PerfOverlaySample) = PolarisApiClient.buildLiveMediaTelemetryBody(
        sample, "session-1", 1, 60.0, 60.0, 20_000, "private_stream", false,
    ).getJSONObject("sample")

    @Test fun unknownTimingNeverBecomesAMeasuredWireZero() {
        // Include older, finite CPU API-return readings as well as today's unavailable value.
        for (ms in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 7.0)) {
            val sample = sample(decodeMs = ms)
            assertFalse(sample.decodeLatencyAvailable)
            assertEquals("present_request", sample.frameOutputSource)
            val json = wire(sample)
            assertEquals(0.0, json.getDouble("decode_latency_ms"), 0.0)
            assertFalse(json.getBoolean("decode_latency_available"))
            assertEquals("present_request", json.getString("frame_output_source"))
            assertEquals(sample.framesRendered, json.getLong("frames_rendered"))
            assertEquals(sample.framesReceived, json.getLong("frames_received"))
            assertEquals(sample.framesLost, json.getLong("frames_lost"))
            assertFalse(json.toString().contains("NaN"))
            assertFalse(json.toString().contains("Infinity"))
        }
    }

    @Test fun otherDecodersRetainMeasuredZeroAndPositiveTiming() {
        for (ms in listOf(0.0, 3.5)) {
            val sample = sample("c2.qti.hevc.decoder", ms)
            val json = wire(sample)
            assertTrue(json.getBoolean("decode_latency_available"))
            assertEquals(ms, json.getDouble("decode_latency_ms"), 0.0)
            assertEquals("renderer_output", json.getString("frame_output_source"))
        }
        assertFalse(wire(sample("c2.qti.hevc.decoder", Double.NaN)).getBoolean("decode_latency_available"))
    }

    @Test fun latencyStaysUnavailableBeforeSetupAndAfterRepeatedTeardown() {
        val renderer = PyroWaveDecoderRenderer(object : PerfOverlayListener {
            override fun onPerfUpdate(text: String) = Unit
        })
        fun unavailable() {
            assertEquals(0, renderer.getAverageEndToEndLatency())
            assertEquals(0, renderer.getAverageDecoderLatency())
            assertFalse(renderer.performanceWasTracked())
        }
        unavailable()
        renderer.start()
        assertEquals(com.papi.nova.nvstream.jni.MoonBridge.DR_NEED_IDR,
            renderer.submitDecodeUnit(byteArrayOf(1), 1, 0, 1, 2, 0.toChar(), 0, 0))
        renderer.prepareForStop()
        renderer.stop()
        renderer.cleanup()
        renderer.cleanup()
        unavailable()
        assertEquals(0L, renderer.framesSubmitted)
        assertEquals(renderer.framesSubmitted, renderer.framesShown)
    }

    @Test fun hudLabelsRequestsAndDoesNotGradeOldCpuReturnTimeAsGpuDecode() {
        fun hud(codec: String) = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG, fps = 50.0, targetFps = 60.0, latencyMs = 4,
            codec = codec, bitrateKbps = 20_000, width = 1920, height = 1080,
            status = null, sparklineSamples = emptyList(), decodeTimeMs = 30.0,
            incomingFps = 60.0, renderedFps = 50.0, mediaFresh = true,
        )
        val pyro = hud("PyroWave")
        assertEquals("--", pyro.decodeTimeLabel)
        assertEquals(NovaHudTone.MUTED, pyro.decodeTone)
        assertEquals("SUBMIT", pyro.outputRateCaption)
        assertEquals("SUBMIT GAP", pyro.outputGapCaption)
        assertEquals("Submit rate below receive", pyro.healthReasonLabel)
        assertEquals("Accepted present requests per second: 50 frames per second", hudMetricDescription("SUBMIT", "50"))
        assertEquals("Decode time: unavailable", hudMetricDescription("DECODE", pyro.decodeTimeLabel))
        assertEquals("OUT", hud("HEVC").outputRateCaption)
        assertEquals(NovaHudTone.DANGER, hud("HEVC").decodeTone)
    }

    @Test fun doctorSummaryPreservesCountsAndResetClearsTimingAvailability() {
        val stats = NovaHudSessionStats()
        stats.recordPerfSample(sample(decodeMs = 7.0))
        val summary = stats.summary()
        assertEquals(false, summary["decode_latency_available"])
        assertEquals(0.0, summary["decode_latency_ms"])
        assertEquals("present_request", summary["frame_output_source"])
        assertEquals(98L, summary["frames_rendered"])
        val log = JSONObject(NovaHudSessionSummaryLog.format(summary))
        assertFalse(log.getBoolean("decode_latency_available"))
        assertFalse(log.toString().contains("NaN"))
        assertTrue(NovaHudDiagnosticReport.format(summary).contains("98 submitted"))
        stats.reset()
        assertEquals(false, stats.summary()["decode_latency_available"])
        stats.recordPerfSample(sample("HEVC", 3.5))
        assertEquals(true, stats.summary()["decode_latency_available"])
        assertEquals(3.5, stats.summary()["decode_latency_ms"])
    }

    private fun recap(vararg samples: PerfOverlaySample): NovaStreamRecap.Recap =
        NovaStreamRecap.Accumulator(1).apply {
            launch(NovaStreamRecapTest.launch())
            connected(1_000)
            samples.forEach { accept(it, it.monotonicTimestampMs) }
        }.finish(NovaStreamRecap.End.CONNECTION_ENDED, null, 10_000, "2026-10-13T15:00:00Z")!!

    @Test fun recapAndSavedReportKeepPyrowaveTimingNullAndNameSubmissionCounts() {
        val recap = recap(sample(decodeMs = 7.0))
        assertNull(recap.decodeTimeMs)
        assertNull(recap.last!!.decodeTimeMs)
        assertEquals(60.0, recap.renderedFps!!.mean, 0.0)
        val json = NovaStreamRecapReport.toJson(recap, "1.5.0-beta.2", "debug")
        assertTrue(json.getJSONObject("readings").isNull("decode_time_ms"))
        val text = NovaStreamRecapText.page(ApplicationProvider.getApplicationContext(), recap)
        assertTrue(text.contains("Submitted fps"))
        assertTrue(text.contains("unmeasured"))
        val bytes = NovaStreamRecapReport.render(recap, "1.5.0-beta.2", "debug")!!
        assertTrue(bytes.toString(Charsets.UTF_8).contains("accepted present requests"))
    }

    @Test fun mixedRecapDoesNotAveragePyrowaveCpuTimeIntoOtherDecoderTiming() {
        val recap = recap(sample("HEVC", 3.5), sample(decodeMs = 7.0).copy(monotonicTimestampMs = 3_000))
        assertEquals(1, recap.decodeTimeMs!!.count)
        assertEquals(3.5, recap.decodeTimeMs!!.mean, 0.0)
        assertNull(recap.last!!.decodeTimeMs)
    }

    @Test fun momentAndOldSavedBaselineCannotResurrectCpuTiming() {
        val snapshot = NovaWorkingBaselineFixtures.snapshot(codec = "PyroWave", decodeTimeMs = 7.0)
        assertNull(snapshot.observed!!.decodeTimeMs)
        assertTrue(NovaMomentReport.toJson(snapshot).getJSONObject("client_decoder").isNull("decode_time_ms"))
        val scope = NovaWorkingBaseline.scope(NovaWorkingBaselineFixtures.key, NovaWorkingBaselineFixtures.entry)!!
        val baseline = NovaWorkingBaseline.fromSnapshot(scope, snapshot)!!
        val stored = JSONObject(NovaWorkingBaseline.encode(baseline)!!)
        assertTrue(stored.getJSONObject("client_decoder").isNull("decode_time_ms"))
        stored.getJSONObject("client_decoder").put("decode_time_ms", 7.0)
        val old = NovaWorkingBaseline.parse(stored.toString(), scope)!!
        assertNull(old.readings.decodeTimeMs)
        assertEquals(baseline.readings.renderedFps, old.readings.renderedFps)
    }
}
