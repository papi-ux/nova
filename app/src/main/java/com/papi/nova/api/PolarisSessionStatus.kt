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
    val aiOptimizerEnabled: Boolean = false,
    val mangohudConfigured: Boolean = false,
    val controls: ControlsStatus = ControlsStatus(),
    val tuning: TuningStatus = TuningStatus(),
    val displayMode: DisplayModeStatus = DisplayModeStatus(),
    val capture: CaptureStatus = CaptureStatus(),
    val encoder: EncoderStatus = EncoderStatus()
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

    val isStreaming get() = state == "streaming" || streamingActive
    val isSessionAlive get() = state in listOf("initializing", "cage_starting", "game_launching", "streaming")
    val isShuttingDown get() = shutdownRequested || state == "tearing_down"
    val isTenBitActive get() = dynamicRange > 0 || encoder.targetFormat.equals("p010", ignoreCase = true)
    val isGpuPath get() = encoder.targetResidency.equals("gpu", ignoreCase = true)
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
}
