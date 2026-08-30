package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import kotlin.math.roundToInt

data class AutoQualityUiState(
    val state: State,
    val label: String,
    val compactLabel: String,
    val detail: String,
    val targetSummary: String,
    val tone: Tone,
    val enabled: Boolean,
    val recovering: Boolean = false,
    val manualOverride: Boolean = false
) {
    enum class State {
        OFF,
        WATCHING,
        OPTIMIZING,
        STABLE,
        RECOVERING,
        BLOCKED,
        UPGRADE_AVAILABLE,
        MANUAL_OVERRIDE,
        NEEDS_ATTENTION
    }

    enum class Tone {
        MUTED,
        INFO,
        STABLE,
        WARNING,
        DANGER
    }

    companion object {
        @JvmStatic
        fun from(
            status: PolarisSessionStatus?,
            fallbackTargetFps: Double = 0.0
        ): AutoQualityUiState {
            if (status == null) {
                return AutoQualityUiState(
                    state = State.WATCHING,
                    label = "Checking Stream",
                    compactLabel = "CHECK",
                    detail = "Waiting for Polaris session status",
                    targetSummary = "",
                    tone = Tone.MUTED,
                    enabled = true
                )
            }

            val adaptiveEnabled = status.tuning.adaptiveBitrateEnabled || status.adaptiveBitrateEnabled
            val aiEnabled = status.tuning.aiOptimizerEnabled || status.aiOptimizerEnabled
            val autoEnabled = adaptiveEnabled || aiEnabled
            val syncState = status.syncStatus.state.lowercase()
            val presentationStatus = status.clientPresentation.status.lowercase()
            val targetFps = listOf(
                status.encoder.sessionTargetFps,
                status.encoder.encodeTargetFps,
                status.encoder.requestedClientFps,
                fallbackTargetFps
            ).firstOrNull { it > 0.0 } ?: 0.0
            val streamPolicy = StreamPolicyUiState.from(status, fallbackTargetFps = fallbackTargetFps)
            val autoPolicy = status.autoQuality
            val adaptiveTarget = streamPolicy.adaptiveTargetBitrateKbps
            val adaptiveLowered = streamPolicy.hasAdaptiveCap ||
                (
                    adaptiveEnabled &&
                        adaptiveTarget > 0 &&
                        streamPolicy.adaptiveBaseBitrateKbps > 0 &&
                        adaptiveTarget < streamPolicy.adaptiveBaseBitrateKbps
                    )
            val authoritativeDoctor = status.hasAuthoritativeDoctorResult
            val legacyHealthSummary = status.health.summary.takeIf {
                !authoritativeDoctor && it.isNotBlank()
            }
            val healthGrade = if (authoritativeDoctor) "stable" else status.health.grade.lowercase()
            val healthAutoAction = if (authoritativeDoctor) "" else status.health.autoAction.lowercase()
            val healthSuggestsRecovery = status.hasHealthConcerns ||
                (!authoritativeDoctor && status.health.recoveryProfile.isNotBlank()) ||
                (!authoritativeDoctor && status.health.relaunchRecommended) ||
                healthAutoAction == "lower_bitrate" ||
                healthAutoAction == "lower_render_profile" ||
                healthAutoAction == "apply_recovery" ||
                healthAutoAction == "suggest_recovery"
            val hostRenderLimited = status.isHostRenderLimited
            val currentBitrate = streamPolicy.effectiveBitrateKbps
            val safeBitrateApplied = !authoritativeDoctor && healthSuggestsRecovery &&
                status.health.safeBitrateKbps > 0 &&
                currentBitrate > 0 &&
                status.health.safeBitrateKbps < currentBitrate
            val safeFpsApplied = !authoritativeDoctor && status.health.safeTargetFps > 0.0 &&
                targetFps > 0.0 &&
                status.health.safeTargetFps < targetFps
            val hostRenderRecoveryQueued = hostRenderLimited &&
                (
                    (!authoritativeDoctor && autoPolicy.isRecoveryQueued) ||
                    (!authoritativeDoctor && status.health.relaunchRecommended) ||
                        safeFpsApplied
                    )
            val safeProfileApplied = safeFpsApplied ||
                safeBitrateApplied ||
                (!authoritativeDoctor && healthSuggestsRecovery && status.health.safeDisplayMode.isNotBlank()) ||
                (!authoritativeDoctor && status.health.recoveryProfile.isNotBlank())
            val cpuCapture = status.capture.transport.equals("shm", ignoreCase = true) ||
                status.capture.residency.equals("cpu", ignoreCase = true) ||
                status.encoder.targetResidency.equals("cpu", ignoreCase = true)
            val syncFailed = status.syncStatus.isFailed ||
                presentationStatus == "blocked"
            val severeHealth = healthGrade == "degraded" ||
                (!authoritativeDoctor && status.health.decoderRisk.equals("elevated", ignoreCase = true))
            val manualOverride = status.syncStatus.isManualOverride ||
                syncState == "manual_override"

            if (status.isHdrDowngraded) {
                return AutoQualityUiState(
                    state = State.NEEDS_ATTENTION,
                    label = "HDR Downgraded",
                    compactLabel = "HDR",
                    detail = "Polaris is sending 10-bit SDR, not HDR. Use an HDR-capable display path for true HDR.",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = true
                )
            }
            if (!autoEnabled && !manualOverride) {
                return AutoQualityUiState(
                    state = State.OFF,
                    label = "Live Tuning Off",
                    compactLabel = "OFF",
                    detail = "Manual stream tuning is active",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.MUTED,
                    enabled = false
                )
            }

            val manualNeedsAttention = manualOverride && (
                syncFailed ||
                    severeHealth ||
                    cpuCapture
                )

            if (manualOverride && manualNeedsAttention) {
                return AutoQualityUiState(
                    state = State.NEEDS_ATTENTION,
                    label = "Sync Attention",
                    compactLabel = "MAN",
                    detail = status.syncStatus.message.takeIf { it.isNotBlank() }
                        ?: legacyHealthSummary
                        ?: "Nova and Polaris need a settings check",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = autoEnabled,
                    manualOverride = true
                )
            }

            if (hostRenderRecoveryQueued) {
                val safeTarget = status.health.safeTargetFps
                    .takeIf { it > 0.0 }
                    ?: autoPolicy.suggestedTargetFps
                val detail = when {
                    safeTarget > 0.0 -> "Observed host pacing pressure near ${safeTarget.roundToInt()} FPS; launch settings are unchanged"
                    !authoritativeDoctor && autoPolicy.summary.isNotBlank() -> autoPolicy.summary
                    legacyHealthSummary != null -> legacyHealthSummary
                    else -> "Host render evidence needs a read-only pacing recheck"
                }
                return AutoQualityUiState(
                    state = State.NEEDS_ATTENTION,
                    label = "Frame Pacing Watch",
                    compactLabel = "HOST",
                    detail = detail,
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = true,
                    recovering = false,
                    manualOverride = manualOverride
                )
            }

            if (!authoritativeDoctor && autoPolicy.isBlocked) {
                val hostBlocked = autoPolicy.normalizedBlockedReason == "host_render_limited" ||
                    hostRenderLimited
                val label = when {
                    hostBlocked -> "Host render limited"
                    autoPolicy.normalizedBlockedReason == "network" -> "Network limited"
                    autoPolicy.normalizedBlockedReason == "encoder" -> "Encoder limited"
                    autoPolicy.normalizedBlockedReason == "decoder" -> "Decoder limited"
                    else -> "Live tuning holding"
                }
                return AutoQualityUiState(
                    state = State.BLOCKED,
                    label = label,
                    compactLabel = when {
                        hostBlocked -> "HOST"
                        autoPolicy.normalizedBlockedReason == "network" -> "NET"
                        autoPolicy.normalizedBlockedReason == "encoder" -> "ENC"
                        autoPolicy.normalizedBlockedReason == "decoder" -> "DEC"
                        else -> "HOLD"
                    },
                    detail = autoPolicy.summary.takeIf { it.isNotBlank() }
                        ?: legacyHealthSummary
                        ?: "Holding quality until the stream is stable",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = true,
                    recovering = false,
                    manualOverride = manualOverride
                )
            }

            if (hostRenderLimited) {
                return AutoQualityUiState(
                    state = State.BLOCKED,
                    label = "Host Render Limited",
                    compactLabel = "HOST",
                    detail = status.doctor.likelyCause.takeIf {
                        authoritativeDoctor && it.isNotBlank()
                    } ?: legacyHealthSummary
                        ?: "Holding quality until the host render path reaches the stream FPS target",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = true,
                    recovering = false,
                    manualOverride = manualOverride
                )
            }

            if (syncFailed || severeHealth || cpuCapture) {
                val label = when {
                    cpuCapture -> "Needs Attention"
                    status.health.decoderRisk.equals("elevated", ignoreCase = true) -> "Decoder pressure"
                    syncFailed -> "Sync attention"
                    else -> "Needs Attention"
                }
                return AutoQualityUiState(
                    state = State.NEEDS_ATTENTION,
                    label = label,
                    compactLabel = "ATTN",
                    detail = legacyHealthSummary
                        ?: status.syncStatus.message.takeIf { it.isNotBlank() }
                        ?: "Doctor needs a stream setting check",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.DANGER,
                    enabled = true
                )
            }

            if (autoPolicy.isRecoveringBitrate) {
                return AutoQualityUiState(
                    state = State.RECOVERING,
                    label = "Recovering Bitrate",
                    compactLabel = streamPolicy.adaptiveTargetLabel
                        .takeIf { it.isNotBlank() }
                        ?.replace(" Mbps", "M")
                        ?: "REC",
                    detail = autoPolicy.summary.takeIf { it.isNotBlank() }
                        ?: streamPolicy.statusCaption,
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.INFO,
                    enabled = true,
                    recovering = true,
                    manualOverride = manualOverride
                )
            }

            if (!authoritativeDoctor && autoPolicy.isUpgradeAvailable) {
                return AutoQualityUiState(
                    state = State.UPGRADE_AVAILABLE,
                    label = "Higher Quality Ready",
                    compactLabel = "UP",
                    detail = autoPolicy.summary.takeIf { it.isNotBlank() }
                        ?: if (autoPolicy.relaunchRequired) {
                            "Relaunch for higher quality"
                        } else {
                            "Higher quality is available on the next launch"
                        },
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.INFO,
                    enabled = true,
                    recovering = false,
                    manualOverride = manualOverride
                )
            }

            val sessionState = status.state.lowercase()
            if (sessionState == "initializing" ||
                sessionState == "cage_starting" ||
                sessionState == "game_launching" ||
                status.encoder.optimizationCacheStatus.equals("miss", ignoreCase = true) ||
                syncState == "applying"
            ) {
                return AutoQualityUiState(
                    state = State.OPTIMIZING,
                    label = "Applying Launch Preset",
                    compactLabel = "SETUP",
                    detail = "Resolving the selected preset against host and client capabilities",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.INFO,
                    enabled = true
                )
            }

            if (healthSuggestsRecovery ||
                adaptiveLowered ||
                safeProfileApplied ||
                (!authoritativeDoctor && status.health.relaunchRecommended) ||
                healthAutoAction == "lower_bitrate" ||
                healthAutoAction == "lower_render_profile" ||
                healthAutoAction == "apply_recovery" ||
                healthAutoAction == "suggest_recovery" ||
                hostRenderLimited
            ) {
                val label = when {
                    hostRenderLimited -> "Host render limited"
                    status.healthToneLabel == "Frame pacing" -> "Frame pacing"
                    healthGrade == "degraded" -> "Stream degraded"
                    healthGrade == "watch" -> "Needs attention"
                    adaptiveLowered -> "Live bitrate adjusted"
                    else -> "Doctor observation"
                }
                val detail = (if (authoritativeDoctor) {
                    status.doctor.likelyCause
                } else {
                    legacyHealthSummary.orEmpty()
                }).takeIf {
                    it.isNotBlank() && !it.trim().trimEnd('.', '!').equals("Stable", ignoreCase = true)
                }
                    ?: when {
                        status.hasHealthConcerns -> status.healthToneLabel
                        adaptiveLowered -> streamPolicy.statusCaption
                        safeFpsApplied -> "Historical FPS guidance is observational; launch settings are unchanged"
                        hostRenderLimited -> "Host render path is missing the target frame rate"
                        else -> "Recheck the measured evidence or review manual guidance"
                    }
                return AutoQualityUiState(
                    state = State.NEEDS_ATTENTION,
                    label = label,
                    compactLabel = when {
                        hostRenderLimited -> "HOST"
                        adaptiveLowered -> streamPolicy.adaptiveTargetLabel.replace(" Mbps", "M")
                        else -> "REC"
                    },
                    detail = detail,
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.WARNING,
                    enabled = true,
                    recovering = false,
                    manualOverride = manualOverride
                )
            }

            if (manualOverride) {
                return AutoQualityUiState(
                    state = State.STABLE,
                    label = "Quality Preset",
                    compactLabel = "QLTY",
                    detail = status.syncStatus.message.takeIf { it.isNotBlank() }
                        ?: streamPolicy.statusCaption,
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.STABLE,
                    enabled = autoEnabled,
                    manualOverride = true
                )
            }

            if (!status.isStreaming) {
                return AutoQualityUiState(
                    state = State.WATCHING,
                    label = "Launch Ready",
                    compactLabel = "READY",
                    detail = "Ready for the next stream",
                    targetSummary = streamPolicy.targetSummary,
                    tone = Tone.INFO,
                    enabled = true
                )
            }

            return AutoQualityUiState(
                state = State.STABLE,
                label = if (autoPolicy.isAtQualityCap) "Stream at Quality Cap" else "Stream Ready",
                compactLabel = "OK",
                detail = autoPolicy.summary.takeIf { !authoritativeDoctor && it.isNotBlank() }
                    ?: status.doctor.likelyCause.takeIf {
                        authoritativeDoctor &&
                            it.isNotBlank() &&
                            !it.equals("No confirmed issue", ignoreCase = true)
                    }
                    ?: legacyHealthSummary
                    ?: "Stream target is holding steady",
                targetSummary = streamPolicy.targetSummary,
                tone = Tone.STABLE,
                enabled = true
            )
        }

    }
}
