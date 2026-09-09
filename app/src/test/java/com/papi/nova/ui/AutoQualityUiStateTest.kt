package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.api.LiveTuningStatus
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class AutoQualityUiStateTest {
    @Test fun sharedStatesDescribeAcknowledgedIntentAndRate() {
        val fixtures = JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
        for (i in 0 until fixtures.length()) {
            val live = LiveTuningStatus.parse(fixtures.getJSONObject(i).getJSONObject("live_tuning"))!!
            val status = status().copy(liveTuning = live, liveTuningPresent = true)
            val ui = AutoQualityUiState.from(status)
            assertEquals(live.enabled, ui.enabled)
            assertTrue(ui.label.startsWith("Live Tuning"))
            assertEquals(live.appliedBitrateKbps, StreamPolicyUiState.from(status).effectiveBitrateKbps)
        }
    }
    @Test fun aiReadinessCannotEnableLiveTuning() {
        val ui = AutoQualityUiState.from(status(adaptiveBitrateEnabled = false, aiOptimizerEnabled = true))
        assertFalse(ui.enabled)
        assertEquals("Live Tuning Off", ui.label)
    }
    @Test fun healthWarningsCannotEraseEnabledState() {
        val unhealthy = status(health = PolarisSessionStatus.HealthStatus(grade = "degraded", summary = "Network pressure"))
        val ui = AutoQualityUiState.from(unhealthy)
        assertTrue(ui.enabled)
        assertEquals("Live Tuning On", ui.label)
        assertEquals("Network pressure", unhealthy.health.summary)
    }
    @Test fun missingOrUnsupportedSchemaIsUnknown() {
        assertEquals("Tuning: Unknown", AutoQualityUiState.from(null).compactLabel)
        assertEquals("Tuning: Unknown", AutoQualityUiState.from(status().copy(liveTuningPresent = true)).compactLabel)
    }
    @Test fun legacyTargetDoesNotClaimEncoderAcknowledgement() {
        val ui = AutoQualityUiState.from(status())
        assertTrue(ui.detail.contains("acknowledgement is unavailable"))
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
        assertEquals(
            "Live tuning is at 4.7 Mbps under your 50 Mbps quality limit.",
            policy.statusCaption
        )
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
        doctor: PolarisSessionStatus.DoctorStatus = PolarisSessionStatus.DoctorStatus(),
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
        doctor = doctor,
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
