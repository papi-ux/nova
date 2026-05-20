package com.papi.nova.ui

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

data class NovaLaunchProfileSummary(
    val primaryLaunchLabel: String,
    val requestedLine: String,
    val selectedLine: String,
    val reasonLine: String,
    val limitingLine: String,
    val freshnessLine: String,
    val historyLines: List<String>,
    val showRetryHighFps: Boolean,
    val retryHighFpsLabel: String
)

internal fun buildNovaLaunchProfileSummary(
    optimization: JSONObject?,
    nowSeconds: Long = System.currentTimeMillis() / 1000L
): NovaLaunchProfileSummary? {
    if (optimization == null) return null

    val profileState = optimization.optJSONObject("profile_state")
    val currentProfile = profileState?.optJSONObject("current_profile")
        ?: optimization.optJSONObject("effective_profile")
    val requestedProfile = optimization.optJSONObject("preference_requested_profile")
        ?: optimization.optJSONObject("requested_profile")
    val lastResult = profileState?.optJSONObject("last_result")
    val actions = profileState?.optJSONObject("actions")

    val preference = normalized(optimization.optString("preference", profileState?.optString("preference", "auto") ?: "auto"))
    val preferenceLabel = profileState
        ?.optString("preference_label", "")
        ?.takeIf { it.isNotBlank() }
        ?: preferenceLabel(preference)
    val state = normalized(profileState?.optString("state", "") ?: "")
    val rawSelectedLabel = profileState
        ?.optString("label", "")
        ?.takeIf { it.isNotBlank() }
        ?: selectedLabelFromState(state)
    val trialProfile = optimization.optBoolean("trial_profile", false) ||
        profileState?.optBoolean("trial_profile", false) == true

    val requestedFps = firstPositive(
        requestedProfile?.optDouble("target_fps", 0.0) ?: 0.0,
        optimization.optDouble("preference_requested_target_fps", 0.0),
        optimization.optDouble("requested_target_fps", 0.0),
        parseDisplayModeFps(requestedProfile?.optString("display_mode", ""))
    )
    val effectiveFps = firstPositive(
        currentProfile?.optDouble("target_fps", 0.0) ?: 0.0,
        optimization.optDouble("effective_target_fps", 0.0),
        parseDisplayModeFps(currentProfile?.optString("display_mode", "")),
        parseDisplayModeFps(optimization.optString("display_mode", ""))
    )
    val highFpsRequestSatisfied = preference == "high_fps" &&
        requestedFps > 0.0 &&
        effectiveFps > 0.0 &&
        effectiveFps + 0.5 >= requestedFps
    val selectedLabel = when {
        trialProfile -> "High FPS Trial"
        highFpsRequestSatisfied -> "High FPS"
        else -> rawSelectedLabel
    }

    val primaryLabel = when {
        trialProfile && effectiveFps > 0.0 -> "Launch High FPS Trial ${formatFps(effectiveFps)} FPS"
        selectedLabel.equals("High FPS", ignoreCase = true) && effectiveFps > 0.0 ->
            "Launch High FPS ${formatFps(effectiveFps)} FPS"
        selectedLabel.equals("Recovery", ignoreCase = true) && effectiveFps > 0.0 ->
            "Launch Recovery ${formatFps(effectiveFps)} FPS"
        effectiveFps > 0.0 -> "Launch ${formatFps(effectiveFps)} FPS"
        selectedLabel.isNotBlank() -> "Launch $selectedLabel"
        else -> ""
    }

    val requestedLine = if (requestedFps > 0.0) {
        "Requested: $preferenceLabel / ${formatFps(requestedFps)} FPS"
    } else {
        "Requested: $preferenceLabel"
    }
    val selectedLine = if (effectiveFps > 0.0) {
        "Selected: $selectedLabel / ${formatFps(effectiveFps)} FPS"
    } else {
        "Selected: $selectedLabel"
    }

    val reasonText = profileState
        ?.optString("reason", "")
        ?.takeIf { it.isNotBlank() }
        ?: optimization.optString("reasoning", "").takeIf { it.isNotBlank() }.orEmpty()
    val reasonLine = reasonText.takeIf { it.isNotBlank() }?.let { "Reason: $it" }.orEmpty()

    val issue = limitingIssue(optimization, lastResult)
    val limitingLine = issue.takeIf { it.isNotBlank() }?.let { "Limited by: ${issueLabel(it)}" }.orEmpty()

    val updatedAt = lastResult?.optLong("updated_at", 0L) ?: 0L
    val freshnessLine = when {
        trialProfile -> "One-launch trial; learned recovery remains active unless this launch grades cleanly."
        selectedLabel.equals("Recovery", ignoreCase = true) && updatedAt > 0L ->
            "Recovery active from last session · ${relativeAge(updatedAt, nowSeconds)}"
        selectedLabel.equals("Recovery", ignoreCase = true) ->
            "Recovery active from last session"
        else -> ""
    }

    val historyLines = buildHistoryLines(lastResult, issue, selectedLabel)
    val highFpsHeldBelowRequest = preference == "high_fps" &&
        requestedFps > 0.0 &&
        effectiveFps > 0.0 &&
        requestedFps > effectiveFps + 0.5
    val preferenceApplied = optimization.optBoolean(
        "preference_applied",
        profileState?.optBoolean("preference_applied", false) ?: false
    )
    val showRetryHighFps = !trialProfile &&
        highFpsHeldBelowRequest &&
        (
            actions?.optBoolean("can_retry_high_fps", false) == true ||
                (preference == "high_fps" && !preferenceApplied)
        )
    val retryLabel = if (requestedFps > effectiveFps + 0.5) {
        "Try ${formatFps(requestedFps)} FPS once"
    } else {
        "Try High FPS once"
    }

    return NovaLaunchProfileSummary(
        primaryLaunchLabel = primaryLabel,
        requestedLine = requestedLine,
        selectedLine = selectedLine,
        reasonLine = reasonLine,
        limitingLine = limitingLine,
        freshnessLine = freshnessLine,
        historyLines = historyLines,
        showRetryHighFps = showRetryHighFps,
        retryHighFpsLabel = retryLabel
    )
}

