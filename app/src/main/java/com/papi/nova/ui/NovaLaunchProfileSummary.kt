package com.papi.nova.ui

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

enum class NovaLaunchProfileNoticeTone {
    WARNING,
    HEALTHY
}

private const val HEALTHY_PROFILE_TARGET_TOLERANCE_FPS = 0.5

data class NovaLaunchProfileSummary(
    val primaryLaunchLabel: String,
    val requestedLine: String,
    val selectedLine: String,
    val reasonLine: String,
    val limitingLine: String,
    val noticeDetail: String,
    val noticeRecommendation: String,
    val noticeTone: NovaLaunchProfileNoticeTone,
    val noticeLabel: String,
    val freshnessLine: String,
    val historyLines: List<String>,
    val showRetryHighFps: Boolean,
    val retryHighFpsLabel: String,
    /**
     * What is holding the granted rate below the client's ask, prettified for display
     * ("Held by History Safe Profile"). Blank when nothing is, or when a pin outranks
     * the hold anyway.
     */
    val grantHoldReason: String = ""
)

internal fun buildNovaLaunchProfileSummary(
    optimization: JSONObject?,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
    /** The fps this client actually asked for (its Settings frame rate); 0 = unknown. */
    clientAskedFps: Double = 0.0,
    /** True when Tuning = High FPS is pinning [clientAskedFps] over the host's plan. */
    clientFpsPinned: Boolean = false
): NovaLaunchProfileSummary? {
    if (optimization == null) return null
    val pinnedFps = if (clientFpsPinned && clientAskedFps > 0.0) clientAskedFps else 0.0

    val profileState = optimization.optJSONObject("profile_state")
    val currentProfile = profileState?.optJSONObject("current_profile")
        ?: optimization.optJSONObject("effective_profile")
    val requestedProfile = optimization.optJSONObject("preference_requested_profile")
        ?: optimization.optJSONObject("requested_profile")
    val lastResult = profileState?.optJSONObject("last_result")
    val actions = profileState?.optJSONObject("actions")

    val preference = normalized(optimization.optString("preference", profileState?.optString("preference", "auto") ?: "auto"))
    val preferenceLabel = launchProfileDisplayLabel(
        profileState
            ?.optString("preference_label", "")
            ?.takeIf { it.isNotBlank() }
            ?: preferenceLabel(preference)
    )
    val state = normalized(profileState?.optString("state", "") ?: "")
    val rawSelectedLabel = profileState
        ?.optString("label", "")
        ?.takeIf { it.isNotBlank() }
        ?: selectedLabelFromState(state)
    val trialProfile = optimization.optBoolean("trial_profile", false) ||
        profileState?.optBoolean("trial_profile", false) == true

    val requestedProfileFps = strictPositiveFiniteNumber(requestedProfile, "target_fps")
    val selectedProfileFps = strictPositiveFiniteNumber(currentProfile, "target_fps")
    val requestedFps = firstPositive(
        requestedProfileFps ?: 0.0,
        strictFiniteNumber(optimization, "preference_requested_target_fps") ?: 0.0,
        strictFiniteNumber(optimization, "requested_target_fps") ?: 0.0,
        parseDisplayModeFps(requestedProfile?.optString("display_mode", ""))
    )
    val effectiveFps = firstPositive(
        selectedProfileFps ?: 0.0,
        strictFiniteNumber(optimization, "effective_target_fps") ?: 0.0,
        parseDisplayModeFps(currentProfile?.optString("display_mode", "")),
        parseDisplayModeFps(optimization.optString("display_mode", ""))
    )
    val highFpsRequestSatisfied = preference == "high_fps" &&
        requestedFps > 0.0 &&
        effectiveFps > 0.0 &&
        effectiveFps + 0.5 >= requestedFps
    val selectedLabel = when {
        trialProfile -> "High FPS trial"
        highFpsRequestSatisfied -> "High FPS stream"
        else -> launchProfileDisplayLabel(rawSelectedLabel)
    }

    val primaryLabel = when {
        // A pin outranks whatever the host planned, so the verb states the pin --
        // promising a recovery launch that will not happen is worse than saying less.
        pinnedFps > 0.0 -> "Launch ${formatFps(pinnedFps)} FPS · your pick"
        trialProfile && effectiveFps > 0.0 -> "Try High FPS stream ${formatFps(effectiveFps)} FPS"
        selectedLabel.equals("High FPS stream", ignoreCase = true) && effectiveFps > 0.0 ->
            "Launch High FPS stream ${formatFps(effectiveFps)} FPS"
        selectedLabel.equals("Recovery profile", ignoreCase = true) && effectiveFps > 0.0 ->
            "Launch Recovery profile ${formatFps(effectiveFps)} FPS"
        effectiveFps > 0.0 -> "Launch ${formatFps(effectiveFps)} FPS"
        selectedLabel.isNotBlank() -> "Launch $selectedLabel"
        else -> ""
    }

    val requestedLine = if (requestedFps > 0.0) {
        "Requested: $preferenceLabel / ${formatFps(requestedFps)} FPS"
    } else {
        "Requested: $preferenceLabel"
    }
    // The ask-vs-grant gap, stated where the grant is stated. The client ask is this
    // client's Settings frame rate -- the host's own requested_* fields cannot be
    // trusted to echo it, and the gap between the two is the single fact the old
    // screen never said anywhere.
    val askedGap = pinnedFps <= 0.0 &&
        clientAskedFps > 0.0 &&
        effectiveFps > 0.0 &&
        clientAskedFps > effectiveFps + 0.5
    val selectedLine = when {
        pinnedFps > 0.0 && effectiveFps > 0.0 && pinnedFps > effectiveFps + 0.5 ->
            "Selected: ${formatFps(pinnedFps)} FPS pinned (host offered $selectedLabel / ${formatFps(effectiveFps)} FPS)"
        pinnedFps > 0.0 -> "Selected: ${formatFps(pinnedFps)} FPS pinned"
        askedGap && effectiveFps > 0.0 ->
            "Selected: $selectedLabel / ${formatFps(effectiveFps)} FPS · you asked ${formatFps(clientAskedFps)}"
        effectiveFps > 0.0 -> "Selected: $selectedLabel / ${formatFps(effectiveFps)} FPS"
        else -> "Selected: $selectedLabel"
    }

    val reasonText = profileState
        ?.optString("reason", "")
        ?.takeIf { it.isNotBlank() }
        ?: optimization.optString("reasoning", "").takeIf { it.isNotBlank() }.orEmpty()
    val reportedIssues = diagnosticIssues(optimization, lastResult)
    val reportedIssue = preferredIssueForDisplay(reportedIssues)
    val healthyPerformanceStatus = healthyPerformanceStatus(
        lastResult = lastResult,
        reportedIssues = reportedIssues,
        completeIssueEvidence = hasCompleteDiagnosticIssueEvidence(optimization, lastResult),
        state = state,
        requestedFps = requestedFps,
        effectiveFps = effectiveFps,
        selectedLabel = selectedLabel,
        rawSelectedLabel = rawSelectedLabel,
        trialProfile = trialProfile,
        hasAuthoritativeProfileFps = requestedProfileFps != null && selectedProfileFps != null
    )
    val healthyPerformance = healthyPerformanceStatus != null
    val reasonLine = if (healthyPerformance) {
        "Performance: $healthyPerformanceStatus"
    } else {
        reasonText.takeIf { it.isNotBlank() }?.let { "Reason: $it" }.orEmpty()
    }

    val issue = if (healthyPerformance) "" else reportedIssue
    val limitingLine = issue.takeIf { it.isNotBlank() }?.let { "Limited by: ${novaLaunchIssueLabel(it)}" }.orEmpty()

    val updatedAt = lastResult?.optLong("updated_at", 0L) ?: 0L
    val freshnessLine = when {
        trialProfile -> "One-launch trial; learned recovery remains active unless this launch grades cleanly."
        selectedLabel.startsWith("Recovery", ignoreCase = true) && updatedAt > 0L ->
            "Recovery active from last session · ${relativeAge(updatedAt, nowSeconds)}"
        selectedLabel.startsWith("Recovery", ignoreCase = true) ->
            "Recovery active from last session"
        else -> ""
    }

    val historyLines = buildHistoryLines(lastResult, issue, selectedLabel, healthyPerformanceStatus)
    val highFpsHeldBelowRequest = preference == "high_fps" &&
        requestedFps > 0.0 &&
        effectiveFps > 0.0 &&
        requestedFps > effectiveFps + 0.5
    val preferenceApplied = optimization.optBoolean(
        "preference_applied",
        profileState?.optBoolean("preference_applied", false) ?: false
    )
    // A pin makes the trial pointless: the launch already goes out at the asked rate.
    val showRetryHighFps = pinnedFps <= 0.0 &&
        !trialProfile &&
        highFpsHeldBelowRequest &&
        (
            actions?.optBoolean("can_retry_high_fps", false) == true ||
                (preference == "high_fps" && !preferenceApplied)
        )
    val blockedReason = optimization.optString(
        "preference_blocked_reason",
        profileState?.optString("preference_blocked_reason", "") ?: ""
    )
    val grantHoldReason = when {
        !askedGap -> ""
        blockedReason.isNotBlank() -> "Held by ${novaLaunchIssueLabel(blockedReason)}"
        issue.isNotBlank() -> "Held by ${novaLaunchIssueLabel(issue)}"
        selectedLabel.startsWith("Recovery", ignoreCase = true) -> "Held by the recovery profile"
        else -> ""
    }
    val retryLabel = if (requestedFps > effectiveFps + 0.5) {
        "Try ${formatFps(requestedFps)} FPS once"
    } else {
        "Try High FPS once"
    }
    val noticeDetail = if (healthyPerformance) {
        buildHealthyPerformanceNoticeDetail(lastResult, requireNotNull(healthyPerformanceStatus))
    } else {
        buildNoticeDetail(lastResult, issue)
    }
    val noticeRecommendation = if (healthyPerformance) {
        "No recovery adjustment is needed."
    } else {
        buildNoticeRecommendation(
            state = state,
            requestedFps = requestedFps,
            effectiveFps = effectiveFps,
            showRetryHighFps = showRetryHighFps
        )
    }
    val noticeTone = if (healthyPerformance) {
        NovaLaunchProfileNoticeTone.HEALTHY
    } else {
        NovaLaunchProfileNoticeTone.WARNING
    }

    return NovaLaunchProfileSummary(
        primaryLaunchLabel = primaryLabel,
        requestedLine = requestedLine,
        selectedLine = selectedLine,
        reasonLine = reasonLine,
        limitingLine = limitingLine,
        noticeDetail = noticeDetail,
        noticeRecommendation = noticeRecommendation,
        noticeTone = noticeTone,
        noticeLabel = healthyPerformanceStatus ?: "Heads up",
        freshnessLine = freshnessLine,
        historyLines = historyLines,
        showRetryHighFps = showRetryHighFps,
        retryHighFpsLabel = retryLabel,
        grantHoldReason = grantHoldReason
    )
}

