package com.papi.nova.ui

import android.content.Context
import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaQuickMenuUiStateTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun viewerSessionShowsLeaveAndLocksOwnerOnlyControls() {
        val state = quickState(
            status = status(
                clientRole = "viewer",
                controls = PolarisSessionStatus.ControlsStatus(
                    hostTuningAllowed = false,
                    quitAllowed = false
                )
            ),
            currentGameName = "Portal"
        )

        assertEquals("Leave", state.endAction.label)
        assertFalse(state.controlRows.first { it.id == NovaQuickMenuActionId.MOUSE_MODE }.enabled)
        assertFalse(state.controlRows.first { it.id == NovaQuickMenuActionId.KEYBOARD }.enabled)
        assertFalse(state.advancedRows.first { it.id == NovaQuickMenuActionId.AI_AUTO_QUALITY }.enabled)
        assertEquals("Owner", state.stability.chip.label)
        assertEquals(NovaQuickMenuTone.MUTED, state.stability.chip.tone)
    }

    @Test
    fun syncNeedsRelaunchUsesWarningChipAndRelaunchCaption() {
        val state = quickState(
            status = status(
                syncStatus = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "needs_relaunch",
                    message = "Saved settings apply on next launch"
                )
            )
        )

        val syncChip = state.sync.chip!!
        assertEquals("Relaunch", syncChip.label)
        assertEquals(NovaQuickMenuTone.WARNING, syncChip.tone)
        assertEquals("Saved settings apply on next launch", state.sync.caption)
    }

    @Test
    fun hostRenderLimitedSessionWarnsWithPlayerReadableRecoveryCopy() {
        val state = quickState(
            status = status(
                aiOptimizerEnabled = true,
                tuning = PolarisSessionStatus.TuningStatus(aiOptimizerEnabled = true),
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Host render path is missing the target frame rate",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    relaunchRecommended = true
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    enabled = true,
                    state = "recovery_queued",
                    relaunchRequired = true
                )
            ),
            aiEnabled = true
        )

        assertEquals("AI Recovery Profile ready for next launch.", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
        assertEquals("AI Recovery Profile", state.stability.chip.label)
        assertEquals(NovaQuickMenuTone.WARNING, state.stability.chip.tone)
    }

    @Test
    fun hostRenderLimitedWithoutRecoveryUsesMonitoringCopyOnly() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Host render path is missing the target frame rate",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    relaunchRecommended = false
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    enabled = true,
                    state = "blocked",
                    blockedReason = "host_render_limited",
                    relaunchRequired = false
                )
            )
        )

        assertEquals("Host is rendering below the stream FPS target.", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
    }

    @Test
    fun headlessHdrDowngradeShowsPlayerReadableCommandCenterCopy() {
        val state = quickState(
            status = status(
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "headless",
                    effectiveHeadless = true
                ),
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "hdr_downgraded",
                    issues = listOf("hdr_downgraded")
                )
            )
        )

        assertEquals("HDR requested, but Private Stream is 10-bit SDR.", state.healthSummary)
        assertEquals("Private Stream does not report HDR metadata. Polaris is sending 10-bit SDR; use an HDR-capable display path for true HDR.", state.healthDetail)
        assertEquals("Polaris is sending 10-bit SDR, not HDR. Use an HDR-capable display path for true HDR.", state.stability.caption)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
    }

    @Test
    fun controllerToggleCopyClarifiesTouchOverlayInsteadOfPhysicalGamepad() {
        val state = quickState(status = status(), currentGameName = "Portal")
        val touchControls = state.controlRows.first { it.id == NovaQuickMenuActionId.CONTROLLER }

        assertEquals("Touch Controls", touchControls.label)
        assertEquals("On-screen overlay; physical gamepad stays active.", touchControls.caption)
        assertEquals("Off", touchControls.chip!!.label)
    }

    @Test
    fun nonPolarisSessionDisablesHostTuningRows() {
        val state = quickState(status = null, apiAvailable = false)

        assertEquals("Checking stream mode", state.sessionMode.label)
        assertEquals("N/A", state.advancedRows.first { it.id == NovaQuickMenuActionId.AI_AUTO_QUALITY }.chip!!.label)
        assertEquals("N/A", state.advancedRows.first { it.id == NovaQuickMenuActionId.MANGOHUD }.chip!!.label)
        assertEquals("not a Polaris session", state.advancedRows.first { it.id == NovaQuickMenuActionId.AI_AUTO_QUALITY }.caption)
        assertFalse(state.advancedRows.first { it.id == NovaQuickMenuActionId.CLEAR_GAME_PROFILE }.enabled)
    }

    @Test
    fun previewStateExposesCoreActionsForComposeContent() {
        val state = NovaQuickMenuUiState.preview(context).copy(advancedExpanded = true)

        assertEquals("Command Center", state.title)
        assertEquals("Quick keys and controls for Private Stream", state.subtitle)
        assertEquals("Disconnect", state.disconnectAction.label)
        assertEquals("End session", state.endAction.label)
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_ESC && it.label == "ESC" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_V && it.label == "Ctrl + V" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_INSERT && it.label == "Insert" })
        assertTrue(state.overlayRows.any { it.id == NovaQuickMenuActionId.PERF_STATS && it.label == "Stats Overlay" })
        assertTrue(state.advancedRows.any { it.id == NovaQuickMenuActionId.MANGOHUD && it.label == "MangoHud" })
        assertTrue(state.sessionRows.any { it.id == NovaQuickMenuActionId.MORE_KEYS && it.label == "More Keys" })
    }

    @Test
    fun commandCenterLabelsPrivateGpuNativeCaptureInsteadOfRawHeadless() {
        val state = quickState(
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    targetDevice = "cuda",
                    targetResidency = "gpu"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    transport = "dmabuf",
                    residency = "gpu"
                ),
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "headless",
                    effectiveHeadless = true
                )
            )
        )

        assertTrue(state.sessionMode.label.contains("Private Stream"))
        assertTrue(state.sessionMode.label.contains("GPU-native DMA-BUF"))
        assertFalse(state.sessionMode.label.contains("Headless"))
    }

    @Test
    fun commandCenterTargetSummaryIncludesAmdVaapiHostCaptureTruth() {
        val state = quickState(
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc",
                    bitrateKbps = 22000,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    targetDevice = "vaapi",
                    targetResidency = "gpu"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    resolution = "1920x1080",
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
                    gpuNativeSucceeded = false
                )
            ),
            fallbackTargetFps = 120.0
        )

        assertTrue(state.stability.targetSummary.contains("HEVC"))
        assertTrue(state.stability.targetSummary.contains("VAAPI + SHM fallback"))
    }

    @Test
    fun commandCenterExposesDiagnoseThisStreamAsPrimaryOverlayAction() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    classification = "NET",
                    likelyCause = "Wi-Fi jitter is the likely bottleneck.",
                    evidence = listOf("3.4% packet loss"),
                    tryFirst = listOf("Lower bitrate"),
                    confidence = "high"
                )
            )
        )

        val diagnose = state.overlayRows.first()
        assertEquals(NovaQuickMenuActionId.DIAGNOSE_STREAM, diagnose.id)
        assertEquals("Diagnose This Stream", diagnose.label)
        assertEquals("Wi-Fi jitter is the likely bottleneck.", diagnose.caption)
        assertEquals("NET", diagnose.chip!!.label)
        assertEquals(NovaQuickMenuTone.WARNING, diagnose.chip.tone)
        assertEquals("Lower bitrate", state.diagnosis.tryFirst)
        assertEquals("3.4% packet loss", state.diagnosis.evidence.first())
        assertEquals("high", state.diagnosis.confidence)
    }

    @Test
    fun commandCenterDisablesDiagnoseThisStreamForMoonlightFallbackSession() {
        val state = quickState(status = null, apiAvailable = false)
        val diagnose = state.overlayRows.first { it.id == NovaQuickMenuActionId.DIAGNOSE_STREAM }

        assertFalse(diagnose.enabled)
        assertEquals("N/A", diagnose.chip!!.label)
        assertEquals("Connect to Polaris for HOST / NET / CLIENT diagnostics.", diagnose.caption)
    }

    @Test
    fun overlayRowsExposePrivacySafeHudDiagnosticCopy() {
        val state = quickState(status = status(), currentGameName = "Portal")
        val diagnostics = state.overlayRows.first { it.id == NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS }

        assertEquals("Copy HUD Diagnostics", diagnostics.label)
        assertEquals("Privacy-safe stream summary for bug reports.", diagnostics.caption)
        assertEquals("Safe", diagnostics.chip!!.label)
        assertEquals(NovaQuickMenuTone.INFO, diagnostics.chip.tone)
    }

    @Test
    fun commandCenterStateExposesHudOpacityPresets() {
        val state = quickState(
            status = status(),
            hudShowing = true,
            hudOpacityPercent = 90
        )

        assertEquals(90, state.hudOpacity.percent)
        assertEquals(listOf(0, 25, 64, 90, 100), state.hudOpacity.presets)
        assertTrue(state.hudOpacity.enabled)
    }

    @Test
    fun commandCenterStateDisablesHudOpacityWhenHudIsOff() {
        val state = quickState(
            status = status(),
            hudShowing = false,
            hudOpacityPercent = 150
        )

        assertFalse(state.hudOpacity.enabled)
        assertEquals(100, state.hudOpacity.percent)
    }

    @Test
    fun commandCenterStateKeepsNonPresetHudOpacityValues() {
        val state = quickState(
            status = status(),
            hudShowing = true,
            hudOpacityPercent = 87
        )

        assertTrue(state.hudOpacity.enabled)
        assertEquals(87, state.hudOpacity.percent)
        assertEquals(NovaHudPreferences.OPACITY_PRESETS, state.hudOpacity.presets)
    }

    @Test
    fun commandCenterStateExposesMenuOpacityIndependentlyFromHud() {
        val state = quickState(
            status = status(),
            hudShowing = false,
            hudOpacityPercent = 25,
            menuOpacityPercent = 64
        )

        assertFalse(state.hudOpacity.enabled)
        assertEquals(64, state.menuOpacity.percent)
        assertEquals(listOf(0, 25, 64, 90, 100), state.menuOpacity.presets)
    }

    @Test
    fun commandCenterStateClampsNonPresetMenuOpacityValues() {
        val state = quickState(status = status(), menuOpacityPercent = 150)

        assertEquals(100, state.menuOpacity.percent)
        assertEquals(NovaMenuPreferences.OPACITY_PRESETS, state.menuOpacity.presets)
    }

    private fun quickState(
        status: PolarisSessionStatus?,
        apiAvailable: Boolean = true,
        adaptiveSupported: Boolean = true,
        aiSupported: Boolean = true,
        adaptiveEnabled: Boolean = false,
        aiEnabled: Boolean = false,
        mangoHudEnabled: Boolean = false,
        stabilityApplied: Boolean = false,
        advancedExpanded: Boolean = true,
        profileClearInProgress: Boolean = false,
        currentGameName: String? = "Portal",
        currentGameUuid: String? = "game-1",
        hudShowing: Boolean = false,
        hudOpacityPercent: Int = 90,
        menuOpacityPercent: Int = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
        fallbackTargetFps: Double = 60.0
    ) = NovaQuickMenuUiState.from(
        context = context,
        status = status,
        apiAvailable = apiAvailable,
        adaptiveSupported = adaptiveSupported,
        aiSupported = aiSupported,
        adaptiveEnabled = adaptiveEnabled,
        aiEnabled = aiEnabled,
        mangoHudEnabled = mangoHudEnabled,
        stabilityApplied = stabilityApplied,
        advancedExpanded = advancedExpanded,
        profileClearInProgress = profileClearInProgress,
        currentGameName = currentGameName,
        currentGameUuid = currentGameUuid,
        profilePreference = "quality",
        hudShowing = hudShowing,
        hudOpacityPercent = hudOpacityPercent,
        menuOpacityPercent = menuOpacityPercent,
        perfOverlayEnabled = false,
        onscreenControllerEnabled = false,
        keyboardVisible = false,
        mouseModeLabel = "Direct",
        allowChangeMouseMode = true,
        isOnExternalDisplay = false,
        fallbackBitrateKbps = 50000,
        fallbackTargetFps = fallbackTargetFps
    )

    private fun status(
        state: String = "streaming",
        clientRole: String = "owner",
        ownedByClient: Boolean = true,
        aiOptimizerEnabled: Boolean = false,
        controls: PolarisSessionStatus.ControlsStatus = PolarisSessionStatus.ControlsStatus(
            hostTuningAllowed = true,
            quitAllowed = true
        ),
        tuning: PolarisSessionStatus.TuningStatus = PolarisSessionStatus.TuningStatus(),
        displayMode: PolarisSessionStatus.DisplayModeStatus = PolarisSessionStatus.DisplayModeStatus(
            effectiveHeadless = true,
            requested = "headless"
        ),
        clientPresentation: PolarisSessionStatus.ClientPresentationStatus = PolarisSessionStatus.ClientPresentationStatus(),
        syncStatus: PolarisSessionStatus.SyncStatus = PolarisSessionStatus.SyncStatus(
            available = true,
            state = "synced"
        ),
        autoQuality: PolarisSessionStatus.AutoQualityPolicy = PolarisSessionStatus.AutoQualityPolicy(),
        health: PolarisSessionStatus.HealthStatus = PolarisSessionStatus.HealthStatus(grade = "good"),
        doctor: PolarisSessionStatus.DoctorStatus = PolarisSessionStatus.DoctorStatus(),
        encoder: PolarisSessionStatus.EncoderStatus = PolarisSessionStatus.EncoderStatus(),
        capture: PolarisSessionStatus.CaptureStatus = PolarisSessionStatus.CaptureStatus(),
        linuxGpuProfile: PolarisSessionStatus.LinuxGpuProfile? = null
    ) = PolarisSessionStatus(
        state = state,
        streamingActive = true,
        game = "Portal",
        gameUuid = "game-1",
        clientRole = clientRole,
        ownedByClient = ownedByClient,
        controls = controls,
        tuning = tuning,
        displayMode = displayMode,
        clientPresentation = clientPresentation,
        syncStatus = syncStatus,
        autoQuality = autoQuality,
        health = health,
        doctor = doctor,
        encoder = encoder,
        capture = capture,
        linuxGpuProfile = linuxGpuProfile,
        aiOptimizerEnabled = aiOptimizerEnabled
    )
}
