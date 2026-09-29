package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.shared.polaris.model.PolarisGame
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Handheld-first Nova wrapper for the Polaris display/resolution planner contract.
 * Older Polaris hosts omit display_planner; in that case Nova keeps its existing launch flow.
 */
data class NovaDisplayResolutionPlanner(
    val available: Boolean,
    val sourceMode: String,
    val recommendedId: String,
    val recommendedMode: String,
    val visibleChoices: List<NovaDisplayResolutionChoice>,
    val hasAdvancedChoices: Boolean
) {
    /** The resolution this device is set to stream at: what a launch uses when nothing is chosen. */
    data class DeviceMode(val width: Int, val height: Int, val fps: Int) {
        val mode: String get() = "${width}x${height}x$fps"
    }

    companion object {
        /**
         * The choice that stands for "whatever this device is set to". It is the default, and
         * choosing it is the same as choosing nothing, so it is never stored as an override.
         */
        const val DEVICE_SETTINGS_ID = "device_settings"

        fun from(
            contract: PolarisGame.DisplayPlannerContract?,
            fallbackMode: String,
            includeAdvanced: Boolean,
            device: DeviceMode? = null
        ): NovaDisplayResolutionPlanner {
            if (contract?.available != true) {
                return NovaDisplayResolutionPlanner(
                    available = false,
                    sourceMode = fallbackMode,
                    recommendedId = "",
                    recommendedMode = "",
                    visibleChoices = emptyList(),
                    hasAdvancedChoices = false
                )
            }

            if (device != null && device.width > 0 && device.height > 0) {
                return forDevice(contract, device, includeAdvanced)
            }

            val recommended = contract.recommendedId.ifBlank { "balanced" }
            val choices = contract.choices
                .filter { it.id.isNotBlank() && it.targetMode.isNotBlank() && it.safe && !it.hidden }
                .map { choice ->
                    NovaDisplayResolutionChoice(
                        id = choice.id,
                        title = plannerTitle(choice, recommended),
                        targetMode = choice.targetMode,
                        badge = meaningfulBadge(choice, recommended),
                        reason = choice.reason.ifBlank { choice.intent },
                        advanced = choice.advanced,
                        custom = choice.custom,
                        safe = choice.safe,
                        recommended = choice.id == recommended
                    )
                }
            return NovaDisplayResolutionPlanner(
                available = true,
                sourceMode = contract.sourceMode.ifBlank { fallbackMode },
                recommendedId = recommended,
                recommendedMode = contract.recommendedMode,
                visibleChoices = choices.filter { includeAdvanced || !it.advanced },
                hasAdvancedChoices = choices.any { it.advanced }
            )
        }

        /**
         * The host's presets, planned from this device rather than from the host.
         *
         * The host plans from its own fallback display mode, the same for every client, while
         * its wording is about the client: "Match the client panel exactly", "Best for this
         * device". On a handheld set to 1340x800 against a 1920x1080 host every line of that
         * was false, and the row's value was too: with nothing chosen a launch uses this
         * device's saved resolution, not the host's recommendation. So the presets keep their
         * scale factors and are applied to the device's resolution, the default is the device's
         * own setting, and it is what the row reads until something else is chosen.
         *
         * The host's Custom preset is left out. It is the web console's hand-typed scale
         * factor, which reaches a client as 1, so it was a second copy of Native under a name
         * that reads as this device's Custom resolution.
         */
        private fun forDevice(
            contract: PolarisGame.DisplayPlannerContract,
            device: DeviceMode,
            includeAdvanced: Boolean
        ): NovaDisplayResolutionPlanner {
            val deviceChoice = NovaDisplayResolutionChoice(
                id = DEVICE_SETTINGS_ID,
                // Eleven characters: what one line of a legend card holds with four cards
                // across a handheld. "Device Settings" wrapped, and cost the legend a line.
                title = "This Device",
                targetMode = device.mode,
                badge = "",
                reason = "Use this device's saved resolution.",
                advanced = false,
                custom = false,
                safe = true,
                recommended = true
            )
            val presets = contract.choices
                .filter { it.id.isNotBlank() && it.id != DEVICE_SETTINGS_ID && !it.custom }
                .mapNotNull { choice ->
                    val width = roundToEven(device.width * choice.scaleFactor)
                    val height = roundToEven(device.height * choice.scaleFactor)
                    // A preset that lands on the device's own size is the default again.
                    if (width == device.width && height == device.height) return@mapNotNull null
                    if (!safeMode(width, height)) return@mapNotNull null
                    NovaDisplayResolutionChoice(
                        id = choice.id,
                        // "Sharp / Supersampled" is the console's heading. A card a quarter of a
                        // handheld wide has room for the name, and its sentence says the rest.
                        title = choice.title.substringBefore(" / ").trim()
                            .ifBlank { choice.id.replaceFirstChar { it.titlecase(Locale.US) } },
                        targetMode = "${width}x${height}x${device.fps}",
                        badge = choice.badge.takeUnless { it.equals("Press A", ignoreCase = true) }.orEmpty(),
                        reason = presetReason(choice),
                        advanced = choice.advanced,
                        custom = false,
                        safe = true,
                        recommended = false
                    )
                }
                .distinctBy { it.targetMode }
            // The host's scale factors, which it has always sent and nothing read: 2x is 4K on a
            // 1080p handheld, and no preset reaches it. Each is applied to this device as the
            // presets are, and one that lands on a size already listed is that row again.
            val listed = (listOf(deviceChoice) + presets).map { it.targetMode }.toMutableSet()
            val scales = contract.advancedScaleFactors
                .filter { it.safe && it.scaleFactor.isFinite() && it.scaleFactor > 0.0 }
                .sortedBy { it.scaleFactor }
                .mapNotNull { scale ->
                    val width = roundToEven(device.width * scale.scaleFactor)
                    val height = roundToEven(device.height * scale.scaleFactor)
                    val mode = "${width}x${height}x${device.fps}"
                    if (!safeMode(width, height) || !listed.add(mode)) return@mapNotNull null
                    val label = scale.label.trim().ifBlank { scaleLabel(scale.scaleFactor) }
                    NovaDisplayResolutionChoice(
                        id = SCALE_ID_PREFIX + label,
                        title = label,
                        targetMode = mode,
                        badge = "",
                        reason = if (scale.scaleFactor > 1.0) SCALE_ABOVE_REASON else SCALE_BELOW_REASON,
                        advanced = true,
                        custom = false,
                        safe = true,
                        recommended = false
                    )
                }
            val offered = presets + scales
            return NovaDisplayResolutionPlanner(
                available = true,
                sourceMode = device.mode,
                recommendedId = DEVICE_SETTINGS_ID,
                recommendedMode = device.mode,
                visibleChoices = listOf(deviceChoice) + offered.filter { includeAdvanced || !it.advanced },
                hasAdvancedChoices = offered.any { it.advanced }
            )
        }

        /** The id of a choice made from one of the host's scale factors, before its label. */
        const val SCALE_ID_PREFIX = "scale_"

        private const val SCALE_ABOVE_REASON = "Rendered larger than this screen, then scaled down to it."
        private const val SCALE_BELOW_REASON = "Rendered smaller than this screen, then scaled up to it."

        /** A scale as a person says it, 2x or 1.5x, for a host that sent no label. */
        private fun scaleLabel(scale: Double): String {
            val whole = kotlin.math.abs(scale - kotlin.math.round(scale)) < 0.01
            return if (whole) "${kotlin.math.round(scale).toInt()}x" else "${scale}x"
        }

        /** The host's rounding, so a preset lands on the size the host would have planned. */
        private fun roundToEven(value: Double): Int {
            if (!value.isFinite()) return 0
            val rounded = maxOf(2L, Math.round(value))
            return (if (rounded % 2L == 0L) rounded else rounded + 1).toInt()
        }

        /** The host's safe-mode envelope: nothing past 8K, and nothing degenerate. */
        private fun safeMode(width: Int, height: Int): Boolean =
            width in 2..7680 && height in 2..4320 && width.toLong() * height <= 7680L * 4320L

        /**
         * The host opens Balanced with "Best for this device:". Here the default is the
         * device's own setting, so the sentence starts after that claim.
         */
        private fun presetReason(choice: PolarisGame.DisplayPlannerChoice): String {
            val reason = choice.reason.ifBlank { choice.intent }
            val claim = "Best for this device:"
            if (!reason.startsWith(claim, ignoreCase = true)) return reason
            return reason.substring(claim.length).trim().replaceFirstChar { it.titlecase(Locale.US) }
        }

        /**
         * The width by height half of a planner target mode, with the one multiplication sign
         * Play Setup writes everywhere. The trailing rate is the host's own plan for that mode,
         * not a decision this row makes (the frame rate is owned by its own row and the launch
         * composer), so the row's value must not read as one.
         */
        fun resolutionLabel(targetMode: String): String {
            val parts = targetMode.trim().split('x', 'X', '×')
            return if (parts.size >= 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                "${parts[0]}×${parts[1]}"
            } else {
                targetMode
            }
        }

        /** The width and height of a planner target mode, or null when it names no size. */
        fun resolutionSize(targetMode: String): Pair<Int, Int>? {
            val parts = targetMode.trim().split('x', 'X', '×')
            val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val height = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return (width to height).takeIf { width > 0 && height > 0 }
        }

        /** The trailing rate half of a planner target mode, or null when it can't be read. */
        fun resolutionFps(targetMode: String): Int? {
            val parts = targetMode.trim().split('x', 'X')
            return parts.getOrNull(2)?.toFloatOrNull()?.roundToInt()
        }

        private fun plannerTitle(choice: PolarisGame.DisplayPlannerChoice, recommendedId: String): String {
            return if (choice.id == recommendedId) {
                "Best for this device"
            } else {
                choice.title.ifBlank { choice.id.replaceFirstChar { it.titlecase(Locale.US) } }
            }
        }

        private fun meaningfulBadge(choice: PolarisGame.DisplayPlannerChoice, recommendedId: String): String {
            val badge = choice.badge.takeUnless { it.equals("Press A", ignoreCase = true) }.orEmpty()
            return when {
                choice.id == recommendedId -> "Recommended"
                badge.isNotBlank() -> badge
                choice.custom -> "Advanced"
                choice.advanced -> "Advanced"
                else -> choice.targetMode
            }
        }
    }
}