private fun buildHealthyPerformanceNoticeDetail(lastResult: JSONObject?, performanceStatus: String): String {
    if (lastResult == null) return ""
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: return ""
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: return ""
    if (deliveredFps <= 0.0 || targetFps <= 0.0) return ""

    val evidence = mutableListOf(
        "Last stream: ${formatFps(deliveredFps)}/${formatFps(targetFps)} FPS."
    )
    val lowOnePercentFps = strictFiniteNumber(lastResult, "low_1_percent_fps")
    if (lowOnePercentFps != null && lowOnePercentFps > 0.0) {
        evidence += "1% low: ${formatFps(lowOnePercentFps)} FPS."
    }
    val badPacingPct = strictFiniteNumber(lastResult, "frame_pacing_bad_pct")
    if (badPacingPct != null) {
        evidence += "Bad pacing: ${formatFps(badPacingPct)}%."
    }
    evidence += if (performanceStatus == "Target met") {
        "Stream target met."
    } else {
        "Normal gameplay variation."
    }
    return evidence.joinToString(" ")
}

private fun buildNoticeDetail(lastResult: JSONObject?, issue: String): String {
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: 0.0
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: 0.0
    val evidence = if (deliveredFps > 0.0 && targetFps > 0.0) {
        "Last stream: ${formatFps(deliveredFps)}/${formatFps(targetFps)} FPS."
    } else {
        ""
    }
    val impact = when (normalized(issue)) {
        "host_render", "host_render_limited" ->
            "The host missed the stream target, which can cause repeated frames or uneven motion."
        "decoder", "decoder_path" ->
            "The client decoder missed frames, which can cause stutter or uneven motion."
        "network" ->
            "The network path was unstable, which can cause hitching or dropped frames."
        "encoder" ->
            "The host encoder missed frames, which can cause uneven frame delivery."
        "pacing", "frame_pacing" ->
            "Frames arrived unevenly, which can look like judder even when average FPS is high."
        "" -> ""
        else -> "Polaris reported ${novaLaunchIssueLabel(issue)} for the last session."
    }
    return listOf(evidence, impact).filter { it.isNotBlank() }.joinToString(" ")
}

