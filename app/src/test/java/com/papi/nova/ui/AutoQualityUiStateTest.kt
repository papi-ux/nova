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
class AutoQualityUiStateTest {
    @Test
    fun stableQualityRunShowsStable() {
        val state = AutoQualityUiState.from(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "good",
                    safeBitrateKbps = 30000,
                    safeDisplayMode = "headless"
                ),
                encoder = encoder(fps = 118.0),
                capture = capture()
            ),
            fallbackTargetFps = 120.0,
            lastRenderedFps = 117.0
        )

        assertEquals(AutoQualityUiState.State.STABLE, state.state)
        assertEquals("Auto Quality Stable", state.label)
        assertTrue(state.targetSummary.contains("HEVC"))
    }

    @Test
    fun warningOnlyFramePacingEvidenceNeverShowsStable() {
        val state = AutoQualityUiState.from(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Stable",
                    primaryIssue = "frame_pacing",
                    issues = listOf("frame_pacing")
                )
            )
        )

        assertEquals(AutoQualityUiState.State.RECOVERING, state.state)
        assertEquals("Frame pacing", state.label)
        assertEquals(AutoQualityUiState.Tone.WARNING, state.tone)
        assertEquals("Frame pacing", state.detail)
    }

    @Test
    fun adaptiveTargetBelowBaseShowsAutoSafeCap() {
        val state = AutoQualityUiState.from(
            status = status(
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    adaptiveTargetBitrateKbps = 12000,
                    adaptiveBaseBitrateKbps = 30000,
                    aiOptimizerEnabled = true
                )
            )
        )

        assertEquals(AutoQualityUiState.State.RECOVERING, state.state)
        assertEquals("Auto Safe capped", state.label)
        assertTrue(state.targetSummary.contains("12 Mbps live / 30 Mbps limit"))
    }

    @Test
    fun hostRenderRecoveryQueuedShowsAiRecoveryProfile() {
        val state = AutoQualityUiState.from(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    safeTargetFps = 60.0,
                    recoveryProfile = "host_render_limited",
                    relaunchRecommended = true
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "recovery_queued",
                    relaunchRequired = true,
                    suggestedTargetFps = 60.0
                )
            )
        )

        assertEquals(AutoQualityUiState.State.RECOVERING, state.state)
        assertEquals("AI Recovery Profile", state.label)
        assertEquals("HOST", state.compactLabel)
        assertEquals(true, state.recovering)
    }

    @Test
    fun healthyManualQualityPresetShowsStable() {
        val state = AutoQualityUiState.from(
            status = status(
                sync = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "manual_override",
                    manualOverride = true
                )
            )
        )

        assertEquals(AutoQualityUiState.State.STABLE, state.state)
        assertEquals("Quality Preset", state.label)
        assertTrue(state.manualOverride)
    }

    @Test
    fun manualQualityDoesNotHideHostRenderRecovery() {
        val state = AutoQualityUiState.from(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Host render path is missing the target frame rate",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    safeTargetFps = 60.0,
                    recoveryProfile = "host_render_limited",
                    relaunchRecommended = true
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "recovery_queued",
                    relaunchRequired = true,
                    suggestedTargetFps = 60.0
                ),
                sync = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "manual_override",
                    manualOverride = true
                )
            )
        )

        assertEquals(AutoQualityUiState.State.RECOVERING, state.state)
        assertEquals("AI Recovery Profile", state.label)
        assertTrue(state.manualOverride)
    }

    @Test
    fun targetSummaryPrefersEffectivePolarisBitrate() {
        val state = AutoQualityUiState.from(
            status = status(
                encoder = encoder(bitrateKbps = 50000),
                sync = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "synced",
                    effective = PolarisSessionStatus.SyncValues(targetBitrateKbps = 24187)
                )
            )
        )

        assertTrue(state.targetSummary.contains("up to 24 Mbps"))
    }

    @Test
    fun streamPolicySeparatesLiveAdaptiveTargetFromQualityLimit() {
        val policy = StreamPolicyUiState.from(
            status(
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    adaptiveTargetBitrateKbps = 4707,
                    adaptiveBaseBitrateKbps = 50000,
                    aiOptimizerEnabled = true
                ),
                encoder = encoder(bitrateKbps = 50000),
                sync = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "synced",
                    effective = PolarisSessionStatus.SyncValues(targetBitrateKbps = 50000)
                )
            )
        )

        assertEquals(4707, policy.effectiveBitrateKbps)
        assertEquals(50000, policy.qualityLimitBitrateKbps)
        assertEquals("4.7 Mbps live / 50 Mbps limit", policy.bitrateSummary)
        assertTrue(policy.statusCaption.contains("under your 50 Mbps quality limit"))
    }

    @Test
    fun streamPolicyNamesAmdVaapiHostCaptureTruthForCommandCenter() {
        val policy = StreamPolicyUiState.from(
            status(
                encoder = encoder(bitrateKbps = 22000).copy(codec = "hevc", targetDevice = "vaapi"),
                capture = capture(transport = "shm", residency = "cpu"),
                linuxGpuProfile = PolarisSessionStatus.LinuxGpuProfile(
                    encoderApi = "vaapi",
                    encoderAdapter = "/dev/dri/renderD128",
                    captureDevice = "/dev/dri/renderD128",
                    adapterMatchesCaptureDevice = true,
                    gpuNativeRequested = true,
                    gpuNativeAttempted = true,
                    gpuNativeSucceeded = false
                )
            )
        )

        assertEquals("VAAPI + SHM fallback", policy.hostCaptureLabel)
        assertTrue(policy.targetSummary.contains("VAAPI + SHM fallback"))
    }

    @Test
    fun autoQualityPolicyShowsBitrateRecovery() {
        val state = AutoQualityUiState.from(
            status = status(
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "recovering_bitrate",
                    liveBitrateKbps = 12000,
                    qualityCapKbps = 30000,
                    canRecoverLive = true,
                    summary = "Recovering bitrate toward the quality cap."
                )
            )
        )

        assertEquals(AutoQualityUiState.State.RECOVERING, state.state)
        assertEquals("Recovering Bitrate", state.label)
        assertTrue(state.recovering)
        assertTrue(state.targetSummary.contains("12 Mbps live / 30 Mbps limit"))
    }

    @Test
    fun autoQualityPolicyShowsUpgradeAvailable() {
        val state = AutoQualityUiState.from(
            status = status(
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    state = "upgrade_available",
                    relaunchRequired = true,
                    summary = "Higher quality is available on the next launch."
                )
            )
        )

        assertEquals(AutoQualityUiState.State.UPGRADE_AVAILABLE, state.state)
        assertEquals("Higher Quality Ready", state.label)
        assertEquals("UP", state.compactLabel)
    }

    @Test
    fun cpuCaptureNeedsAttention() {
        val state = AutoQualityUiState.from(
            status = status(capture = capture(transport = "shm", residency = "cpu"))
        )

        assertEquals(AutoQualityUiState.State.NEEDS_ATTENTION, state.state)
        assertEquals("Needs Attention", state.label)
    }

    @Test
    fun disabledAutoQualityReportsOff() {
        val state = AutoQualityUiState.from(
            status = status(
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = false,
                    aiOptimizerEnabled = false
                ),
                adaptiveBitrateEnabled = false,
                aiOptimizerEnabled = false
            )
        )

        assertEquals(AutoQualityUiState.State.OFF, state.state)
        assertEquals("Auto Quality Off", state.label)
    }

    private fun status(
        state: String = "streaming",
        streamingActive: Boolean = true,
        adaptiveBitrateEnabled: Boolean = true,
        aiOptimizerEnabled: Boolean = true,
        tuning: PolarisSessionStatus.TuningStatus = PolarisSessionStatus.TuningStatus(
            adaptiveBitrateEnabled = adaptiveBitrateEnabled,
            adaptiveTargetBitrateKbps = 30000,
            adaptiveBaseBitrateKbps = 30000,
            aiOptimizerEnabled = aiOptimizerEnabled
        ),
        encoder: PolarisSessionStatus.EncoderStatus = encoder(),
        capture: PolarisSessionStatus.CaptureStatus = capture(),
        health: PolarisSessionStatus.HealthStatus = PolarisSessionStatus.HealthStatus(grade = "good"),
        autoQuality: PolarisSessionStatus.AutoQualityPolicy = PolarisSessionStatus.AutoQualityPolicy(),
        linuxGpuProfile: PolarisSessionStatus.LinuxGpuProfile? = null,
        sync: PolarisSessionStatus.SyncStatus = PolarisSessionStatus.SyncStatus(
            available = true,
            state = "synced"
        )
    ) = PolarisSessionStatus(
        state = state,
        streamingActive = streamingActive,
        adaptiveBitrateEnabled = adaptiveBitrateEnabled,
        adaptiveTargetBitrateKbps = tuning.adaptiveTargetBitrateKbps,
        aiOptimizerEnabled = aiOptimizerEnabled,
        tuning = tuning,
        encoder = encoder,
        capture = capture,
        autoQuality = autoQuality,
        health = health,
        linuxGpuProfile = linuxGpuProfile,
        syncStatus = sync
    )

    private fun encoder(fps: Double = 120.0, bitrateKbps: Int = 30000) = PolarisSessionStatus.EncoderStatus(
        codec = "hevc_nvenc",
        bitrateKbps = bitrateKbps,
        fps = fps,
        requestedClientFps = 120.0,
        sessionTargetFps = 120.0,
        encodeTargetFps = 120.0,
        optimizationSource = "ai_cached",
        optimizationCacheStatus = "hit",
        targetResidency = "gpu"
    )

    private fun capture(
        transport: String = "dmabuf",
        residency: String = "gpu"
    ) = PolarisSessionStatus.CaptureStatus(
        resolution = "1920x1080",
        transport = transport,
        residency = residency
    )
}