data class NovaDisplayResolutionChoice(
    val id: String,
    val title: String,
    val targetMode: String,
    val badge: String,
    val reason: String,
    val advanced: Boolean,
    val custom: Boolean,
    val safe: Boolean,
    val recommended: Boolean
)

data class NovaPostSessionReportUiState(
    val visible: Boolean,
    val qualityLine: String,
    val issueLine: String,
    val nextLaunchLine: String,
    val recoveryLine: String,
    val copyDiagnostics: String
) {
    companion object {
        fun from(health: PolarisSessionStatus.HealthStatus): NovaPostSessionReportUiState {
            val grade = health.grade.ifBlank { "unknown" }
            val issue = health.primaryIssue.ifBlank { health.issues.firstOrNull().orEmpty() }
            val nextLaunchParts = buildList {
                health.safeDisplayMode.ifBlank { null }?.let { add(it.replace('_', ' ')) }
                if (health.safeTargetFps > 0.0) add("${health.safeTargetFps.roundToInt()}fps")
                if (health.safeBitrateKbps > 0) add("${health.safeBitrateKbps / 1000} Mbps")
                health.safeCodec.ifBlank { null }?.let { add(it.uppercase(Locale.US)) }
            }
            val qualityLine = "Grade: ${grade.replaceFirstChar { it.titlecase(Locale.US) }}"
            val issueLine = "Main issue: ${issue.ifBlank { "none" }.replace('_', ' ')}"
            val nextLaunchLine = "Safe profile: ${nextLaunchParts.joinToString(" · ").ifBlank { "keep current settings" }}"
            val recoveryLine = "Recovery record: ${health.recoveryProfile.ifBlank { "none" }.replace('_', ' ')}"
            val summary = health.summary.ifBlank { "Host safe profile unavailable on this Polaris host." }
            val gradeNeedsAttention = grade.lowercase(Locale.US) in setOf("watch", "degraded")
            return NovaPostSessionReportUiState(
                // A steady health block has nothing to report. The card earns its space only
                // when the host carries a safe profile, a recovery record, or an issue.
                visible = nextLaunchParts.isNotEmpty() ||
                    health.recoveryProfile.isNotBlank() ||
                    issue.isNotBlank() ||
                    gradeNeedsAttention,
                qualityLine = qualityLine,
                issueLine = issueLine,
                nextLaunchLine = nextLaunchLine,
                recoveryLine = recoveryLine,
                copyDiagnostics = listOf(summary, qualityLine, issueLine, nextLaunchLine, recoveryLine)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            )
        }
    }
}
