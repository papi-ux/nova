package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaHudUiStateTest {
    @Test
    fun performanceTextParserExtractsHudSample() {
        val sample = NovaHudPerfSample.fromPerfText(
            """
            Video stream: 1920x1080
            Decoder: hevc
            FPS: 59.8
            RTT: 18 ms
            Packet loss: 1.5%
            """.trimIndent()
        )

        assertEquals(59.8, sample.fps!!, 0.01)
        assertEquals(1920, sample.width)
        assertEquals(1080, sample.height)
        assertEquals(18, sample.latencyMs)
        assertEquals("HEVC", sample.codec)
        assertEquals(1.5, sample.packetLossPct!!, 0.01)
    }

    @Test
    fun fullModeFormatsReadableLabelsAndTones() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.FULL,
            fps = 118.7,
            targetFps = 120.0,
            latencyMs = 18,
            codec = "hevc_nvenc",
            bitrateKbps = 24187,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = listOf(55f, 58f, 60f)
        )

        assertEquals("118", state.fpsLabel)
        assertEquals("TGT 120", state.targetFpsLabel)
        assertEquals("18ms", state.latencyLabel)
        assertEquals("24 Mbps", state.bitrateLabel)
        assertEquals("1920×1080", state.resolutionLabel)
        assertEquals("HEVC", state.codecLabel)
        assertEquals("Auto Quality Stable", state.autopilotLabel)
        assertEquals("Auto Stable", state.autopilotHudLabel)
        assertEquals("OK", state.autopilotCompactLabel)
        assertEquals(NovaHudTone.STABLE, state.fpsTone)
        assertEquals(NovaHudTone.STABLE, state.latencyTone)
        assertEquals(NovaHudTone.STABLE, state.statusTone)
        assertTrue(state.streamModeLabel.contains("Headless"))
        assertEquals(listOf(55f, 58f, 60f), state.sparklineSamples)
    }

    @Test
    fun compactModesUseDenseLabels() {
        val banner = NovaHudUiState.from(
            mode = NovaHudMode.BANNER,
            fps = 59.7,
            targetFps = 120.0,
            latencyMs = 51,
            codec = "AV1 Main",
            bitrateKbps = 24187,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = emptyList()
        )
        val fpsOnly = banner.copy(mode = NovaHudMode.FPS_ONLY)

        assertEquals("/120", banner.targetFpsLabel)
        assertEquals("24M", banner.bitrateLabel)
        assertEquals("1080p", banner.resolutionLabel)
        assertEquals("AV1", banner.codecLabel)
        assertEquals(NovaHudTone.DANGER, banner.latencyTone)
        assertEquals("/120", fpsOnly.targetFpsLabel)
    }

    @Test
    fun recoveryStatusUsesWarningAutopilotTone() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.FPS_ONLY,
            fps = 42.0,
            targetFps = 120.0,
            latencyMs = 24,
            codec = "h264",
            bitrateKbps = 12000,
            width = 1280,
            height = 720,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    safeTargetFps = 60.0,
                    relaunchRecommended = true
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "recovery_queued",
                    relaunchRequired = true,
                    suggestedTargetFps = 60.0
                )
            ),
            sparklineSamples = listOf(42f)
        )

        assertEquals("AI Recovery Profile", state.autopilotLabel)
        assertEquals("AI Recovery", state.autopilotHudLabel)
        assertEquals("HOST", state.autopilotCompactLabel)
        assertEquals(NovaHudTone.WARNING, state.statusTone)
        assertEquals(NovaHudTone.WARNING, state.fpsTone)
    }

    @Test
    fun hudLabelsStayCompactForSpaceConstrainedOverlay() {
        val stable = NovaHudUiState.from(
            mode = NovaHudMode.FULL,
            fps = 118.7,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = emptyList()
        )
        val upgrade = NovaHudUiState.from(
            mode = NovaHudMode.FULL,
            fps = 118.7,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = status(
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "upgrade_available"
                )
            ),
            sparklineSamples = emptyList()
        )
        val attention = NovaHudUiState.from(
            mode = NovaHudMode.FULL,
            fps = 118.7,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(targetResidency = "cpu")
            ),
            sparklineSamples = emptyList()
        )

        assertEquals("Auto Stable", stable.autopilotHudLabel)
        assertEquals("Quality Ready", upgrade.autopilotHudLabel)
        assertEquals("Attention", attention.autopilotHudLabel)
        assertTrue(
            listOf(stable, upgrade, attention).all {
                it.autopilotHudLabel.length <= 16
            }
        )
    }

    private fun status(
        encoder: PolarisSessionStatus.EncoderStatus = PolarisSessionStatus.EncoderStatus(
            codec = "hevc_nvenc",
            bitrateKbps = 30000,
            fps = 120.0,
            requestedClientFps = 120.0,
            sessionTargetFps = 120.0,
            encodeTargetFps = 120.0,
            optimizationSource = "ai_cached",
            optimizationCacheStatus = "hit",
            targetResidency = "gpu"
        ),
        capture: PolarisSessionStatus.CaptureStatus = PolarisSessionStatus.CaptureStatus(
            transport = "dmabuf",
            residency = "gpu"
        ),
        health: PolarisSessionStatus.HealthStatus = PolarisSessionStatus.HealthStatus(grade = "good"),
        autoQuality: PolarisSessionStatus.AutoQualityPolicy = PolarisSessionStatus.AutoQualityPolicy(),
        displayMode: PolarisSessionStatus.DisplayModeStatus = PolarisSessionStatus.DisplayModeStatus(
            requested = "headless",
            effectiveHeadless = true
        )
    ) = PolarisSessionStatus(
        state = "streaming",
        streamingActive = true,
        adaptiveBitrateEnabled = true,
        aiOptimizerEnabled = true,
        tuning = PolarisSessionStatus.TuningStatus(
            adaptiveBitrateEnabled = true,
            adaptiveTargetBitrateKbps = 30000,
            adaptiveBaseBitrateKbps = 30000,
            aiOptimizerEnabled = true
        ),
        encoder = encoder,
        capture = capture,
        health = health,
        autoQuality = autoQuality,
        displayMode = displayMode,
        syncStatus = PolarisSessionStatus.SyncStatus(
            available = true,
            state = "synced"
        )
    )
}