private fun buildNoticeRecommendation(
    state: String,
    requestedFps: Double,
    effectiveFps: Double,
    showRetryHighFps: Boolean
): String {
    val recoveryActive = state == "recovering"
    val retry = if (showRetryHighFps && requestedFps > 0.0) {
        " Try ${formatFps(requestedFps)} FPS once remains available below."
    } else {
        ""
    }
    if (requestedFps > effectiveFps + 0.5 && effectiveFps > 0.0) {
        return if (recoveryActive) {
            "Next launch: ${formatFps(effectiveFps)} FPS Recovery instead of your requested ${formatFps(requestedFps)} FPS because the learned recovery profile is active.$retry"
        } else {
            "Next launch: Nova selected ${formatFps(effectiveFps)} FPS instead of your requested ${formatFps(requestedFps)} FPS.$retry"
        }
    }
    if (recoveryActive && effectiveFps > 0.0) {
        return "Next launch: ${formatFps(effectiveFps)} FPS Recovery remains active. One clean launch can release it, or reset this game profile below."
    }
    return ""
}

private fun buildHistoryLines(
    lastResult: JSONObject?,
    issue: String,
    selectedLabel: String,
    healthyPerformanceStatus: String?
): List<String> {
    if (lastResult == null) return emptyList()

    val lines = mutableListOf<String>()
    val grade = lastResult.optString("grade", "").takeIf { it.isNotBlank() }
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: 0.0
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: 0.0
    if (healthyPerformanceStatus != null && grade != null && deliveredFps > 0.0 && targetFps > 0.0) {
        lines += "Last: grade $grade · $healthyPerformanceStatus at ${formatFps(deliveredFps)}/${formatFps(targetFps)} FPS"
    } else if (grade != null && deliveredFps > 0.0 && targetFps > 0.0) {
        lines += "Last: grade $grade at ${formatFps(deliveredFps)}/${formatFps(targetFps)} FPS"
    } else if (grade != null) {
        lines += "Last: grade $grade"
    }
    if (issue.isNotBlank()) {
        lines += "Issue: ${novaLaunchIssueLabel(issue)}"
    }
    if (selectedLabel.startsWith("Recovery", ignoreCase = true)) {
        lines += "Next: one clean launch can release recovery, or reset this game profile."
    }
    return lines
}

