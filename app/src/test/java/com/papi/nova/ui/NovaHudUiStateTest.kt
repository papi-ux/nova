package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
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
            mode = NovaHudMode.DEBUG,
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
    fun cudaTargetDeviceKeepsGpuPathInStreamModeLabelWhenResidencyMissing() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 59.8,
            targetFps = 60.0,
            latencyMs = 18,
            codec = "hevc_nvenc",
            bitrateKbps = 20000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc_nvenc",
                    targetDevice = "cuda",
                    targetResidency = "",
                    targetFormat = "p010"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    transport = "dmabuf",
                    residency = ""
                )
            ),
            sparklineSamples = emptyList()
        )

        assertTrue(state.streamModeLabel.contains("GPU"))
        assertTrue(state.streamModeLabel.contains("10b"))
    }

    @Test
    fun hudModesMapCasualPerformanceAndDebugPreferences() {
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference("minimal"))
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.fromPreference("performance"))
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.fromPreference("debug"))
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.fromPreference("full"))
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.fromPreference("banner"))
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference("fps_only"))
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference(null))
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.MINIMAL.next())
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.PERFORMANCE.next())
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.DEBUG.next())
    }

    @Test
    fun visualModesFormatMetricsForTheirUseCase() {
        val performance = NovaHudUiState.from(
            mode = NovaHudMode.PERFORMANCE,
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
        val minimal = NovaHudUiState.from(
            mode = NovaHudMode.MINIMAL,
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
        val debug = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
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

        assertEquals("/120", performance.targetFpsLabel)
        assertEquals("24M", performance.bitrateLabel)
        assertEquals("1080p", performance.resolutionLabel)
        assertEquals("AV1", performance.codecLabel)
        assertEquals(NovaHudTone.DANGER, performance.latencyTone)
        assertEquals("", minimal.targetFpsLabel)
        assertEquals("24 Mbps", debug.bitrateLabel)
    }

    @Test
    fun recoveryStatusUsesWarningAutopilotTone() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.MINIMAL,
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
    fun autoSafeBitrateCapUsesExplicitHudLabel() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.MINIMAL,
            fps = 118.7,
            targetFps = 120.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 12000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc_nvenc",
                    bitrateKbps = 12000,
                    fps = 120.0,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    optimizationSource = "ai_cached",
                    optimizationCacheStatus = "hit",
                    targetResidency = "gpu"
                ),
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    adaptiveTargetBitrateKbps = 12000,
                    adaptiveBaseBitrateKbps = 28000,
                    aiOptimizerEnabled = true
                )
            ),
            sparklineSamples = listOf(118f)
        )

        assertEquals("Auto Safe capped", state.autopilotLabel)
        assertEquals("Auto Safe", state.autopilotHudLabel)
        assertEquals(NovaHudTone.WARNING, state.statusTone)
    }

    @Test
    fun hudLabelsStayCompactForSpaceConstrainedOverlay() {
        val stable = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
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
            mode = NovaHudMode.DEBUG,
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
            mode = NovaHudMode.DEBUG,
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

    @Test
    fun diagnosticsExplainHealthReasonStreamTruthAndLayerHealth() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 59.2,
            targetFps = 120.0,
            latencyMs = 18,
            codec = "hevc_nvenc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    safeTargetFps = 60.0,
                    relaunchRecommended = true
                )
            ),
            sparklineSamples = listOf(58f, 60f, 59f)
        )

        assertEquals("Host capped", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals("Stream 120 • Game capped 60", state.streamTruthLabel)
        assertEquals(
            listOf(
                NovaHudLayerHealth("HOST", NovaHudTone.WARNING),
                NovaHudLayerHealth("NET", NovaHudTone.STABLE),
                NovaHudLayerHealth("CLIENT", NovaHudTone.STABLE)
            ),
            state.layerHealth
        )
    }

    @Test
    fun hdrDowngradeUsesExplicitWarningCopyAndTenBitSdrTruth() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.PERFORMANCE,
            fps = 59.0,
            targetFps = 60.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "hdr_downgraded",
                    issues = listOf("hdr_downgraded"),
                    hdrEffectiveMode = "sdr_10bit",
                    hdrDowngradeReason = "headless_hdr_unavailable",
                    hdrDowngradeMessage = "Polaris is streaming 10-bit SDR, not HDR.",
                    safeHdr = false,
                    relaunchRecommended = true
                )
            ),
            sparklineSamples = listOf(59f)
        )

        assertEquals("HDR downgraded", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals("Stream 60 • 10-bit SDR", state.streamTruthLabel)
        assertEquals(NovaHudTone.WARNING, state.statusTone)
    }
    @Test
    fun eventBreadcrumbTrailKeepsLatestActionableHudEvent() {
        val trail = NovaHudEventTrail(capacity = 3)

        trail.recordBitrateChange(fromKbps = 30000, toKbps = 22000)
        assertEquals("Bitrate lowered: 30M → 22M", trail.latestLabel)

        trail.recordRecoveryProfile(targetFps = 60.0)
        val state = NovaHudUiState.from(
            mode = NovaHudMode.PERFORMANCE,
            fps = 59.0,
            targetFps = 120.0,
            latencyMs = 20,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = listOf(59f),
            eventBreadcrumbLabel = trail.latestLabel
        )

        assertEquals("Next launch recovery: 60 FPS", trail.latestLabel)
        assertEquals("Next launch recovery: 60 FPS", state.eventBreadcrumbLabel)
    }

    @Test
    fun sparklineBufferKeepsLatestSixtySamplesInOrder() {
        val buffer = NovaHudSparklineBuffer(capacity = 60)

        for (i in 1..65) {
            buffer.add(i.toFloat())
        }

        val snapshot = buffer.snapshot()
        assertEquals(60, snapshot.size)
        assertEquals(6f, snapshot.first(), 0.01f)
        assertEquals(65f, snapshot.last(), 0.01f)
    }

    @Test
    fun sparklineBufferCalculatesLowOnePercentWithoutMutatingSamples() {
        val buffer = NovaHudSparklineBuffer(capacity = 60)
        listOf(60f, 58f, 59f, 42f, 61f).forEach(buffer::add)

        assertEquals(42.0, buffer.lowOnePercent(), 0.01)
        assertEquals(listOf(60f, 58f, 59f, 42f, 61f), buffer.snapshot())
    }

    @Test
    fun streamHudConsumesStructuredPerfSamplesBesideTextFallback() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            StandardCharsets.UTF_8
        )

        assertTrue(source.contains("fun updateFromPerfSample(sample: PerfOverlaySample)"))
        assertTrue(source.contains("updateFps(sample.fps)"))
        assertTrue(source.contains("updateFromPerfText(text: String)"))
        assertTrue(source.contains("NovaHudPerfSample.fromPerfText(text)"))
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
        ),
        tuning: PolarisSessionStatus.TuningStatus = PolarisSessionStatus.TuningStatus(
            adaptiveBitrateEnabled = true,
            adaptiveTargetBitrateKbps = 30000,
            adaptiveBaseBitrateKbps = 30000,
            aiOptimizerEnabled = true
        )
    ) = PolarisSessionStatus(
        state = "streaming",
        streamingActive = true,
        adaptiveBitrateEnabled = true,
        aiOptimizerEnabled = true,
        tuning = tuning,
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
