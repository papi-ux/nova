package com.papi.nova.api

data class PolarisSessionStatus(
    val state: String,
    val streamingActive: Boolean = false,
    val shutdownRequested: Boolean = false,
    val game: String = "",
    val gameId: Int = 0,
    val gameUuid: String = "",
    val sessionToken: String = "",
    val ownerUniqueId: String = "",
    val ownerDeviceName: String = "",
    val clientRole: String = "none",
    val viewerCount: Int = 0,
    val ownedByClient: Boolean = false,
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
    val autoQuality: AutoQualityPolicy = AutoQualityPolicy(),
    val profileState: ProfileState = ProfileState(),
    val health: HealthStatus = HealthStatus()
) {
    data class ControlsStatus(
        val hostTuningAllowed: Boolean = false,
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
        val gpuNativeOverrideActive: Boolean = false
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
        val targetFormat: String = ""
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
    val isHeadlessMode get() = displayMode.effectiveHeadless
    val isVirtualDisplayMode get() = displayMode.virtualDisplay
    val sessionModeLabel get() = when {
        displayMode.label.isNotBlank() -> displayMode.label
        displayMode.effectiveHeadless -> "Headless"
        displayMode.virtualDisplay -> "Virtual Display"
        else -> "Host Display"
    }
    val isViewer get() = clientRole.equals("viewer", ignoreCase = true)
    val hasExplicitDisplayModeChoice get() = displayMode.explicitChoice
    val canAdjustHostTuning get() = controls.hostTuningAllowed || (ownedByClient && !isViewer)
    val canQuit get() = controls.quitAllowed || (ownedByClient && !isViewer)

    private val String.isCudaGpuTarget: Boolean
        get() = equals("cuda", ignoreCase = true) ||
            equals("gpu", ignoreCase = true) ||
            equals("nvidia", ignoreCase = true)
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
    val isHostRenderLimited get() =
        health.hostRenderLimited ||
            health.primaryIssue.equals("host_render_limited", ignoreCase = true) ||
            health.issues.any { it.equals("host_render_limited", ignoreCase = true) }
    val hasHealthConcerns get() = health.grade.equals("watch", ignoreCase = true) || health.grade.equals("degraded", ignoreCase = true)
    val healthToneLabel get() = when {
        isHostRenderLimited -> "Host render"
        health.grade.equals("degraded", ignoreCase = true) -> "Degraded"
        health.grade.equals("watch", ignoreCase = true) -> "Watch"
        else -> "Stable"
    }
}