private val healthyContradictionIssues = setOf(
    "host_render",
    "host_render_limited",
    "pacing",
    "frame_pacing"
)

private fun healthyPerformanceStatus(
    lastResult: JSONObject?,
    reportedIssues: List<String>,
    completeIssueEvidence: Boolean,
    state: String,
    requestedFps: Double,
    effectiveFps: Double,
    selectedLabel: String,
    rawSelectedLabel: String,
    trialProfile: Boolean,
    hasAuthoritativeProfileFps: Boolean
): String? {
    if (lastResult == null || !completeIssueEvidence || reportedIssues.isEmpty()) return null
    val issueClasses = reportedIssues.map(::healthyIssueClass)
    if (issueClasses.any { it == null } || issueClasses.distinct().size != 1) return null
    if (trialProfile || state !in setOf("stable", "blocked")) return null
    if (rawSelectedLabel.contains("recovery", ignoreCase = true) ||
        rawSelectedLabel.contains("trial", ignoreCase = true) ||
        selectedLabel.startsWith("Recovery", ignoreCase = true) ||
        selectedLabel.contains("trial", ignoreCase = true)
    ) {
        return null
    }
    if (!hasAuthoritativeProfileFps || !requestedFps.isFinite() || requestedFps <= 0.0 ||
        !effectiveFps.isFinite() || effectiveFps <= 0.0
    ) {
        return null
    }
    if (requestedFps > effectiveFps + 0.5) return null
    if (lastResult.optBoolean("relaunch_recommended", false)) return null
    if (normalized(lastResult.optString("grade", "")) != "a") return null

    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: return null
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: return null
    val lowOnePercentFps = strictFiniteNumber(lastResult, "low_1_percent_fps") ?: return null
    val minFps = strictFiniteNumber(lastResult, "min_fps") ?: return null
    val badPacingPct = strictFiniteNumber(lastResult, "frame_pacing_bad_pct") ?: return null
    if (abs(targetFps - requestedFps) > HEALTHY_PROFILE_TARGET_TOLERANCE_FPS ||
        abs(targetFps - effectiveFps) > HEALTHY_PROFILE_TARGET_TOLERANCE_FPS
    ) {
        return null
    }
    if (deliveredFps <= 0.0 || targetFps < 24.0) return null
    if (deliveredFps / targetFps < 0.95) return null
    if (lowOnePercentFps <= 0.0 || lowOnePercentFps / targetFps < 0.85) return null
    if (minFps <= 0.0 || minFps / targetFps < 0.60) return null
    if (badPacingPct < 0.0 || badPacingPct >= 5.0) return null

    val normalRiskValues = setOf("normal", "low", "none", "steady", "stable", "good", "ok", "healthy")
    for (key in listOf("network_risk", "decoder_risk", "hdr_risk")) {
        if (!lastResult.has(key) || lastResult.isNull(key)) return null
        val risk = lastResult.opt(key) as? String ?: return null
        if (normalized(risk) !in normalRiskValues) return null
    }

    return if (deliveredFps >= targetFps) "Target met" else "Near target"
}