private fun buildHistoryLines(
    lastResult: JSONObject?,
    issue: String,
    selectedLabel: String
): List<String> {
    if (lastResult == null) return emptyList()

    val lines = mutableListOf<String>()
    val grade = lastResult.optString("grade", "").takeIf { it.isNotBlank() }
    val deliveredFps = lastResult.optDouble("delivered_fps", 0.0)
    val targetFps = lastResult.optDouble("target_fps", 0.0)
    if (grade != null && deliveredFps > 0.0 && targetFps > 0.0) {
        lines += "Last: grade $grade at ${formatFps(deliveredFps)}/${formatFps(targetFps)} FPS"
    } else if (grade != null) {
        lines += "Last: grade $grade"
    }
    if (issue.isNotBlank()) {
        lines += "Issue: ${issueLabel(issue)}"
    }
    if (selectedLabel.equals("Recovery", ignoreCase = true)) {
        lines += "Next: one clean launch can release recovery, or reset this game profile."
    }
    return lines
}

private fun limitingIssue(optimization: JSONObject, lastResult: JSONObject?): String {
    val limitingFactor = meaningfulIssue(optimization.optString("limiting_factor", ""))
    if (limitingFactor.isNotBlank()) {
        return limitingFactor
    }
    return meaningfulIssue(lastResult?.optString("primary_issue", "") ?: "")
}

private fun meaningfulIssue(value: String): String {
    val issue = normalized(value)
    return when (issue) {
        "", "none", "steady", "stable", "good", "ok", "healthy" -> ""
        else -> issue
    }
}

private fun preferenceLabel(preference: String): String {
    return when (preference) {
        "quality" -> "Prefer Quality"
        "high_fps" -> "Prefer High FPS"
        "stability" -> "Prefer Stability"
        else -> "Auto"
    }
}

private fun selectedLabelFromState(state: String): String {
    return when (state) {
        "recovering" -> "Recovery"
        "trial" -> "High FPS Trial"
        "blocked" -> "Holding"
        "learning" -> "Learning"
        "stable" -> "Quality"
        else -> "Profile"
    }
}

private fun issueLabel(issue: String): String {
    return when (normalized(issue)) {
        "host_render", "host_render_limited" -> "Host render"
        "decoder", "decoder_path" -> "Decoder path"
        "network" -> "Network"
        "encoder" -> "Encoder"
        "pacing", "frame_pacing" -> "Frame pacing"
        else -> issue.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun relativeAge(updatedAtSeconds: Long, nowSeconds: Long): String {
    val deltaSeconds = (nowSeconds - updatedAtSeconds).coerceAtLeast(0L)
    return when {
        deltaSeconds < 60L -> "just now"
        deltaSeconds < 3600L -> {
            val minutes = deltaSeconds / 60L
            "$minutes min ago"
        }
        deltaSeconds < 86_400L -> {
            val hours = deltaSeconds / 3600L
            "$hours hr ago"
        }
        else -> {
            val days = deltaSeconds / 86_400L
            "$days d ago"
        }
    }
}

private fun parseDisplayModeFps(displayMode: String?): Double {
    if (displayMode.isNullOrBlank()) return 0.0
    val parts = displayMode.split("x")
    if (parts.size < 3) return 0.0
    return parts[2].toDoubleOrNull() ?: 0.0
}

private fun firstPositive(vararg values: Double): Double {
    return values.firstOrNull { it > 0.0 } ?: 0.0
}

private fun normalized(value: String): String {
    return value.trim().lowercase(Locale.US)
}

private fun formatFps(fps: Double): String {
    val rounded = round(fps)
    return if (abs(fps - rounded) < 0.01) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", fps)
    }
}
