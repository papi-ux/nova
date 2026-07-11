package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject
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

        fun buildLaunchOptimizationOverride(choice: NovaDisplayResolutionChoice, source: String): JSONObject {
            return JSONObject().apply {
                put("source", source)
                put("confidence", "high")
                put("display_mode", choice.targetMode)
                put("paired_profile_applied", true)
                put("normalization_reason", "display_resolution_planner")
                put("preference", "auto")
                put("preference_applied", true)
                put("display_planner_choice", choice.id)
            }
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
            val qualityLine = "Quality: ${grade.replaceFirstChar { it.titlecase(Locale.US) }}"
            val issueLine = "Main issue: ${issue.ifBlank { "none" }.replace('_', ' ')}"
            val nextLaunchLine = "Next launch: ${nextLaunchParts.joinToString(" · ").ifBlank { "keep current settings" }}"
            val recoveryLine = "Recovery: ${health.recoveryProfile.ifBlank { "none" }.replace('_', ' ')}"
            val summary = health.summary.ifBlank { "Post-session report unavailable on this Polaris host." }
            return NovaPostSessionReportUiState(
                visible = summary.isNotBlank() || grade != "unknown" || issue.isNotBlank(),
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
