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
        assertFalse(state.advancedRows.any { it.id == NovaQuickMenuActionId.AI_AUTO_QUALITY })
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
    fun hostRenderLimitedSessionWarnsWithObservationalPacingCopy() {
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

        assertEquals("Frame-pacing evidence needs a read-only recheck; launch settings are unchanged.", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
        assertEquals("Frame Pacing Watch", state.stability.chip.label)
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
    fun framePacingWarningOverridesStaleStableSummary() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Stable",
                    primaryIssue = "frame_pacing",
                    issues = listOf("frame_pacing")
                )
            )
        )

        assertEquals("Frame pacing", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
        assertFalse(state.healthSummary.contains("Stable", ignoreCase = true))
    }

    @Test
    fun authoritativeDoctorNoneDoesNotShowStaleHealthSummaryBesideSteadyState() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Network jitter was previously observed.",
                    primaryIssue = "network_jitter",
                    issues = listOf("network_jitter")
                ),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-current-none",
                    primaryIssue = "none",
                    likelyCause = "No confirmed issue"
                )
            )
        )

        assertEquals("Session looks steady.", state.healthSummary)
        assertFalse(state.healthSummary.contains("Network", ignoreCase = true))
        assertFalse(state.diagnosis.likelyCause.contains("Network", ignoreCase = true))
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
        assertEquals("N/A", state.advancedRows.first { it.id == NovaQuickMenuActionId.MANGOHUD }.chip!!.label)
        assertFalse(state.advancedRows.any { it.id == NovaQuickMenuActionId.AI_AUTO_QUALITY })
        assertFalse(state.advancedRows.first { it.id == NovaQuickMenuActionId.CLEAR_GAME_PROFILE }.enabled)
    }

    @Test
    fun previewStateExposesCoreActionsForComposeContent() {
        val state = NovaQuickMenuUiState.preview(context).copy(advancedExpanded = true)

        assertEquals("Command Center", state.title)
        assertEquals("Quick keys and controls for Private Stream", state.subtitle)
        assertEquals("Disconnect", state.disconnectAction.label)
        assertEquals("End Session", state.endAction.label)
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_ESC && it.label == "ESC" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_V && it.label == "Ctrl + V" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_INSERT && it.label == "Insert" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_1 && it.label == "Ctrl + 1" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_2 && it.label == "Ctrl + 2" })
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
                    version = 2,
                    resultId = "doctor-v2-needs_action-network_jitter-gpu_native",
                    classification = "NET",
                    likelyCause = "Wi-Fi jitter is the likely bottleneck.",
                    evidence = listOf("3.4% packet loss"),
                    tryFirst = listOf("Lower bitrate"),
                    confidence = "high",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "lower_bitrate",
                    actionSourceResultId = "doctor-v2-needs_action-network_jitter-gpu_native",
                    actionContractTyped = true,
                    targetBitrateKbps = 16000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "fail",
                            source = "media_transport",
                            value = 3.4
                        )
                    ),
                    packetLossPct = 3.4,
                    latencyMs = 12.0
                )
            )
        )

        val diagnose = state.overlayRows.first()
        assertEquals(NovaQuickMenuActionId.DIAGNOSE_STREAM, diagnose.id)
        assertEquals("Auto Fix", diagnose.label)
        assertEquals("Wi-Fi jitter is the likely bottleneck.", diagnose.caption)
        assertEquals("NET", diagnose.chip!!.label)
        assertEquals(NovaQuickMenuTone.WARNING, diagnose.chip.tone)
        assertEquals("Lower bitrate", state.diagnosis.tryFirst)
        assertEquals("3.4% packet loss", state.diagnosis.evidence.first())
        assertEquals("high", state.diagnosis.confidence)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertEquals(16000, state.diagnosis.targetBitrateKbps)
    }

    @Test
    fun deterministicFallbackIsDisplayedAsAnInformationalSource() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    classification = "HOST",
                    likelyCause = "Frame pacing is uneven.",
                    confidence = "deterministic-fallback",
                    primaryIssue = "frame_pacing",
                    explanationSourceKind = "deterministic-fallback",
                    explanationSourceMode = "openai-subscription",
                    explanationInformational = true
                )
            )
        )

        assertEquals("Deterministic fallback · openai-subscription", state.diagnosis.informationalSource)
        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
    }

    @Test
    fun aiExplanationStaysSecondaryToTheDeterministicAction() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-needs_action-network_jitter",
                    classification = "NET",
                    likelyCause = "Confirmed media loss is limiting the stream.",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "lower_bitrate",
                    actionSourceResultId = "doctor-v2-needs_action-network_jitter",
                    actionContractTyped = true,
                    targetBitrateKbps = 16000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    aiExplanation = PolarisSessionStatus.DoctorStatus.AiExplanation(
                        available = true,
                        likelyCause = "Wi-Fi interference is the likely reason.",
                        tryFirst = listOf("Move closer to the access point"),
                        sourceMode = "openai-subscription",
                        informational = true
                    )
                )
            )
        )

        assertEquals("Confirmed media loss is limiting the stream.", state.diagnosis.likelyCause)
        assertEquals("Auto Fix", state.overlayRows.first().label)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertTrue(state.diagnosis.aiExplanation.contains("Wi-Fi interference"))
        assertTrue(state.diagnosis.aiExplanation.contains("Move closer"))
        assertEquals("AI explanation only · openai-subscription", state.diagnosis.informationalSource)
    }

    @Test
    fun commandCenterDisablesDiagnoseThisStreamForMoonlightFallbackSession() {
        val state = quickState(status = null, apiAvailable = false)
        val diagnose = state.overlayRows.first { it.id == NovaQuickMenuActionId.DIAGNOSE_STREAM }

        assertFalse(diagnose.enabled)
        assertEquals("N/A", diagnose.chip!!.label)
        assertEquals("Connect to Polaris for HOST / NET / CLIENT diagnostics.", diagnose.caption)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
    }

    @Test
    fun networkObservationOnlyOffersAReadOnlyRecheck() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-network-observation",
                    classification = "NET",
                    likelyCause = "A network warning needs more live evidence before Doctor changes quality.",
                    primaryIssue = "network_observation",
                    actionId = "recheck_network",
                    actionLabel = "Recheck network",
                    actionCapability = "recheck",
                    actionKind = "verification",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "recheck_network",
                    actionSourceResultId = "doctor-v2-network-observation",
                    actionContractTyped = true,
                    verificationDelaySeconds = 3,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    packetLossPct = 0.4,
                    latencyMs = 20.0
                )
            )
        )

        assertEquals("Recheck network", state.overlayRows.first().label)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.RECHECK, state.diagnosis.capability)
        assertEquals(0, state.diagnosis.targetBitrateKbps)
    }

    @Test
    fun staleNetworkLabelCannotExecuteBitrateReductionWithoutLiveEvidence() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    classification = "NET",
                    likelyCause = "Old network warning",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    targetBitrateKbps = 7580,
                    packetLossPct = 0.0,
                    latencyMs = 3.8
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.overlayRows.first().label)
    }

    @Test
    fun dormantRunTrialCapabilityIsPublishedAsManualUntilAnExecutableContractExists() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-trial-dormant",
                    classification = "HOST",
                    likelyCause = "A one-shot cadence trial might distinguish the cause.",
                    primaryIssue = "frame_pacing",
                    actionId = "run_trial",
                    actionLabel = "Run a trial",
                    actionCapability = "run_trial",
                    actionKind = "fresh_launch_trial"
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.overlayRows.first().label)
    }

    @Test
    fun cleanLiveReductionOffersOneClickQualityRestoreWithinLaunchCeiling() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-needs_action-quality_reduced_live-gpu_native",
                    classification = "HOST",
                    likelyCause = "The reversible live target is below the capability-validated launch ceiling.",
                    primaryIssue = "quality_reduced_live",
                    actionId = "restore_quality",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "restore_quality",
                    actionSourceResultId = "doctor-v2-needs_action-quality_reduced_live-gpu_native",
                    actionContractTyped = true,
                    targetBitrateKbps = 15000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "graduated_live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "effective_quality_ceiling",
                            status = "watch",
                            source = "launch_policy",
                            value = 15000.0
                        ),
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "pass",
                            source = "media_transport",
                            value = 0.0
                        ),
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "latency",
                            status = "pass",
                            source = "stream_stats",
                            value = 3.8
                        )
                    ),
                    packetLossPct = 0.0,
                    latencyMs = 3.8
                )
            )
        )

        assertEquals("Auto Fix", state.overlayRows.first().label)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertEquals(15000, state.diagnosis.targetBitrateKbps)
        assertTrue(state.diagnosis.undoSupported)
    }

    @Test
    fun exactLegacyNextLaunchRecoveryIsPresentedAsNonExecutableManualGuidance() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-watch-frame_pacing-safe-profile",
                    classification = "HOST",
                    likelyCause = "Frame pacing is uneven.",
                    primaryIssue = "frame_pacing",
                    actionId = "apply_recovery_profile_next_launch",
                    actionLabel = "Use safer profile next launch",
                    actionKind = "next_launch_profile",
                    actionAppUuid = "game-1",
                    undoSupported = true,
                    requiresConfirmation = true,
                    ownerTuningAllowed = true,
                    pairedEndpoint = "/polaris/v1/doctor/action",
                    undoPairedEndpoint = "/polaris/v1/doctor/action"
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.overlayRows.first().label)
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
    fun durableDoctorReceiptStaysVisibleWithUndoAfterCommandCenterReopen() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "resolved",
            message = "Doctor verified that network pressure cleared.",
            undoAvailable = true,
            undoActionId = "undo"
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertTrue(state.doctorReceiptAction.enabled)
        assertEquals(NovaQuickMenuActionId.DOCTOR_UNDO, state.doctorReceiptAction.id)
        assertEquals("Verified", state.doctorReceiptAction.chip?.label)
        assertTrue(state.doctorReceiptAction.caption.contains("restore", ignoreCase = true))
    }

    @Test
    fun unconfirmedDoctorRollbackStaysVisibleAsNeedsAttention() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "rollback_unconfirmed",
            message = "The encoder did not confirm that the prior bitrate was restored.",
            undoAvailable = false,
            undoActionId = ""
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertFalse(state.doctorReceiptAction.enabled)
        assertEquals("Needs attention", state.doctorReceiptAction.chip?.label)
        assertEquals(NovaQuickMenuTone.WARNING, state.doctorReceiptAction.chip?.tone)
        assertTrue(state.doctorReceiptAction.caption.contains("did not confirm"))
    }

    @Test
    fun recoveryUndoCopyPromisesOnlyQueuedProfileRemoval() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "recovery-run-1",
            state = "queued",
            message = "Safer profile queued.",
            undoAvailable = true,
            undoActionId = "undo_recovery_profile_next_launch",
            appUuid = "game-1"
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.caption.contains("removes only this deprecated record"))
        assertTrue(state.doctorReceiptAction.caption.contains("stream and launch settings remain unchanged"))
        assertFalse(state.doctorReceiptAction.caption.contains("restore the previous bitrate", ignoreCase = true))
    }

    @Test
    fun pairedOwnerCanCancelLegacyRecoveryWithoutOwningTheActiveStream() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "recovery-run-1",
            state = "queued",
            message = "Deprecated profile queued.",
            undoAvailable = true,
            undoActionId = "undo",
            appUuid = "game-1"
        )
        val viewerStatus = status(
            clientRole = "viewer",
            ownedByClient = false,
            controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false)
        )

        val state = quickState(status = viewerStatus, doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertTrue(state.doctorReceiptAction.enabled)
    }

    @Test
    fun durableDoctorUndoRequiresHostActionIdAndCurrentTuningPermission() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "resolved",
            message = "Verified",
            undoAvailable = true,
            undoActionId = ""
        )

        val missingAction = quickState(status = status(), doctorReceipt = receipt)
        val viewer = quickState(
            status = status(
                clientRole = "viewer",
                ownedByClient = false,
                controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false)
            ),
            doctorReceipt = receipt.copy(undoActionId = "restore_quality")
        )

        assertTrue(missingAction.doctorReceiptAction.visible)
        assertFalse(missingAction.doctorReceiptAction.enabled)
        assertTrue(viewer.doctorReceiptAction.visible)
        assertFalse(viewer.doctorReceiptAction.enabled)
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
        fallbackTargetFps: Double = 60.0,
        doctorReceipt: DoctorActionReceipt? = null
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
        fallbackTargetFps = fallbackTargetFps,
        doctorReceipt = doctorReceipt
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
    ): PolarisSessionStatus {
        val scopedActionIds = setOf(
            "lower_bitrate",
            "restore_quality",
            "recheck_network",
            "recheck_pacing"
        )
        val scopedDoctor = if (doctor.actionId in scopedActionIds) {
            doctor.copy(
                actionAppSessionId = doctor.actionAppSessionId.ifBlank { "app-session-1" },
                actionSessionGeneration = doctor.actionSessionGeneration.takeIf { it > 0L } ?: 41L,
                actionControllerRevision = if (doctor.actionCapability == "auto_fix") {
                    doctor.actionControllerRevision.takeIf { it > 0L } ?: 51L
                } else {
                    doctor.actionControllerRevision
                },
                actionEvidenceRevision = if (doctor.actionCapability == "auto_fix") {
                    doctor.actionEvidenceRevision.takeIf { it > 0L } ?: 61L
                } else {
                    doctor.actionEvidenceRevision
                }
            )
        } else {
            doctor
        }
        return PolarisSessionStatus(
            state = state,
            streamingActive = true,
            game = "Portal",
            gameUuid = "game-1",
            appSessionId = "app-session-1",
            appSessionIdPresent = true,
            sessionGeneration = 41L,
            clientRole = clientRole,
            ownedByClient = ownedByClient,
            controls = controls,
            tuning = tuning,
            displayMode = displayMode,
            clientPresentation = clientPresentation,
            syncStatus = syncStatus,
            autoQuality = autoQuality,
            health = health,
            doctor = scopedDoctor,
            encoder = encoder,
            capture = capture,
            linuxGpuProfile = linuxGpuProfile,
            aiOptimizerEnabled = aiOptimizerEnabled
        )
    }
}
