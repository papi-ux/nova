package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaHudUiStateTest {
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

        assertEquals("119", state.fpsLabel)
        assertEquals("TGT 120", state.targetFpsLabel)
        assertEquals("18ms", state.latencyLabel)
        assertEquals("24 Mbps", state.bitrateLabel)
        assertEquals("1920×1080", state.resolutionLabel)
        assertEquals("HEVC", state.codecLabel)
        assertEquals("Stream Ready", state.autopilotLabel)
        assertEquals("Stream Ready", state.autopilotHudLabel)
        assertEquals("OK", state.autopilotCompactLabel)
        assertEquals(NovaHudTone.STABLE, state.fpsTone)
        assertEquals(NovaHudTone.STABLE, state.latencyTone)
        assertEquals(NovaHudTone.STABLE, state.statusTone)
        assertTrue(state.streamModeLabel.contains("Private Stream"))
        assertTrue(state.streamModeLabel.contains("GPU-native DMA-BUF"))
        assertFalse(state.streamModeLabel.contains("Headless"))
        // The Debug header has 96dp: the short label is the mode alone, the full label
        // stays for the diagnostics copy.
        assertEquals("Private Stream", state.streamModeShortLabel)
        assertEquals("55", state.lowOnePercentLabel)
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
    fun streamModeLabelNamesAutoSelectedVulkanEncoder() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 119.5,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc",
                    targetResidency = "gpu",
                    activeBackend = "vulkan",
                    selection = PolarisSessionStatus.EncoderSelectionStatus(
                        mode = "auto",
                        gpuDriver = "amdgpu",
                        policy = "amd_private_vulkan_live_probe",
                        preferredEncoder = "vulkan",
                        fallbackEncoder = "vaapi",
                        selectedEncoder = "vulkan",
                    ),
                ),
            ),
            sparklineSamples = emptyList(),
        )

        assertTrue(state.streamModeLabel.contains("Auto → Vulkan"))
    }

    @Test
    fun hudModesMapSlimCasualPerformanceAndDebugPreferences() {
        assertEquals(NovaHudMode.SLIM, NovaHudMode.fromPreference("slim"))
        assertEquals(NovaHudMode.SLIM, NovaHudMode.fromPreference("pill"))
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference("minimal"))
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.fromPreference("performance"))
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.fromPreference("debug"))
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.fromPreference("full"))
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.fromPreference("banner"))
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference("fps_only"))
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.fromPreference(null))
        // Guide + Y walks the ring from the default; Slim is one press past Debug so the
        // three layouts people already know keep their order.
        assertEquals(NovaHudMode.PERFORMANCE, NovaHudMode.MINIMAL.next())
        assertEquals(NovaHudMode.DEBUG, NovaHudMode.PERFORMANCE.next())
        assertEquals(NovaHudMode.SLIM, NovaHudMode.DEBUG.next())
        assertEquals(NovaHudMode.MINIMAL, NovaHudMode.SLIM.next())
        // A picker lists them smallest to largest.
        assertEquals(
            listOf(NovaHudMode.SLIM, NovaHudMode.MINIMAL, NovaHudMode.PERFORMANCE, NovaHudMode.DEBUG),
            NovaHudMode.entries
        )
    }

    @Test
    fun debugTilesGradeDecodeTimeAgainstTheFrameBudget() {
        fun debug(decodeMs: Double, target: Double, host: Double? = null) = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = target,
            latencyMs = 3,
            codec = "hevc",
            bitrateKbps = 20000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = emptyList(),
            decodeTimeMs = decodeMs,
            hostProcessingLatencyMs = host,
            incomingFps = 60.4,
            renderedFps = 59.6
        )

        val noSample = debug(0.0, 120.0)
        assertEquals("--", noSample.decodeTimeLabel)
        assertEquals(NovaHudTone.MUTED, noSample.decodeTone)
        assertEquals("--", noSample.hostLatencyLabel)

        // Under 10 ms the decimal is the difference between decoders.
        val quick = debug(3.0, 120.0, host = 2.14)
        assertEquals("3.0ms", quick.decodeTimeLabel)
        assertEquals(NovaHudTone.STABLE, quick.decodeTone)
        assertEquals("2.1ms", quick.hostLatencyLabel)
        assertEquals("60", quick.incomingFpsLabel)
        assertEquals("60", quick.renderedFpsLabel)

        // 13 ms is fine inside a 60 fps frame and a dropped frame at 120.
        assertEquals("13ms", debug(13.0, 60.0).decodeTimeLabel)
        assertEquals(NovaHudTone.WARNING, debug(13.0, 60.0).decodeTone)
        assertEquals(NovaHudTone.DANGER, debug(13.0, 120.0).decodeTone)
        // With no target yet the budget is 60 fps.
        assertEquals(NovaHudTone.WARNING, debug(9.0, 0.0).decodeTone)
        assertEquals(NovaHudTone.STABLE, debug(4.0, 0.0).decodeTone)
    }

    @Test
    fun fpsLabelRoundsNearTargetInsteadOfFlooringReconnectJitter() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.MINIMAL,
            fps = 119.8,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = listOf(119.8f)
        )

        assertEquals("120", state.fpsLabel)
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

        val slim = NovaHudUiState.from(
            mode = NovaHudMode.SLIM,
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
        assertEquals("", slim.targetFpsLabel)
        assertEquals("60", slim.fpsLabel)
        assertEquals("51ms", slim.latencyLabel)
        assertEquals("1080p", slim.resolutionLabel)
        // Slim spends its one line on numbers, so the bitrate drops its unit like Performance.
        assertEquals("24M", slim.bitrateLabel)
    }

    @Test
    fun debugNetworkRowGradesLossSoZeroIsTheOnlyGreen() {
        fun debug(loss: Double, jitter: Int = 2, lost: Long = 0L, latency: Int = 9) = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = latency,
            codec = "hevc",
            bitrateKbps = 20000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = emptyList(),
            packetLossPct = loss,
            rttVarianceMs = jitter,
            framesLost = lost
        )

        val noSample = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 9,
            codec = "hevc",
            bitrateKbps = 20000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = emptyList()
        )
        assertEquals("--", noSample.packetLossLabel)
        assertEquals(NovaHudTone.MUTED, noSample.packetLossTone)
        assertEquals("--", noSample.jitterLabel)
        assertEquals("--", noSample.framesLostLabel)

        val clean = debug(0.0)
        assertEquals("0%", clean.packetLossLabel)
        assertEquals(NovaHudTone.STABLE, clean.packetLossTone)
        assertEquals("2ms", clean.jitterLabel)
        assertEquals("0", clean.framesLostLabel)

        // A trace of loss is named as one instead of rounding to a green-looking 0%.
        val trace = debug(0.04)
        assertEquals("<0.1%", trace.packetLossLabel)
        assertEquals(NovaHudTone.WARNING, trace.packetLossTone)

        val some = debug(0.34, lost = 12L)
        assertEquals("0.3%", some.packetLossLabel)
        assertEquals(NovaHudTone.WARNING, some.packetLossTone)
        assertEquals("12", some.framesLostLabel)

        assertEquals("2.6%", debug(2.6).packetLossLabel)
        assertEquals(NovaHudTone.DANGER, debug(2.6).packetLossTone)
        assertEquals("12%", debug(12.4).packetLossLabel)

        // Jitter means nothing without a round trip to wobble around.
        assertEquals("--", debug(0.0, latency = 0).jitterLabel)
        assertEquals("0ms", debug(0.0, jitter = 0).jitterLabel)
    }

    @Test
    fun legacyRecoveryStatusUsesObservationalPacingWatch() {
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

        assertEquals("Frame Pacing Watch", state.autopilotLabel)
        assertEquals("Pacing Watch", state.autopilotHudLabel)
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

        assertEquals("Live bitrate adjusted", state.autopilotLabel)
        assertEquals("Bitrate Adjusted", state.autopilotHudLabel)
        assertEquals(NovaHudTone.WARNING, state.statusTone)
    }

    @Test
    fun cleanAutoSafeRecoveryDoesNotRaiseAttention() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.MINIMAL,
            fps = 120.0,
            targetFps = 120.0,
            latencyMs = 3,
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
                    targetResidency = "gpu"
                ),
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    adaptiveTargetBitrateKbps = 12000,
                    adaptiveBaseBitrateKbps = 20000,
                    adaptiveBitrateState = "recovering",
                    adaptiveBitrateReason = "healthy_window",
                    aiOptimizerEnabled = false
                ),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-clean-auto-safe-recovery",
                    status = "ok",
                    severity = "info",
                    trafficLight = "green",
                    primaryIssue = "none",
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "effective_quality_ceiling",
                            status = "watch",
                            source = "launch_policy",
                            value = 20000.0
                        )
                    )
                )
            ),
            sparklineSamples = listOf(120f)
        )

        assertEquals("Stable", state.healthReasonLabel)
        assertEquals("Recovering Bitrate", state.autopilotLabel)
        assertEquals(NovaHudTone.INFO, state.statusTone)
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
                health = PolarisSessionStatus.HealthStatus(
                    grade = "degraded",
                    summary = "Decoder timing needs attention"
                )
            ),
            sparklineSamples = emptyList()
        )

        assertEquals("Stream Ready", stable.autopilotHudLabel)
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
    fun normalRiskValuesDoNotRaiseNetworkOrDecoderWarnings() {
        // Polaris serves network_risk/decoder_risk unconditionally as "normal" | "elevated";
        // the healthy value must not read as a warning just because it is non-blank.
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "good",
                    networkRisk = "normal",
                    decoderRisk = "normal"
                )
            ),
            sparklineSamples = listOf(60f)
        )

        assertEquals("Stable", state.healthReasonLabel)
        assertEquals(NovaHudTone.STABLE, state.healthReasonTone)
        assertEquals(
            listOf(
                NovaHudLayerHealth("HOST", NovaHudTone.STABLE),
                NovaHudLayerHealth("NET", NovaHudTone.STABLE),
                NovaHudLayerHealth("CLIENT", NovaHudTone.STABLE)
            ),
            state.layerHealth
        )
    }

    @Test
    fun warningLatencyCannotRenderBesideStableDiagnosis() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 48,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status(),
            sparklineSamples = listOf(60f)
        )

        assertEquals(NovaHudTone.WARNING, state.latencyTone)
        assertEquals("High latency", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertFalse(state.healthReasonLabel.contains("Stable", ignoreCase = true))
        assertEquals(NovaHudTone.WARNING, state.layerHealth.single { it.label == "NET" }.tone)
    }

    @Test
    fun lowRenderedFpsAloneDoesNotInventAStaticContentPacingFault() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 30.0,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "good",
                    primaryIssue = "none",
                    networkRisk = "normal",
                    decoderRisk = "normal"
                ),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-static-clean",
                    primaryIssue = "none"
                )
            ),
            sparklineSamples = listOf(30f)
        )

        assertEquals("Stable", state.healthReasonLabel)
        assertEquals(NovaHudTone.STABLE, state.healthReasonTone)
        assertEquals(NovaHudTone.STABLE, state.fpsTone)
    }

    @Test
    fun framePacingWarningNeverContradictsItselfWithStableOrNetworkCopy() {
        val status = status(
            health = PolarisSessionStatus.HealthStatus(
                grade = "watch",
                primaryIssue = "frame_pacing",
                issues = listOf("frame_pacing"),
                networkRisk = "normal",
                decoderRisk = "normal"
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertEquals("Frame pacing", status.healthToneLabel)
        assertEquals("Frame pacing", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertFalse(state.healthReasonLabel.contains("Stable", ignoreCase = true))
        assertFalse(state.healthReasonLabel.contains("Network", ignoreCase = true))
    }

    @Test
    fun doctorOnlyWarningCannotRenderBesideStableAndKeepsItsLayer() {
        val status = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                primaryIssue = "none",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "packet_loss",
                        status = "fail",
                        source = "media_transport",
                        value = 3.4
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertTrue(status.hasHealthConcerns)
        assertEquals("Needs attention", status.healthToneLabel)
        assertEquals("Needs attention", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals(NovaHudTone.STABLE, state.layerHealth[0].tone)
        assertEquals(NovaHudTone.WARNING, state.layerHealth[1].tone)
        assertEquals(NovaHudTone.STABLE, state.layerHealth[2].tone)
    }

    @Test
    fun healthGradeFallbacksRemainWarningsWithoutSpecificEvidence() {
        val watch = status(health = PolarisSessionStatus.HealthStatus(grade = "watch"))
        val degraded = status(health = PolarisSessionStatus.HealthStatus(grade = "degraded"))

        assertEquals("Needs attention", watch.healthToneLabel)
        assertEquals("Stream degraded", degraded.healthToneLabel)
    }

    @Test
    fun elevatedNetworkRiskRaisesTheJitterWarning() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    networkRisk = "elevated",
                    decoderRisk = "normal"
                )
            ),
            sparklineSamples = listOf(60f)
        )

        assertEquals("Network jitter", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals(
            NovaHudLayerHealth("NET", NovaHudTone.WARNING),
            state.layerHealth[1]
        )
    }

    @Test
    fun unconfirmedNetworkObservationRequestsARecheckWithoutNetworkBlame() {
        val status = status(
            health = PolarisSessionStatus.HealthStatus(
                grade = "watch",
                primaryIssue = "network_jitter",
                issues = listOf("network_jitter"),
                networkRisk = "elevated"
            ),
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-network-observation",
                primaryIssue = "network_observation",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "packet_loss",
                        status = "pass",
                        source = "media_transport",
                        value = 0.4
                    ),
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "latency",
                        status = "watch",
                        source = "stream_stats",
                        value = 30.0
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 30,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertFalse(status.hasHealthConcerns)
        assertEquals("Network recheck", status.healthToneLabel)
        assertEquals("Network recheck", state.healthReasonLabel)
        assertEquals(NovaHudTone.MUTED, state.healthReasonTone)
        assertEquals(NovaHudTone.STABLE, state.latencyTone)
        assertEquals(NovaHudTone.STABLE, state.layerHealth[1].tone)
    }

    @Test
    fun confirmedMediaLossOverridesObservationSuppression() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 30,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(grade = "watch"),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-confirmed-loss",
                    primaryIssue = "network_observation",
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "fail",
                            source = "media_transport",
                            value = 3.2
                        )
                    )
                )
            ),
            sparklineSamples = listOf(60f)
        )

        assertEquals("Needs attention", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals(NovaHudTone.WARNING, state.layerHealth[1].tone)
    }

    @Test
    fun controlChannelRetriesRemainInformational() {
        val status = status(
            health = PolarisSessionStatus.HealthStatus(grade = "watch"),
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-control-observation",
                status = "ok",
                severity = "info",
                trafficLight = "green",
                primaryIssue = "control_channel_observation",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "control_channel_packet_loss",
                        status = "watch",
                        source = "enet_control_channel",
                        value = 8.0
                    ),
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "live_bitrate_control",
                        status = "watch",
                        source = "encoder_capability"
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertFalse(status.hasHealthConcerns)
        assertEquals("Link retries", status.healthToneLabel)
        assertEquals("Link retries", state.healthReasonLabel)
        assertEquals(NovaHudTone.MUTED, state.healthReasonTone)
        assertEquals(NovaHudTone.STABLE, state.layerHealth[1].tone)
    }

    @Test
    fun authoritativeAmberDoctorStillEscalatesWatchEvidence() {
        val status = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-control-needs-action",
                status = "needs_action",
                severity = "warning",
                trafficLight = "amber",
                primaryIssue = "control_channel_observation",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "live_bitrate_control",
                        status = "watch",
                        source = "encoder_capability"
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertTrue(status.hasHealthConcerns)
        assertEquals("Needs attention", status.healthToneLabel)
        assertEquals("Needs attention", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
    }

    @Test
    fun partialAuthoritativeVerdictFailsClosed() {
        val status = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-partial-verdict",
                status = "needs_action",
                severity = "warning",
                primaryIssue = "control_channel_observation",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "live_bitrate_control",
                        status = "watch",
                        source = "encoder_capability"
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertFalse(status.hasExplicitAuthoritativeDoctorVerdict)
        assertTrue(status.authoritativeDoctorVerdictNeedsAttention)
        assertTrue(status.hasHealthConcerns)
        assertEquals("Needs attention", status.healthToneLabel)
        assertEquals("Needs attention", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
    }

    @Test
    fun greenAuthoritativeVerdictCannotHideConfirmedMediaLoss() {
        val status = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-green-contradiction",
                status = "ok",
                severity = "info",
                trafficLight = "green",
                primaryIssue = "control_channel_observation",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "packet_loss",
                        status = "fail",
                        source = "media_transport",
                        value = 3.2
                    )
                )
            )
        )
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = status,
            sparklineSamples = listOf(60f)
        )

        assertTrue(status.authoritativeDoctorVerdictIsHealthy)
        assertTrue(status.hasActionableDoctorEvidence)
        assertTrue(status.hasHealthConcerns)
        assertEquals("Needs attention", status.healthToneLabel)
        assertEquals("Needs attention", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals(NovaHudTone.WARNING, state.layerHealth[1].tone)
    }

    @Test
    fun healthyStandardShmCaptureDoesNotShowAttention() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 117.0,
            targetFps = 120.0,
            latencyMs = 5,
            codec = "hevc_vulkan",
            bitrateKbps = 20_000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc_vulkan",
                    bitrateKbps = 20_000,
                    fps = 120.0,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    targetDevice = "vulkan",
                    targetResidency = "gpu"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    transport = "shm",
                    residency = "cpu"
                ),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-current-control-observation",
                    status = "ok",
                    severity = "info",
                    trafficLight = "green",
                    primaryIssue = "control_channel_observation",
                    likelyCause = "Control retries were observed without confirmed video loss."
                )
            ),
            sparklineSamples = listOf(116f, 117f, 118f)
        )

        assertEquals("OK", state.autopilotCompactLabel)
        assertEquals("Stream Ready", state.autopilotHudLabel)
        assertEquals(NovaHudTone.STABLE, state.statusTone)
        assertEquals("Link retries", state.healthReasonLabel)
        assertEquals(NovaHudTone.MUTED, state.healthReasonTone)
        assertTrue(state.streamModeLabel.contains("SHM/CPU capture"))
        assertEquals(NovaHudLayerHealth("HOST", NovaHudTone.STABLE), state.layerHealth.first())
    }

    @Test
    fun authoritativeDoctorNoneSuppressesStaleHealthNetworkAndPacingLabels() {
        val doctor = PolarisSessionStatus.DoctorStatus(
            available = true,
            version = 2,
            resultId = "doctor-current-clean",
            primaryIssue = "none"
        )
        val staleNetwork = status(
            health = PolarisSessionStatus.HealthStatus(
                grade = "watch",
                primaryIssue = "network_jitter",
                issues = listOf("network_jitter"),
                networkRisk = "elevated"
            ),
            doctor = doctor
        )
        val stalePacing = status(
            health = PolarisSessionStatus.HealthStatus(
                grade = "watch",
                primaryIssue = "frame_pacing",
                issues = listOf("frame_pacing")
            ),
            doctor = doctor
        )

        val networkState = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = staleNetwork,
            sparklineSamples = listOf(60f)
        )
        val pacingState = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 30.0,
            targetFps = 120.0,
            latencyMs = 12,
            codec = "hevc",
            bitrateKbps = 22_000,
            width = 1920,
            height = 1080,
            status = stalePacing,
            sparklineSamples = listOf(30f)
        )

        assertEquals("none", staleNetwork.effectivePrimaryIssue)
        assertFalse(staleNetwork.hasHealthConcerns)
        assertEquals("Stable", networkState.healthReasonLabel)
        assertEquals(NovaHudTone.STABLE, networkState.layerHealth[1].tone)
        assertEquals("Stable", pacingState.healthReasonLabel)
        assertEquals(NovaHudTone.STABLE, pacingState.fpsTone)
        assertEquals(NovaHudTone.STABLE, pacingState.layerHealth[0].tone)
    }

    @Test
    fun elevatedDecoderRiskRaisesDecoderLate() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 60.0,
            targetFps = 60.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    networkRisk = "normal",
                    decoderRisk = "elevated"
                )
            ),
            sparklineSamples = listOf(60f)
        )

        assertEquals("Decoder late", state.healthReasonLabel)
        assertEquals(NovaHudTone.WARNING, state.healthReasonTone)
        assertEquals(
            NovaHudLayerHealth("CLIENT", NovaHudTone.WARNING),
            state.layerHealth[2]
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
    fun diagnosticsExplainAmdVaapiHostCaptureTruthSeparatelyFromClientHealth() {
        val state = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 59.2,
            targetFps = 120.0,
            latencyMs = 18,
            codec = "hevc",
            bitrateKbps = 22000,
            width = 1920,
            height = 1080,
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc",
                    bitrateKbps = 22000,
                    fps = 60.0,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    targetDevice = "vaapi",
                    targetResidency = "gpu"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    transport = "shm",
                    residency = "cpu"
                ),
                linuxGpuProfile = PolarisSessionStatus.LinuxGpuProfile(
                    encoderApi = "vaapi",
                    encoderAdapter = "/dev/dri/renderD128",
                    captureDevice = "/dev/dri/renderD128",
                    adapterMatchesCaptureDevice = true,
                    gpuNativeRequested = true,
                    gpuNativeAttempted = true,
                    gpuNativeSucceeded = false,
                    vaapiVendor = "Mesa Gallium"
                )
            ),
            sparklineSamples = listOf(58f, 60f, 59f)
        )

        assertEquals("Stream 120 • VAAPI + SHM fallback", state.streamTruthLabel)
        assertEquals(NovaHudLayerHealth("VAAPI + SHM fallback", NovaHudTone.WARNING), state.layerHealth.first())
        assertTrue(state.layerHealth.any { it.label == "CLIENT" && it.tone == NovaHudTone.STABLE })
    }

    @Test
    fun eventBreadcrumbTrailKeepsLatestActionableHudEvent() {
        val trail = NovaHudEventTrail(capacity = 3)

        trail.recordBitrateChange(fromKbps = 30000, toKbps = 22000)
        assertEquals("Bitrate lowered: 30M → 22M", trail.latestLabel)

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

        assertEquals("Bitrate lowered: 30M → 22M", trail.latestLabel)
        assertEquals("Bitrate lowered: 30M → 22M", state.eventBreadcrumbLabel)
    }

    @Test
    fun retireRecoveryProfileRemovesStaleRecoveryButKeepsBitrateHistory() {
        val trail = NovaHudEventTrail(capacity = 4)

        trail.recordBitrateChange(fromKbps = 30000, toKbps = 22000)
        trail.record("Next launch recovery: 60 FPS")
        assertEquals("Next launch recovery: 60 FPS", trail.latestLabel)

        trail.retireRecoveryProfile()

        assertEquals("Bitrate lowered: 30M → 22M", trail.latestLabel)
    }

    @Test
    fun retireRecoveryProfileRemovesFallbackReadyEntries() {
        val trail = NovaHudEventTrail(capacity = 4)

        trail.recordBitrateChange(fromKbps = 22000, toKbps = 30000)
        trail.record("Fallback ready: 60 FPS")
        assertEquals("Fallback ready: 60 FPS", trail.latestLabel)

        trail.retireRecoveryProfile()

        assertEquals("Bitrate recovered: 22M → 30M", trail.latestLabel)
    }

    @Test
    fun retireRecoveryProfileIsNoOpWhenTrailHoldsNoRecoveryEntries() {
        val trail = NovaHudEventTrail(capacity = 4)

        trail.recordBitrateChange(fromKbps = 30000, toKbps = 22000)
        trail.recordBitrateChange(fromKbps = 22000, toKbps = 18000)

        trail.retireRecoveryProfile()

        assertEquals("Bitrate lowered: 22M → 18M", trail.latestLabel)
    }

    @Test
    fun retireRecoveryProfileClearsEveryRecoveryFamilyEntry() {
        val trail = NovaHudEventTrail(capacity = 4)

        trail.record("Fallback ready: 90 FPS")
        trail.recordBitrateChange(fromKbps = 30000, toKbps = 22000)
        trail.record("Next launch recovery: 60 FPS")

        trail.retireRecoveryProfile()

        assertEquals("Bitrate lowered: 30M → 22M", trail.latestLabel)
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
    fun streamHudConsumesStructuredPerfSamplesOnly() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            StandardCharsets.UTF_8
        )

        assertTrue(source.contains("fun updateFromPerfSample(sample: PerfOverlaySample)"))
        assertTrue(source.contains("updateFps(sample.fps)"))
        assertTrue(source.contains("sessionStats.recordRawMediaEvidence(sample)"))
        assertTrue(source.contains("lastDecodeTimeMs = sample.decodeTimeMs"))
        // The text path is gone on purpose. It made the decoder build the legacy overlay
        // string on the decode thread whenever the HUD was up, to regex out numbers the
        // struct already carries, and it published the HUD twice per sample.
        assertFalse(source.contains("updateFromPerfText"))
        assertFalse(source.contains("fromPerfText"))
    }

    @Test
    fun streamHudIsObservationalAndNeverOwnsBitrateMutation() {
        val hudSource = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            StandardCharsets.UTF_8
        )
        val gameSource = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/Game.kt")),
            StandardCharsets.UTF_8
        )

        assertFalse(hudSource.contains("onBitrateAdjust"))
        assertFalse(hudSource.contains("setBitrate("))
        assertFalse(hudSource.contains("currentBitrateKbps * 0.75"))
        assertFalse(gameSource.contains("hud.onBitrateAdjust"))
        assertTrue(gameSource.contains("uploadDoctorSample(sample)"))
    }

    @Test
    fun streamHudNeverTurnsRecoveryHistoryIntoANextLaunchBreadcrumb() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            StandardCharsets.UTF_8
        )

        assertTrue(source.contains("eventTrail.retireRecoveryProfile()"))
        assertFalse(source.contains("eventTrail.recordRecoveryProfile("))
    }

    @Test
    fun streamHudUnconditionallyRetiresLegacyRecoveryBreadcrumbs() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            StandardCharsets.UTF_8
        )

        assertTrue(source.contains("eventTrail.retireRecoveryProfile()"))
        assertFalse(source.contains("status?.health?.safeTargetFps"))
        assertFalse(source.contains("status?.autoQuality?.isRecoveryQueued"))
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
        doctor: PolarisSessionStatus.DoctorStatus = PolarisSessionStatus.DoctorStatus(),
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
        ),
        linuxGpuProfile: PolarisSessionStatus.LinuxGpuProfile? = null
    ) = PolarisSessionStatus(
        state = "streaming",
        streamingActive = true,
        adaptiveBitrateEnabled = true,
        aiOptimizerEnabled = true,
        tuning = tuning,
        encoder = encoder,
        capture = capture,
        health = health,
        doctor = doctor,
        autoQuality = autoQuality,
        displayMode = displayMode,
        linuxGpuProfile = linuxGpuProfile,
        syncStatus = PolarisSessionStatus.SyncStatus(
            available = true,
            state = "synced"
        )
    )
}
