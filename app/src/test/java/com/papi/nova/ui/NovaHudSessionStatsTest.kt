package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaHudSessionStatsTest {
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
    fun summaryRecommendsRelaunchForHighRefreshPacingFailure() {
        val stats = NovaHudSessionStats()

        stats.setTargetFps(120.0)
        repeat(12) { index ->
            stats.recordFps(54.0, nowMs = 1_000L + index)
            stats.recordLatency(24)
        }

        val summary = stats.summary(nowMs = 20_000)

        assertEquals(30.0, summary["safe_target_fps"] as Double, 0.01)
        assertEquals(true, summary["relaunch_recommended"])
        assertTrue((summary["frame_pacing_bad_pct"] as Double) > 90.0)
    }

    @Test
    fun summaryPrefersPolarisHealthRecoveryFields() {
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

        assertEquals("host_render_limited", summary["primary_issue"])
        assertEquals(60.0, summary["safe_target_fps"] as Double, 0.01)
        assertEquals(14000, summary["safe_bitrate_kbps"])
        assertEquals("h264", summary["safe_codec"])
        assertEquals("desktop", summary["safe_display_mode"])
        assertEquals(true, summary["relaunch_recommended"])
    }

    @Test
    fun sessionSummaryLogIncludesEvidenceFieldsAndExcludesIdentifiers() {
        val summary = mapOf(
            "avg_fps" to 59.5,
            "target_fps" to 60.0,
            "avg_latency_ms" to 24.0,
            "avg_bitrate_kbps" to 18000,
            "packet_loss_pct" to 0.25,
            "codec" to "HEVC",
            "duration_s" to 120,
            "samples" to 118,
            "health_grade" to "stable",
            "primary_issue" to "none",
            "issues" to listOf("decoder_watch"),
            "safe_target_fps" to 60.0,
            "relaunch_recommended" to false,
            "device" to "Retroid Pocket Flip2",
            "unique_id" to "abc123",
            "host" to "pc-papi.lan"
        )

        val json = JsonParser.parseString(NovaHudSessionSummaryLog.format(summary)).asJsonObject

        assertEquals(59.5, json["avg_fps"].asDouble, 0.01)
        assertEquals(24.0, json["avg_latency_ms"].asDouble, 0.01)
        assertEquals(18000, json["avg_bitrate_kbps"].asInt)
        assertEquals("HEVC", json["codec"].asString)
        assertEquals("decoder_watch", json["issues"].asJsonArray[0].asString)
        assertFalse(json.has("device"))
        assertFalse(json.has("unique_id"))
        assertFalse(json.has("host"))
    }
}