private fun strictFiniteNumber(source: JSONObject?, key: String): Double? {
    val value = source?.opt(key) as? Number ?: return null
    return value.toDouble().takeIf { it.isFinite() }
}

private fun strictPositiveFiniteNumber(source: JSONObject?, key: String): Double? {
    return strictFiniteNumber(source, key)?.takeIf { it > 0.0 }
}

private fun healthyIssueClass(issue: String): String? {
    return when (normalized(issue)) {
        "host_render", "host_render_limited" -> "host_render"
        "pacing", "frame_pacing" -> "frame_pacing"
        else -> null
    }
}

private fun hasCompleteDiagnosticIssueEvidence(
    optimization: JSONObject,
    lastResult: JSONObject?
): Boolean {
    if (!optimization.has("limiting_factor") ||
        lastResult == null ||
        !lastResult.has("primary_issue")
    ) {
        return false
    }
    return meaningfulIssue(optimization.optString("limiting_factor", "")).isNotBlank() &&
        meaningfulIssue(lastResult.optString("primary_issue", "")).isNotBlank()
}

private fun diagnosticIssues(optimization: JSONObject, lastResult: JSONObject?): List<String> {
    return listOf(
        meaningfulIssue(optimization.optString("limiting_factor", "")),
        meaningfulIssue(lastResult?.optString("primary_issue", "") ?: "")
    ).filter { it.isNotBlank() }.distinct()
}

private fun preferredIssueForDisplay(reportedIssues: List<String>): String {
    return reportedIssues.firstOrNull { normalized(it) !in healthyContradictionIssues }
        ?: reportedIssues.firstOrNull().orEmpty()
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
        "quality" -> "Quality profile"
        "high_fps" -> "High FPS stream"
        "stability" -> "Stability profile"
        else -> "Auto"
    }
}

private fun launchProfileDisplayLabel(label: String): String {
    return when {
        label.equals("High FPS", ignoreCase = true) -> "High FPS stream"
        label.equals("Prefer High FPS", ignoreCase = true) -> "High FPS stream"
        label.equals("High FPS profile", ignoreCase = true) -> "High FPS stream"
        label.equals("High FPS Trial", ignoreCase = true) -> "High FPS trial"
        label.equals("Recovery", ignoreCase = true) -> "Recovery profile"
        label.equals("Prefer Quality", ignoreCase = true) -> "Quality profile"
        label.equals("Quality", ignoreCase = true) -> "Quality profile"
        label.equals("Prefer Stability", ignoreCase = true) -> "Stability profile"
        else -> label
    }
}

private fun selectedLabelFromState(state: String): String {
    return when (state) {
        "recovering" -> "Recovery profile"
        "trial" -> "High FPS trial"
        "blocked" -> "Holding"
        "learning" -> "Learning"
        "stable" -> "Quality"
        else -> "Profile"
    }
}

internal fun novaLaunchIssueLabel(issue: String): String {
    return when (normalized(issue)) {
        "host_render", "host_render_limited" -> "Host Render"
        "decoder", "decoder_path" -> "Decoder Path"
        "network" -> "Network"
        "encoder" -> "Encoder"
        "pacing", "frame_pacing" -> "Frame Pacing"
        // These are names of a limiting factor, not sentences about one, so an issue
        // Polaris starts reporting tomorrow should read like the five above rather than
        // capitalising only its first word and sitting oddly beside them.
        else -> issue.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
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
    return parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}

private fun firstPositive(vararg values: Double): Double {
    return values.firstOrNull { it.isFinite() && it > 0.0 } ?: 0.0
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
