package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import kotlin.math.roundToInt

data class StreamPolicyUiState(
    val effectiveBitrateKbps: Int,
    val qualityLimitBitrateKbps: Int,
    val adaptiveTargetBitrateKbps: Int,
    val adaptiveBaseBitrateKbps: Int,
    val adaptiveEnabled: Boolean,
    val aiEnabled: Boolean,
    val codecLabel: String,
    val displayLabel: String,
    val hostCaptureLabel: String = ""
) {
    val autoQualityEnabled get() = adaptiveEnabled || aiEnabled
    val hasAdaptiveCap get() = adaptiveEnabled &&
        adaptiveTargetBitrateKbps > 0 &&
        qualityLimitBitrateKbps > 0 &&
        adaptiveTargetBitrateKbps < qualityLimitBitrateKbps
    val effectiveBitrateLabel get() = formatMbps(effectiveBitrateKbps)
    val qualityLimitLabel get() = formatMbps(qualityLimitBitrateKbps)
    val adaptiveTargetLabel get() = formatMbps(adaptiveTargetBitrateKbps)

    val bitrateSummary: String
        get() = when {
            hasAdaptiveCap -> "$adaptiveTargetLabel live / $qualityLimitLabel limit"
            effectiveBitrateKbps > 0 -> "up to $effectiveBitrateLabel"
            else -> ""
        }

    val targetSummary: String
        get() = listOf(displayLabel, codecLabel, hostCaptureLabel, bitrateSummary)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    val statusCaption: String
        get() = when {
            hasAdaptiveCap -> "Auto Safe is live at $adaptiveTargetLabel under your $qualityLimitLabel quality limit."
            adaptiveEnabled && adaptiveTargetBitrateKbps > 0 -> "Auto Safe live target is $adaptiveTargetLabel."
            autoQualityEnabled && qualityLimitBitrateKbps > 0 -> "Auto Safe is using your $qualityLimitLabel quality limit."
            qualityLimitBitrateKbps > 0 -> "Using your $qualityLimitLabel quality limit."
            else -> "Waiting for stream policy."
        }

    companion object {
        @JvmStatic
        fun from(
            status: PolarisSessionStatus?,
            fallbackBitrateKbps: Int = 0,
            fallbackTargetFps: Double = 0.0
        ): StreamPolicyUiState {
            val adaptiveEnabled = status?.tuning?.adaptiveBitrateEnabled == true ||
                status?.adaptiveBitrateEnabled == true
            val aiEnabled = status?.tuning?.aiOptimizerEnabled == true ||
                status?.aiOptimizerEnabled == true
            val autoPolicy = status?.autoQuality
            val adaptiveTarget = firstPositive(
                autoPolicy?.liveBitrateKbps,
                status?.syncStatus?.effective?.adaptiveTargetBitrateKbps,
                status?.syncStatus?.applied?.adaptiveTargetBitrateKbps,
                status?.tuning?.adaptiveTargetBitrateKbps,
                status?.adaptiveTargetBitrateKbps
            )
            val qualityLimit = firstPositive(
                autoPolicy?.qualityCapKbps,
                status?.syncStatus?.effective?.targetBitrateKbps,
                status?.syncStatus?.applied?.targetBitrateKbps,
                status?.encoder?.bitrateKbps,
                fallbackBitrateKbps
            )
            val effectiveBitrate = when {
                adaptiveEnabled &&
                    adaptiveTarget > 0 &&
                    (qualityLimit <= 0 || adaptiveTarget <= qualityLimit) -> adaptiveTarget
                qualityLimit > 0 -> qualityLimit
                else -> firstPositive(fallbackBitrateKbps)
            }
            val fps = firstPositiveDouble(
                status?.encoder?.sessionTargetFps,
                status?.encoder?.encodeTargetFps,
                status?.encoder?.requestedClientFps,
                fallbackTargetFps
            )
            val resolution = when {
                status?.capture?.resolution?.isNotBlank() == true -> status.capture.resolution
                status?.displayMode?.selection?.isNotBlank() == true -> status.displayMode.selection
                else -> ""
            }
            val display = when {
                resolution.isNotBlank() && fps > 0.0 -> "$resolution@${fps.roundToInt()}"
                resolution.isNotBlank() -> resolution
                fps > 0.0 -> "${fps.roundToInt()} FPS"
                else -> ""
            }

            return StreamPolicyUiState(
                effectiveBitrateKbps = effectiveBitrate,
                qualityLimitBitrateKbps = qualityLimit,
                adaptiveTargetBitrateKbps = adaptiveTarget,
                adaptiveBaseBitrateKbps = status?.tuning?.adaptiveBaseBitrateKbps ?: 0,
                adaptiveEnabled = adaptiveEnabled,
                aiEnabled = aiEnabled,
                codecLabel = normalizeCodec(status?.encoder?.codec.orEmpty()),
                displayLabel = display,
                hostCaptureLabel = status?.hostCaptureTruthLabel.orEmpty()
            )
        }

        fun formatMbps(kbps: Int): String {
            if (kbps <= 0) {
                return ""
            }
            val mbps = kbps / 1000.0
            return if (kbps >= 10000 || kbps % 1000 == 0) {
                "${mbps.roundToInt()} Mbps"
            } else {
                String.format(java.util.Locale.US, "%.1f Mbps", mbps)
            }
        }

        private fun firstPositive(vararg values: Int?): Int {
            return values.firstOrNull { it != null && it > 0 } ?: 0
        }

        private fun firstPositiveDouble(vararg values: Double?): Double {
            return values.firstOrNull { it != null && it > 0.0 } ?: 0.0
        }

        private fun normalizeCodec(codec: String): String {
            val lower = codec.lowercase()
            return when {
                lower.contains("av1") -> "AV1"
                lower.contains("hevc") || lower.contains("h265") -> "HEVC"
                lower.contains("h264") || lower.contains("avc") -> "H.264"
                codec.isBlank() -> ""
                else -> codec.uppercase()
            }
        }
    }
}
