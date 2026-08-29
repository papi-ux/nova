package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaHudSessionStatsTest {
    @Test
    fun rawMediaEvidencePopulatesDiagnosticsWithoutDoubleCountingHudAverages() {
        val stats = NovaHudSessionStats()
        stats.recordFps(120.0, nowMs = 1_000)
        stats.recordLatency(4)
        stats.recordPacketLoss(0.5)

        stats.recordRawMediaEvidence(
            PerfOverlaySample(
                fps = 120.0,
                incomingFps = 119.8,
                renderedFps = 119.7,
                width = 1920,
                height = 1080,
                codec = "HEVC",
                rttMs = 4,
                rttVarianceMs = 1,
                decodeTimeMs = 3.2,
                packetLossPct = 0.5,
                monotonicTimestampMs = 50_000L,
                framesExpected = 6_000L,
                framesReceived = 5_970L,
                framesRendered = 5_960L,
                framesLost = 30L,
                sessionGeneration = 3L
            )
        )

        val summary = stats.summary(nowMs = 2_000)
        assertEquals(120.0, summary["avg_fps"] as Double, 0.01)
        assertEquals(4.0, summary["avg_latency_ms"] as Double, 0.01)
        assertEquals(0.5, summary["packet_loss_pct"] as Double, 0.01)
        assertEquals(6_000L, summary["frames_expected"])
        assertEquals(5_970L, summary["frames_received"])
        assertEquals(5_960L, summary["frames_rendered"])
        assertEquals(30L, summary["frames_lost"])
        assertEquals(3L, summary["session_generation"])
    }

    @Test
    fun summaryReportsAveragesAndPacketLoss() {
        val stats = NovaHudSessionStats()

        stats.setTargetFps(60.0)
        stats.setLastCodec("HEVC")
        stats.setLastBitrateKbps(30000)
        stats.recordFps(60.0, nowMs = 1_000)
        stats.recordLatency(18)
        stats.recordBitrate(28000)
        stats.recordPacketLoss(1.25)
        stats.recordFps(58.0, nowMs = 2_000)
        stats.recordLatency(22)
        stats.recordBitrate(26000)

        val summary = stats.summary(nowMs = 11_000)

        assertEquals(59.0, summary["avg_fps"] as Double, 0.01)
        assertEquals(20.0, summary["avg_latency_ms"] as Double, 0.01)
        assertEquals(1.25, summary["packet_loss_pct"] as Double, 0.01)
        assertEquals(27000, summary["avg_bitrate_kbps"])
        assertEquals("HEVC", summary["codec"])
        assertEquals(10, summary["duration_s"])
    }

    @Test
    fun summaryAveragesLatencyByLatencySamples() {
        val stats = NovaHudSessionStats()

        stats.recordFps(60.0, nowMs = 1_000)
        stats.recordLatency(18)
        stats.recordLatency(30)
        stats.recordLatency(42)

        val summary = stats.summary(nowMs = 2_000)

        assertEquals(30.0, summary["avg_latency_ms"] as Double, 0.01)
        assertEquals(1, summary["samples"])
    }

    @Test
    fun summaryReportsRawHighRefreshEvidenceWithoutDerivingPacing() {
        val stats = NovaHudSessionStats()

        stats.setTargetFps(120.0)
        repeat(12) { index ->
            stats.recordFps(54.0, nowMs = 1_000L + index)
            stats.recordLatency(24)
        }

        val summary = stats.summary(nowMs = 20_000)

        assertFalse(summary.containsKey("safe_target_fps"))
        assertFalse(summary.containsKey("relaunch_recommended"))
        assertEquals(true, summary["observational"])
        assertFalse(summary.containsKey("frame_pacing_bad_pct"))
        assertEquals(54.0, summary["avg_fps"] as Double, 0.01)
        assertEquals(120.0, summary["target_fps"] as Double, 0.01)
    }

    @Test
    fun summaryDoesNotImportPolarisHealthRecoveryConclusions() {
        val stats = NovaHudSessionStats()

        stats.setTargetFps(120.0)
        stats.applySessionStatus(
            PolarisSessionStatus(
                state = "streaming",
                streamingActive = true,
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "host_render_limited",
                    issues = listOf("host_render_limited"),
                    safeTargetFps = 60.0,
                    safeBitrateKbps = 14000,
                    safeCodec = "h264",
                    safeDisplayMode = "desktop",
                    relaunchRecommended = true
                ),
                displayMode = PolarisSessionStatus.DisplayModeStatus(effectiveHeadless = true)
            )
        )
        repeat(3) { stats.recordFps(80.0, nowMs = 1_000L + it) }

        val summary = stats.summary(nowMs = 5_000)

        assertEquals("doctor_v2_raw", summary["contract"])
        assertFalse(summary.containsKey("primary_issue"))
        assertFalse(summary.containsKey("safe_target_fps"))
        assertFalse(summary.containsKey("safe_bitrate_kbps"))
        assertFalse(summary.containsKey("safe_codec"))
        assertFalse(summary.containsKey("safe_display_mode"))
        assertFalse(summary.containsKey("relaunch_recommended"))
    }

    @Test
    fun sessionSummaryLogIncludesEvidenceFieldsAndExcludesIdentifiers() {
        val summary = mapOf(
            "contract" to "doctor_v2_raw",
            "observational" to true,
            "avg_fps" to 59.5,
            "target_fps" to 60.0,
            "avg_latency_ms" to 24.0,
            "avg_bitrate_kbps" to 18000,
            "packet_loss_pct" to 0.25,
            "codec" to "HEVC",
            "duration_s" to 120,
            "samples" to 118,
            "monotonic_timestamp_ms" to 240_000L,
            "frames_expected" to 7_200L,
            "frames_received" to 7_198L,
            "frames_rendered" to 7_190L,
            "frames_lost" to 2L,
            "received_fps" to 59.9,
            "rendered_fps" to 59.5,
            "decode_latency_ms" to 4.2,
            "host_processing_latency_ms" to 8.1,
            "session_generation" to 12L,
            "primary_issue" to "must_not_escape",
            "device" to "Generic Handheld",
            "unique_id" to "abc123",
            "host" to "example-stream-host.lan"
        )

        val json = JsonParser.parseString(NovaHudSessionSummaryLog.format(summary)).asJsonObject

        assertEquals(59.5, json["avg_fps"].asDouble, 0.01)
        assertEquals(24.0, json["avg_latency_ms"].asDouble, 0.01)
        assertEquals(18000, json["avg_bitrate_kbps"].asInt)
        assertEquals("HEVC", json["codec"].asString)
        assertEquals("doctor_v2_raw", json["contract"].asString)
        assertTrue(json["observational"].asBoolean)
        assertEquals(7_198L, json["frames_received"].asLong)
        assertEquals(12L, json["session_generation"].asLong)
        assertFalse(json.has("primary_issue"))
        assertFalse(json.has("device"))
        assertFalse(json.has("unique_id"))
        assertFalse(json.has("host"))
    }

    @Test
    fun diagnosticReportIsHumanReadableAndPrivacySafe() {
        val summary = mapOf(
            "avg_fps" to 59.5,
            "target_fps" to 120.0,
            "safe_target_fps" to 60.0,
            "avg_latency_ms" to 24.0,
            "avg_bitrate_kbps" to 18000,
            "packet_loss_pct" to 0.25,
            "codec" to "HEVC",
            "frames_received" to 7_198L,
            "frames_rendered" to 7_190L,
            "frames_lost" to 2L,
            "primary_issue" to "host_render_limited",
            "health_grade" to "watch",
            "relaunch_recommended" to true,
            "device" to "Generic Handheld",
            "host" to "example-stream-host.lan",
            "unique_id" to "abc123"
        )

        val text = NovaHudDiagnosticReport.format(summary)

        assertTrue(text.contains("Nova stream evidence"))
        assertTrue(text.contains("Observed: 59.5 FPS / target 120 FPS"))
        assertFalse(text.contains("Suggested:"))
        assertFalse(text.contains("Health:"))
        assertTrue(text.contains("Network: 24 ms RTT / 0.25% loss"))
        assertTrue(text.contains("Counters: 7198 received / 7190 rendered / 2 lost"))
        assertTrue(text.contains("Observational only"))
        assertFalse(text.contains("Generic Handheld"))
        assertFalse(text.contains("example-stream-host"))
        assertFalse(text.contains("abc123"))
    }

    @Test
    fun diagnosticReportExcludesNovaDerivedDoctorClassificationAndRawHostIdentifiers() {
        val summary = mapOf(
            "avg_fps" to 58.0,
            "target_fps" to 60.0,
            "avg_latency_ms" to 32.0,
            "avg_bitrate_kbps" to 18000,
            "packet_loss_pct" to 3.4,
            "codec" to "HEVC",
            "diagnosis_classification" to "NET",
            "diagnosis_likely_cause" to "Wi-Fi jitter is the likely bottleneck.",
            "diagnosis_try_first" to "Lower bitrate",
            "diagnosis_confidence" to "high",
            "host" to "private-host.lan"
        )

        val text = NovaHudDiagnosticReport.format(summary)

        assertFalse(text.contains("Diagnosis:"))
        assertFalse(text.contains("Wi-Fi jitter"))
        assertFalse(text.contains("Try first:"))
        assertTrue(text.contains("Network: 32 ms RTT / 3.4% loss"))
        assertFalse(text.contains("private-host"))
    }
}
