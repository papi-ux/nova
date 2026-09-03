package com.papi.nova.api

import java.util.Locale

data class PolarisSessionStatus(
    val state: String,
    val streamingActive: Boolean = false,
    val shutdownRequested: Boolean = false,
    val game: String = "",
    val gameId: Int = 0,
    val gameUuid: String = "",
    val sessionToken: String = "",
    val appSessionId: String = "",
    val appSessionIdPresent: Boolean = false,
    val sessionGeneration: Long = 0L,
    val ownerUniqueId: String = "",
    val ownerDeviceName: String = "",
    val clientRole: String = "none",
    val viewerCount: Int = 0,
    val ownedByClient: Boolean = false,
    val authorityContractValid: Boolean = true,
    val cagePid: Int = 0,
    val screenLocked: Boolean = false,
    val cursorVisible: Boolean = false,
    val dynamicRange: Int = 0,
    val adaptiveBitrateEnabled: Boolean = false,
    val adaptiveTargetBitrateKbps: Int = 0,
    val aiAutoQualityEnabled: Boolean = false,
    val aiOptimizerEnabled: Boolean = false,
    val mangohudConfigured: Boolean = false,
    val controls: ControlsStatus = ControlsStatus(),
    val tuning: TuningStatus = TuningStatus(),
    val displayMode: DisplayModeStatus = DisplayModeStatus(),
    val presentationPolicy: PresentationPolicy = PresentationPolicy(),
    val clientPresentation: ClientPresentationStatus = ClientPresentationStatus(),
    val syncStatus: SyncStatus = SyncStatus(),
    val capture: CaptureStatus = CaptureStatus(),
    val encoder: EncoderStatus = EncoderStatus(),
    val linuxGpuProfile: LinuxGpuProfile? = null,
    val autoQuality: AutoQualityPolicy = AutoQualityPolicy(),
    val profileState: ProfileState = ProfileState(),
    val health: HealthStatus = HealthStatus(),
    val doctor: DoctorStatus = DoctorStatus(),
    val recovery: RecoveryReceipt = RecoveryReceipt(),
    val recoveryRecords: List<RecoveryReceipt> = emptyList()
) {
    data class RecoveryReceipt(
        val status: Boolean = true,
        val state: String = "none",
        val runId: String = "",
        val sourceResultId: String = "",
        val appUuid: String = "",
        val expiresAt: Long = 0L,
        val message: String = "",
        val error: String = "",
        val safeProfile: RecoverySafeProfile = RecoverySafeProfile(),
        val undoSupported: Boolean = false,
        val undoAvailable: Boolean = false,
        val undoActionId: String = "",
        val verificationActionId: String = "",
        val deprecated: Boolean = false,
        val applicable: Boolean = true,
        val cancellable: Boolean = false,
        val reasonCode: String = ""
    ) {
        val normalizedState get() = state.trim().lowercase()
        val isQueued get() = normalizedState == "queued"
        val isTerminal get() = normalizedState in setOf("applied", "expired", "rejected", "undone")
    }

    data class RecoverySafeProfile(
        val streamDisplayMode: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val targetFps: Float = 0f,
        val targetBitrateKbps: Int = 0,
        val preferredCodec: String = "",
        val hdr: Boolean = false,
        val preservePairedResolution: Boolean = false,
        val requiresFreshLaunch: Boolean = false
    )

    data class ControlsStatus(
        val hostTuningAllowed: Boolean? = null,
        val quitAllowed: Boolean = false,
        val shutdownInProgress: Boolean = false,
        val clientCommandsEnabled: Boolean = false,
        val deviceCommandsEnabled: Boolean = false
    )

    data class TuningStatus(
        val adaptiveBitrateEnabled: Boolean = false,
        val adaptiveTargetBitrateKbps: Int = 0,
        val adaptiveBaseBitrateKbps: Int = 0,
        val adaptiveMinBitrateKbps: Int = 0,
        val adaptiveMaxBitrateKbps: Int = 0,
        val adaptiveBitrateState: String = "",
        val adaptiveBitrateReason: String = "",
        val aiAutoQualityEnabled: Boolean = false,
        val aiOptimizerEnabled: Boolean = false,
        val mangohudConfigured: Boolean = false
    )

    data class DisplayModeStatus(
        val label: String = "",
        val selection: String = "",
        val requested: String = "",
        val explicitChoice: Boolean = false,
        val virtualDisplay: Boolean = false,
        val requestedHeadless: Boolean = false,
        val effectiveHeadless: Boolean = false,
        val gpuNativeOverrideActive: Boolean = false,
        val mirrorDesktop: Boolean = false,
        val forcePrivateAfterSteamClose: Boolean = false,
        /** Why this session is not on the display the client asked for; blank when it is. */
        val warning: String = ""
    )

    data class PresentationPolicy(
        val version: Int = 0,
        val targetRefreshRateHz: Double = 0.0,
        val refreshRatePolicy: String = "",
        val allowDisplayModeChange: Boolean = false,
        val internalDisplayOnly: Boolean = true,
        val reason: String = ""
    )

    data class ClientPresentationStatus(
        val status: String = "",
        val appliedRefreshRateHz: Double = 0.0,
        val targetRefreshRateHz: Double = 0.0,
        val refreshRatePolicy: String = "",
        val displayMode: String = "",
        val decoder: String = "",
        val framePacingState: String = "",
        val reason: String = ""
    )

    data class SyncValues(
        val streamDisplayMode: String = "",
        val displayMode: String = "",
        val targetBitrateKbps: Int = 0,
        val adaptiveTargetBitrateKbps: Int = 0,
        val adaptiveBitrateEnabled: Boolean = false,
        val aiOptimizerEnabled: Boolean = false,
        val preferredCodec: String = "",
        val hdr: Boolean? = null
    )

    data class SyncStatus(
        val available: Boolean = false,
        val version: Int = 0,
        val state: String = "",
        val legacyState: String = "",
        val message: String = "",
        val sourceOfTruth: String = "",
        val syncMode: String = "",
        val manualOverride: Boolean = false,
        val desired: SyncValues = SyncValues(),
        val effective: SyncValues = SyncValues(),
        val applied: SyncValues = SyncValues()
    ) {
        val isSynced get() = state.equals("synced", ignoreCase = true)
        val isApplying get() = state.equals("applying", ignoreCase = true)
        val needsRelaunch get() = state.equals("needs_relaunch", ignoreCase = true)
        val isManualOverride get() = state.equals("manual_override", ignoreCase = true) || manualOverride
        val isFailed get() = state.equals("failed", ignoreCase = true)
        val label get() = when {
            isManualOverride -> "Manual"
            needsRelaunch -> "Relaunch"
            isFailed -> "Attention"
            isApplying -> "Applying"
            isSynced -> "Synced"
            available -> "Ready"
            else -> "Unavailable"
        }
    }

    data class CaptureStatus(
        val backend: String = "",
        val resolution: String = "",
        val path: String = "",
        val reason: String = "",
        val transport: String = "",
        val residency: String = "",
        val format: String = ""
    )

    data class EncoderStatus(
        val codec: String = "",
        val bitrateKbps: Int = 0,
        val fps: Double = 0.0,
        val requestedClientFps: Double = 0.0,
        val sessionTargetFps: Double = 0.0,
        val encodeTargetFps: Double = 0.0,
        val pacingPolicy: String = "",
        val optimizationSource: String = "",
        val optimizationConfidence: String = "",
        val optimizationCacheStatus: String = "",
        val optimizationReasoning: String = "",
        val optimizationNormalizationReason: String = "",
        val recommendationVersion: Int = 0,
        val targetDevice: String = "",
        val targetResidency: String = "",
        val targetFormat: String = "",
        val activeBackend: String = "",
        val selection: EncoderSelectionStatus = EncoderSelectionStatus()
    )

    /** Informational provenance for Polaris' deterministic host-side encoder choice. */
    data class EncoderSelectionStatus(
        val mode: String = "",
        val gpuDriver: String = "",
        val policy: String = "",
        val preferredEncoder: String = "",
        val fallbackEncoder: String = "",
        val selectedEncoder: String = "",
        val exactLiveProbeRequired: Boolean = false,
        val fallbackUsed: Boolean = false,
        val reason: String = ""
    )

    data class LinuxGpuProfile(
        val encoderApi: String = "",
        val encoderAdapter: String = "",
        val captureDevice: String = "",
        val adapterMatchesCaptureDevice: Boolean = true,
        val crossGpuDmabufRisk: Boolean = false,
        val gpuNativeRequested: Boolean = false,
        val gpuNativeAttempted: Boolean = false,
        val gpuNativeSucceeded: Boolean = false,
        val vaapiVendor: String = ""
    )

    data class AutoQualityPolicy(
        val enabled: Boolean = false,
        val state: String = "",
        val blockedReason: String = "none",
        val liveBitrateKbps: Int = 0,
        val qualityCapKbps: Int = 0,
        val adaptiveBitrateActive: Boolean = false,
        val optimizerActive: Boolean = false,
        val adaptiveState: String = "",
        val adaptiveReason: String = "",
        val relaunchRequired: Boolean = false,
        val canRecoverLive: Boolean = false,
        val summary: String = "",
        val detail: String = "",
        val suggestedTargetFps: Double = 0.0,
        val suggestedBitrateKbps: Int = 0,
        val suggestedCodec: String = "",
        val suggestedDisplayMode: String = "",
        val suggestedHdr: Boolean? = null
    ) {
        val normalizedState get() = state.lowercase()
        val normalizedBlockedReason get() = blockedReason.lowercase()
        val isOff get() = !enabled || normalizedState == "off"
        val isBlocked get() = normalizedState == "blocked"
        val isRecoveringBitrate get() = normalizedState == "recovering_bitrate"
        val isRecoveryQueued get() = normalizedState == "recovery_queued"
        val isUpgradeAvailable get() = normalizedState == "upgrade_available"
        val isAtQualityCap get() = qualityCapKbps > 0 &&
            liveBitrateKbps > 0 &&
            liveBitrateKbps >= qualityCapKbps
    }

    data class ProfileState(
        val state: String = "",
        val label: String = "",
        val reason: String = "",
        val source: String = "",
        val cacheStatus: String = "",
        val confidence: String = "",
        val preference: String = "auto",
        val preferenceLabel: String = "Auto",
        val preferenceApplied: Boolean = true,
        val preferenceNote: String = "",
        val currentProfile: ProfileValues = ProfileValues(),
        val lastResult: LastResult = LastResult(),
        val actions: Actions = Actions()
    ) {
        data class ProfileValues(
            val displayMode: String = "",
            val targetBitrateKbps: Int = 0,
            val targetFps: Double = 0.0,
            val preferredCodec: String = "",
            val hdr: Boolean? = null
        )

        data class LastResult(
            val grade: String = "",
            val sessionCount: Int = 0,
            val deliveredFps: Double = 0.0,
            val targetFps: Double = 0.0,
            val lowOnePercentFps: Double = 0.0,
            val minFps: Double = 0.0,
            val framePacingBadPct: Double = 0.0,
            val primaryIssue: String = "",
            val sampleConfidence: String = "",
            val updatedAt: Long = 0L
        )

        data class Actions(
            val canReset: Boolean = false,
            val canRetryQuality: Boolean = false,
            val canKeepRecovery: Boolean = false,
            val canChangePreference: Boolean = true
        )

        val isRecovering get() = state.equals("recovering", ignoreCase = true)
        val isUpgradeAvailable get() = state.equals("upgrade_available", ignoreCase = true)
        val isLearning get() = state.equals("learning", ignoreCase = true)
        val isManualOverride get() = state.equals("manual_override", ignoreCase = true)
    }

    data class DoctorStatus(
        val available: Boolean = false,
        val version: Int = 0,
        val resultId: String = "",
        val status: String = "",
        val severity: String = "",
        val trafficLight: String = "",
        val classification: String = "UNKNOWN",
        val likelyCause: String = "",
        val evidence: List<String> = emptyList(),
        val evidenceItems: List<EvidenceItem> = emptyList(),
        val tryFirst: List<String> = emptyList(),
        val confidence: String = "",
        val advancedDetail: String = "",
        val primaryIssue: String = "",
        val actionId: String = "",
        val actionLabel: String = "",
        val actionCapability: String = "",
        val actionKind: String = "",
        val actionEndpoint: String = "",
        val actionMethod: String = "",
        val actionPayloadId: String = "",
        val actionSourceResultId: String = "",
        val actionContractTyped: Boolean = false,
        val actionAppUuid: String = "",
        val actionAppSessionId: String = "",
        val actionSessionGeneration: Long = 0L,
        val actionControllerRevision: Long = 0L,
        val actionEvidenceRevision: Long = 0L,
        val targetBitrateKbps: Int = 0,
        val targetBitratePresent: Boolean = false,
        val targetBitrateTyped: Boolean = false,
        val verificationDelaySeconds: Int = 0,
        val undoSupported: Boolean = false,
        val undoEndpoint: String = "",
        val requiresConfirmation: Boolean = false,
        val requiresOwner: Boolean = false,
        val allowedInViewerMode: Boolean = false,
        val destructive: Boolean = false,
        val ownerTuningAllowed: Boolean = false,
        val pairedEndpoint: String = "",
        val undoPairedEndpoint: String = "",
        val verificationMode: String = "",
        val verificationEndpoint: String = "",
        val packetLossPct: Double? = null,
        val latencyMs: Double? = null,
        val destructiveActionAllowed: Boolean = false,
        val explanationSourceKind: String = "",
        val explanationSourceMode: String = "",
        val explanationInformational: Boolean = false,
        val aiExplanation: AiExplanation = AiExplanation()
    ) {
        data class EvidenceItem(
            val id: String = "",
            val status: String = "",
            val source: String = "",
            val value: Double? = null,
            val detail: String = ""
        )

        data class AiExplanation(
            val available: Boolean = false,
            val likelyCause: String = "",
            val evidence: List<String> = emptyList(),
            val tryFirst: List<String> = emptyList(),
            val confidence: String = "",
            val advancedDetail: String = "",
            val sourceKind: String = "",
            val sourceMode: String = "",
            val informational: Boolean = false
        )

        val firstTry get() = tryFirst.firstOrNull().orEmpty()
        private fun evidenceItem(id: String) = evidenceItems.firstOrNull { it.id == id }
        private fun evidenceStatusIs(item: EvidenceItem?, status: String) =
            item?.status?.equals(status, ignoreCase = true) == true
        private fun evidenceSourceIs(item: EvidenceItem?, expected: String) =
            item?.source?.equals(expected, ignoreCase = true) == true
        private val actionEnvelopeValid get() =
            version >= 2 &&
                actionContractTyped &&
                resultId.isNotBlank() &&
                actionPayloadId == actionId &&
                actionSourceResultId == resultId &&
                actionEndpoint == "/api/doctor/action" &&
                actionMethod.equals("POST", ignoreCase = true) &&
                actionAppSessionId.isNotBlank() &&
                actionSessionGeneration > 0L &&
                requiresOwner &&
                !allowedInViewerMode &&
                !destructive &&
                !requiresConfirmation &&
                !ownerTuningAllowed &&
                pairedEndpoint.isEmpty()
        private val verificationEnvelopeValid get() =
            verificationEndpoint == "/api/doctor/action"
        private val confirmedMediaLoss get() = evidenceItem("packet_loss").let { item ->
            evidenceSourceIs(item, "media_transport") &&
                evidenceStatusIs(item, "fail") && (item?.value ?: 0.0) > 2.0
        }
        private val confirmedRttPressure get() = evidenceItem("latency").let { item ->
            evidenceSourceIs(item, "stream_stats") &&
                evidenceStatusIs(item, "fail") && (item?.value ?: 0.0) >= 45.0
        }
        private val cleanRtt get() = evidenceItem("latency").let { item ->
            evidenceSourceIs(item, "stream_stats") && evidenceStatusIs(item, "pass") &&
                (item?.value ?: Double.POSITIVE_INFINITY) < 45.0
        }
        private val lossEvidenceAllowsQualityRetry get() = evidenceItem("packet_loss").let { item ->
            (evidenceSourceIs(item, "media_transport") && evidenceStatusIs(item, "pass") &&
                (item?.value ?: Double.POSITIVE_INFINITY) <= 2.0) ||
                (evidenceSourceIs(item, "unavailable") && evidenceStatusIs(item, "unknown") &&
                    item?.value == null)
        }
        val networkPressureConfirmed get() = confirmedMediaLoss || confirmedRttPressure
        val canExecuteAction get() = when (actionId) {
            "recheck_network", "recheck_pacing" ->
                actionEnvelopeValid &&
                    actionCapability == "recheck" &&
                    actionKind == "verification" &&
                    !undoSupported &&
                    !targetBitratePresent &&
                    verificationEnvelopeValid &&
                    verificationMode == "live_telemetry"
            "lower_bitrate" ->
                actionEnvelopeValid &&
                    actionCapability == "auto_fix" &&
                    actionKind == "live_tuning" &&
                    primaryIssue == "network_jitter" &&
                    targetBitrateTyped &&
                    targetBitrateKbps in 1_000..300_000 &&
                    undoSupported &&
                    undoEndpoint == "/api/doctor/action" &&
                    undoPairedEndpoint.isEmpty() &&
                    verificationDelaySeconds >= 8 &&
                    verificationEnvelopeValid &&
                    verificationMode == "live_telemetry" &&
                    actionControllerRevision > 0L &&
                    actionEvidenceRevision > 0L &&
                    networkPressureConfirmed
            "restore_quality" -> {
                val ceiling = evidenceItem("effective_quality_ceiling")
                actionEnvelopeValid &&
                    actionCapability == "auto_fix" &&
                    actionKind == "live_tuning" &&
                    primaryIssue == "quality_reduced_live" &&
                    targetBitrateTyped &&
                    targetBitrateKbps in 1_000..300_000 &&
                    ceiling?.source == "launch_policy" &&
                    evidenceStatusIs(ceiling, "watch") &&
                    ceiling?.value?.toInt() == targetBitrateKbps &&
                    cleanRtt &&
                    lossEvidenceAllowsQualityRetry &&
                    undoSupported &&
                    undoEndpoint == "/api/doctor/action" &&
                    undoPairedEndpoint.isEmpty() &&
                    verificationDelaySeconds >= 8 &&
                    verificationEnvelopeValid &&
                    verificationMode == "graduated_live_telemetry"
                    && actionControllerRevision > 0L
                    && actionEvidenceRevision > 0L
            }
            else -> false
        }

        /**
         * True when this doctor payload is the same actionable pair the user just confirmed.
         * Guards against a session refresh swapping the safe action out from under a confirmation
         * dialog before the positive button is pressed.
         */
        fun matchesConfirmedAction(confirmed: DoctorStatus): Boolean =
            actionId.isNotBlank() &&
                resultId.isNotBlank() &&
                actionId == confirmed.actionId &&
                resultId == confirmed.resultId &&
                canExecuteAction &&
                confirmed.canExecuteAction &&
                actionCapability == confirmed.actionCapability &&
                actionKind == confirmed.actionKind &&
                actionEndpoint == confirmed.actionEndpoint &&
                actionMethod == confirmed.actionMethod &&
                actionPayloadId == confirmed.actionPayloadId &&
                actionSourceResultId == confirmed.actionSourceResultId &&
                actionContractTyped == confirmed.actionContractTyped &&
                actionAppUuid == confirmed.actionAppUuid &&
                actionAppSessionId == confirmed.actionAppSessionId &&
                actionSessionGeneration == confirmed.actionSessionGeneration &&
                actionControllerRevision == confirmed.actionControllerRevision &&
                actionEvidenceRevision == confirmed.actionEvidenceRevision &&
                targetBitrateKbps == confirmed.targetBitrateKbps &&
                targetBitratePresent == confirmed.targetBitratePresent &&
                targetBitrateTyped == confirmed.targetBitrateTyped &&
                verificationDelaySeconds == confirmed.verificationDelaySeconds &&
                verificationMode == confirmed.verificationMode &&
                verificationEndpoint == confirmed.verificationEndpoint &&
                undoSupported == confirmed.undoSupported &&
                undoEndpoint == confirmed.undoEndpoint &&
                requiresConfirmation == confirmed.requiresConfirmation &&
                requiresOwner == confirmed.requiresOwner &&
                allowedInViewerMode == confirmed.allowedInViewerMode &&
                destructive == confirmed.destructive &&
                ownerTuningAllowed == confirmed.ownerTuningAllowed

        /**
         * True when both payloads describe the same user-selected action and stream scope.
         *
         * Routine telemetry is allowed to replace the result, controller/evidence revisions,
         * and deterministic target between rendering the Doctor card and pressing it. Nova must
         * execute the latest host-authored payload in that case. A changed action type, wire
         * contract, app, or stream generation still requires another explicit press.
         */
        fun matchesExecutableActionIntent(displayed: DoctorStatus): Boolean =
            actionId.isNotBlank() &&
                canExecuteAction &&
                displayed.canExecuteAction &&
                actionId == displayed.actionId &&
                actionCapability == displayed.actionCapability &&
                actionKind == displayed.actionKind &&
                actionEndpoint == displayed.actionEndpoint &&
                actionMethod.equals(displayed.actionMethod, ignoreCase = true) &&
                actionPayloadId == displayed.actionPayloadId &&
                actionAppUuid == displayed.actionAppUuid &&
                actionAppSessionId == displayed.actionAppSessionId &&
                actionSessionGeneration == displayed.actionSessionGeneration &&
                verificationMode == displayed.verificationMode &&
                verificationEndpoint == displayed.verificationEndpoint &&
                undoEndpoint == displayed.undoEndpoint
    }

    data class HealthStatus(
        val autoMode: Boolean = false,
        val limitingFactor: String = "",
        val autoAction: String = "",
        val grade: String = "",
        val summary: String = "",
        val primaryIssue: String = "",
        val issues: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
        val safeBitrateKbps: Int = 0,
        val safeCodec: String = "",
        val safeDisplayMode: String = "",
        val safeTargetFps: Double = 0.0,
        val safeHdr: Boolean? = null,
        val decoderRisk: String = "",
        val hdrRisk: String = "",
        val hdrEffectiveMode: String = "",
        val hdrDowngradeReason: String = "",
        val hdrDowngradeMessage: String = "",
        val hdrSource: String = "",
        val networkRisk: String = "",
        val hostRenderLimited: Boolean = false,
        val renderFpsGap: Double = 0.0,
        val recoveryProfile: String = "",
        val relaunchRecommended: Boolean = false
    )

    private val normalizedState get() = state.lowercase()
    val isStreaming get() = normalizedState == "streaming" || streamingActive
    val isPausedForResume get() = normalizedState == "paused"
    val isSessionAlive get() = normalizedState in listOf("initializing", "cage_starting", "game_launching", "streaming", "paused")
    val isShuttingDown get() = shutdownRequested || normalizedState == "tearing_down"
    val isResumable get() = !isShuttingDown && gameId > 0 && (isSessionAlive || isPausedForResume)
    val isTenBitActive get() = dynamicRange > 0 || encoder.targetFormat.equals("p010", ignoreCase = true)
    val isGpuPath get() = encoder.targetResidency.equals("gpu", ignoreCase = true) ||
        (encoder.targetResidency.isBlank() && encoder.targetDevice.isCudaGpuTarget)
    val isGpuNativeCapture get() =
        capture.transport.contains("dmabuf", ignoreCase = true) &&
            (capture.residency.equals("gpu", ignoreCase = true) || isGpuPath) ||
            capture.path.contains("dmabuf", ignoreCase = true) &&
            (capture.residency.equals("gpu", ignoreCase = true) || isGpuPath) ||
            linuxGpuProfile?.gpuNativeSucceeded == true
    val capturePathLabel: String
        get() = when {
            isGpuNativeCapture -> "GPU-native DMA-BUF"
            capture.transport.contains("shm", ignoreCase = true) ||
                capture.residency.equals("cpu", ignoreCase = true) ||
                capture.reason.contains("shm", ignoreCase = true) -> "SHM/CPU capture"
            isGpuPath -> "GPU encoder path"
            else -> ""
        }
    val hostCaptureTruthLabel: String
        get() {
            val profile = linuxGpuProfile ?: return ""
            val encoderApi = profile.encoderApi.ifBlank { encoder.targetDevice }
            if (!encoderApi.equals("vaapi", ignoreCase = true)) {
                return ""
            }
            if (!profile.adapterMatchesCaptureDevice || profile.crossGpuDmabufRisk) {
                return "VAAPI render-node mismatch"
            }
            val captureTransport = capture.transport.lowercase().ifBlank { capture.path.lowercase() }
            val captureResidency = capture.residency.lowercase()
            val captureReason = capture.reason.lowercase()
            val shmFallback = captureTransport.contains("shm") ||
                captureResidency == "cpu" ||
                captureReason.contains("shm_fallback")
            return when {
                profile.gpuNativeSucceeded || (captureTransport.contains("dmabuf") && captureResidency == "gpu") ->
                    "VAAPI + GPU-native"
                shmFallback -> "VAAPI + SHM fallback"
                else -> "VAAPI host"
            }
        }
    val isHeadlessMode get() = displayMode.effectiveHeadless
    val isVirtualDisplayMode get() = displayMode.virtualDisplay
    val sessionModeLabel get() = when {
        displayMode.selection.equals("windowed_stream", ignoreCase = true) ||
            displayMode.requested.equals("windowed_stream", ignoreCase = true) -> "Private Stream (GPU-native)"
        displayMode.selection.equals("desktop_display", ignoreCase = true) ||
            displayMode.requested.equals("desktop_display", ignoreCase = true) -> "Mirror Desktop"
        displayMode.selection.equals("desktop_takeover", ignoreCase = true) ||
            displayMode.requested.equals("desktop_takeover", ignoreCase = true) -> "Desktop Takeover"
        displayMode.label.isNotBlank() -> normalizeSessionModeLabel(displayMode.label)
        displayMode.effectiveHeadless -> "Private Stream"
        displayMode.virtualDisplay -> "Host Virtual Display"
        else -> "Mirror Desktop"
    }
    val sessionModeWithCaptureLabel: String
        get() = listOf(sessionModeLabel, capturePathLabel).filter { it.isNotBlank() }.joinToString(" · ")
    val encoderSelectionLabel: String
        get() {
            val selected = encoderDisplayName(
                encoder.selection.selectedEncoder.takeUnless { it.isUnknownEncoderName }
                    ?: encoder.activeBackend.takeUnless { it.isUnknownEncoderName }
                    ?: ""
            )
            if (selected.isBlank()) return ""

            val preferred = encoderDisplayName(
                encoder.selection.preferredEncoder.takeUnless { it.isUnknownEncoderName } ?: ""
            )
            return when {
                encoder.selection.fallbackUsed && preferred.isNotBlank() && preferred != selected ->
                    "$preferred → $selected fallback"
                encoder.selection.mode.equals("auto", ignoreCase = true) -> "Auto → $selected"
                else -> selected
            }
        }
    val isViewer get() = clientRole.equals("viewer", ignoreCase = true)
    val hasExplicitDisplayModeChoice get() = displayMode.explicitChoice
    val canAdjustHostTuning get() = authorityContractValid &&
        (controls.hostTuningAllowed ?: (ownedByClient && !isViewer))
    val canQuit get() = authorityContractValid &&
        (controls.quitAllowed || (ownedByClient && !isViewer))

    private fun normalizeSessionModeLabel(label: String): String = when (label.trim().lowercase()) {
        "headless", "headless stream", "private headless stream", "private stream" -> "Private Stream"
        "gpu-native stream", "gpu-native test", "windowed stream", "private stream (gpu-native)" -> "Private Stream (GPU-native)"
        "desktop display", "host display", "desktop", "mirror desktop" -> "Mirror Desktop"
        "virtual display", "host virtual display" -> "Host Virtual Display"
        else -> label
    }

    private fun encoderDisplayName(name: String): String = when (name.trim().lowercase()) {
        "vulkan" -> "Vulkan"
        "nvenc" -> "NVENC"
        "vaapi" -> "VAAPI"
        "software" -> "Software"
        else -> name.trim().uppercase(Locale.US)
    }

    private val String.isUnknownEncoderName: Boolean
        get() = isBlank() || equals("unknown", ignoreCase = true)

    private val String.isCudaGpuTarget: Boolean
        get() = equals("cuda", ignoreCase = true) ||
            equals("gpu", ignoreCase = true) ||
            equals("nvidia", ignoreCase = true)
    private val String.isActiveHdrDowngradeReason: Boolean
        get() = isNotBlank() && !equals("none", ignoreCase = true)
    val isClientPresentationSynced get() = clientPresentation.status.equals("synced", ignoreCase = true)
    val hasOptimizerSync get() = syncStatus.available
    val optimizationSourceLabel get() = when {
        encoder.optimizationSource.equals("ai_live", ignoreCase = true) &&
            encoder.optimizationCacheStatus.equals("invalidated", ignoreCase = true) -> "Recovery tune"
        encoder.optimizationSource.equals("ai_live", ignoreCase = true) -> "AI tune"
        encoder.optimizationSource.equals("ai_cached", ignoreCase = true) -> "Cached AI"
        encoder.optimizationSource.equals("device_db", ignoreCase = true) -> "Baseline device tune"
        else -> ""
    }
    val optimizationBadgeLabel get() = when {
        encoder.optimizationSource.equals("ai_live", ignoreCase = true) &&
            encoder.optimizationCacheStatus.equals("invalidated", ignoreCase = true) -> "Recovery"
        encoder.optimizationSource.equals("ai_live", ignoreCase = true) -> "AI"
        encoder.optimizationSource.equals("ai_cached", ignoreCase = true) -> "Cached AI"
        encoder.optimizationSource.equals("device_db", ignoreCase = true) -> "Baseline"
        else -> ""
    }
    val hasOptimizationNormalization get() = encoder.optimizationNormalizationReason.isNotBlank()
    val optimizationNormalizedLabel get() = if (hasOptimizationNormalization) "Host adjusted" else ""
    val optimizationConfidenceLabel get() = encoder.optimizationConfidence.uppercase()
    val hasAuthoritativeDoctorResult get() =
        doctor.available && doctor.version >= 2 && doctor.resultId.isNotBlank()
    val hasExplicitAuthoritativeDoctorVerdict get() =
        hasAuthoritativeDoctorResult &&
            doctor.status.isNotBlank() &&
            doctor.severity.isNotBlank() &&
            doctor.trafficLight.isNotBlank()
    private val hasAnyAuthoritativeDoctorVerdictField get() =
        hasAuthoritativeDoctorResult &&
            (doctor.status.isNotBlank() ||
                doctor.severity.isNotBlank() ||
                doctor.trafficLight.isNotBlank())
    val authoritativeDoctorVerdictIsHealthy get() =
        hasExplicitAuthoritativeDoctorVerdict &&
            doctor.status.equals("ok", ignoreCase = true) &&
            doctor.severity.equals("info", ignoreCase = true) &&
            doctor.trafficLight.equals("green", ignoreCase = true)
    val authoritativeDoctorVerdictNeedsAttention get() =
        hasAnyAuthoritativeDoctorVerdictField && !authoritativeDoctorVerdictIsHealthy
    val effectivePrimaryIssue get() = if (hasAuthoritativeDoctorResult) {
        doctor.primaryIssue.ifBlank { "none" }
    } else {
        health.primaryIssue
    }
    val isHostRenderLimited get() = if (hasAuthoritativeDoctorResult) {
        doctor.primaryIssue.equals("host_render_limited", ignoreCase = true)
    } else {
        health.hostRenderLimited ||
            health.primaryIssue.equals("host_render_limited", ignoreCase = true) ||
            health.issues.any { it.equals("host_render_limited", ignoreCase = true) }
    }
    val isHdrDowngraded get() =
        health.primaryIssue.equals("hdr_downgraded", ignoreCase = true) ||
            health.issues.any { it.equals("hdr_downgraded", ignoreCase = true) } ||
            health.hdrDowngradeReason.isActiveHdrDowngradeReason
    val isHeadlessHdrUnavailable get() =
        health.hdrDowngradeReason.equals("headless_hdr_unavailable", ignoreCase = true) ||
            (isHdrDowngraded && isHeadlessMode)
    private val doctorPrimaryIsObservation get() =
        doctor.primaryIssue.lowercase() in setOf("network_observation", "control_channel_observation")
    fun doctorEvidenceIsActionable(item: DoctorStatus.EvidenceItem): Boolean {
        val evidenceStatus = item.status.lowercase()
        if (evidenceStatus !in setOf("watch", "warning", "fail", "degraded", "needs_action")) {
            return false
        }
        val isSubstantive = when (item.id.lowercase()) {
            "control_channel_packet_loss" -> false
            "packet_loss" -> evidenceStatus == "fail" &&
                item.source.equals("media_transport", ignoreCase = true)
            "latency" -> evidenceStatus == "fail"
            else -> true
        }
        if (!isSubstantive) return false

        // A healthy v2 envelope can carry informational capability watches, such as an
        // encoder that cannot retune bitrate live. Hard or contradictory evidence must
        // still fail closed even when the envelope itself says ok/info/green.
        return !authoritativeDoctorVerdictIsHealthy || evidenceStatus != "watch"
    }
    val hasActionableDoctorEvidence get() = doctor.evidenceItems.any(::doctorEvidenceIsActionable)
    private fun healthIssueIsCoveredByObservation(issue: String): Boolean =
        doctorPrimaryIsObservation &&
            (issue.contains("network", ignoreCase = true) ||
                issue.equals("control_channel_observation", ignoreCase = true) ||
                issue.equals("control_channel_packet_loss", ignoreCase = true))
    private val healthPrimaryActionable get() =
        !hasAuthoritativeDoctorResult && health.primaryIssue.isNotBlank() &&
            !health.primaryIssue.equals("none", ignoreCase = true) &&
            !healthIssueIsCoveredByObservation(health.primaryIssue)
    private val healthIssuesActionable get() = !hasAuthoritativeDoctorResult && health.issues.any {
        !healthIssueIsCoveredByObservation(it)
    }
    val hasHealthConcerns get() =
        (!hasAuthoritativeDoctorResult && health.grade.equals("degraded", ignoreCase = true)) ||
            (!hasAuthoritativeDoctorResult && health.grade.equals("watch", ignoreCase = true) && !doctorPrimaryIsObservation) ||
            healthPrimaryActionable ||
            healthIssuesActionable ||
            (doctor.primaryIssue.isNotBlank() &&
                !doctor.primaryIssue.equals("none", ignoreCase = true) &&
                !doctorPrimaryIsObservation) ||
            authoritativeDoctorVerdictNeedsAttention ||
            hasActionableDoctorEvidence
    val healthToneLabel get() = when {
        isHostRenderLimited -> "Host Render"
        doctor.primaryIssue.equals("frame_pacing", ignoreCase = true) ||
            (!hasAuthoritativeDoctorResult && (
                health.primaryIssue.equals("frame_pacing", ignoreCase = true) ||
                    health.issues.any { it.equals("frame_pacing", ignoreCase = true) }
                )) -> "Frame pacing"
        authoritativeDoctorVerdictNeedsAttention -> "Needs attention"
        doctorPrimaryIsObservation && hasActionableDoctorEvidence -> "Needs attention"
        doctor.primaryIssue.equals("network_observation", ignoreCase = true) -> "Network recheck"
        doctor.primaryIssue.equals("control_channel_observation", ignoreCase = true) -> "Control retries"
        doctor.primaryIssue.equals("network_jitter", ignoreCase = true) -> "Network"
        doctor.primaryIssue.contains("decoder", ignoreCase = true) -> "Decoder"
        !hasAuthoritativeDoctorResult && health.grade.equals("degraded", ignoreCase = true) -> "Stream degraded"
        !hasAuthoritativeDoctorResult && health.grade.equals("watch", ignoreCase = true) -> "Needs attention"
        doctor.primaryIssue.isNotBlank() && !doctor.primaryIssue.equals("none", ignoreCase = true) -> "Needs attention"
        hasActionableDoctorEvidence -> "Needs attention"
        !hasAuthoritativeDoctorResult && health.primaryIssue.isNotBlank() &&
            !health.primaryIssue.equals("none", ignoreCase = true) -> "Needs attention"
        !hasAuthoritativeDoctorResult && health.issues.isNotEmpty() -> "Needs attention"
        else -> "Stable"
    }
}

data class PolarisDoctorActionResult(
    val status: Boolean,
    val changed: Boolean = false,
    val state: String = "",
    val message: String = "",
    val error: String = "",
    val runId: String = "",
    val recoveryState: String = "",
    val appUuid: String = "",
    val expiresAt: Long = 0L,
    val safeProfile: PolarisSessionStatus.RecoverySafeProfile = PolarisSessionStatus.RecoverySafeProfile(),
    val verificationDelaySeconds: Int = 0,
    val verificationActionId: String = "",
    val undoAvailable: Boolean? = null,
    val undoActionId: String = "",
    val evidencePacketLossPct: Double? = null,
    val evidenceLatencyMs: Double? = null,
    val requestId: String = "",
    val changedContractValid: Boolean = true,
    val appSessionId: String = "",
    val sessionGeneration: Long = 0L,
    val scopeContractValid: Boolean = true
)
