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
    companion object {
        fun from(
            contract: PolarisGame.DisplayPlannerContract?,
            fallbackMode: String,
            includeAdvanced: Boolean
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
         * The width x height half of a planner target mode. The trailing rate is the
         * host's own plan for that mode, not a decision this row makes -- the frame
         * rate is owned by Tuning and the launch composer -- so the row's value must
         * not read as one.
         */
        fun resolutionLabel(targetMode: String): String {
            val parts = targetMode.trim().split('x', 'X')
            return if (parts.size == 3) "${parts[0]}x${parts[1]}" else targetMode
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
