package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus

// Keep the source name for existing HUD consumers; this models only Live Tuning.
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
    enum class State { OFF, WATCHING, OPTIMIZING, STABLE, RECOVERING, BLOCKED,
        UPGRADE_AVAILABLE, MANUAL_OVERRIDE, NEEDS_ATTENTION }
    enum class Tone { MUTED, INFO, STABLE, WARNING, DANGER }
    companion object {
        @JvmStatic fun from(status: PolarisSessionStatus?, fallbackTargetFps: Double = 0.0): AutoQualityUiState {
            val policy = StreamPolicyUiState.from(status, fallbackTargetFps = fallbackTargetFps)
            val live = status?.liveTuning
            val unknown = status == null || (status.liveTuningPresent && live == null)
            if (unknown) return AutoQualityUiState(State.WATCHING, "Live Tuning: Unknown", "Tuning: Unknown",
                "Waiting for the host to confirm the setting", policy.targetSummary, Tone.MUTED, false)
            val enabled = live?.enabled ?: (status!!.tuning.adaptiveBitrateEnabled || status.adaptiveBitrateEnabled)
            if (!enabled) return AutoQualityUiState(State.OFF, "Live Tuning Off", "Tuning Off",
                "Automatic bitrate adjustment is off", policy.targetSummary, Tone.MUTED, false)
            // Older hosts expose preference and target only. Never claim encoder acknowledgement.
            if (live == null) return AutoQualityUiState(State.WATCHING, "Live Tuning On", "Tuning: On",
                "Enabled on this host; encoder acknowledgement is unavailable", policy.targetSummary, Tone.INFO, true)
            val label = when (live.state) {
                "waiting" -> "Live Tuning On — waiting for a stream"
                "unavailable" -> "Live Tuning On — unavailable for this stream"
                "applying" -> "Live Tuning On — applying bitrate"
                "measuring" -> "Live Tuning On — measuring"
                "adjusting" -> "Live Tuning On — adjusting"
                else -> "Live Tuning On — stable"
            }
            val compact = when (live.state) {
                "waiting" -> "Tuning: Waiting"
                "unavailable" -> "Tuning: Unavailable"
                "applying" -> "Tuning: Applying"
                "measuring" -> "Tuning: Measuring"
                "adjusting" -> "Auto: ${live.appliedBitrateKbps / 1000} / ${live.qualityLimitKbps / 1000}M"
                else -> "Tuning: On"
            }
            val detail = if (live.state == "unavailable") live.reason.replace('_', ' ') else
                if (live.appliedBitrateKbps > 0) "${StreamPolicyUiState.formatMbps(live.appliedBitrateKbps)} applied / ${StreamPolicyUiState.formatMbps(live.qualityLimitKbps)} limit" else
                    "Automatically adjust stream bitrate within your quality limit"
            return AutoQualityUiState(when (live.state) {
                "unavailable" -> State.BLOCKED
                "applying", "adjusting" -> State.RECOVERING
                "waiting", "measuring" -> State.WATCHING
                else -> State.STABLE
            }, label, compact, detail, policy.targetSummary, if (live.supported) Tone.STABLE else Tone.INFO, true)
        }
    }
}
