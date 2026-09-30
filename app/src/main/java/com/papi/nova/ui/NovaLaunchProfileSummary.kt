package com.papi.nova.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.papi.nova.R
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * The words the launch summary says, from string resources, in the player's language (N22). They
 * were written into the builder in English, the last of them the Auto preset's Launch label.
 */
internal class NovaLaunchProfileText(private val resources: Resources) {
    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)
}

enum class NovaLaunchProfileNoticeTone {
    WARNING,
    HEALTHY
}

private const val HEALTHY_PROFILE_TARGET_TOLERANCE_FPS = 0.5

/** Refresh presentation after a local choice without changing host preflight authority. */
internal fun NovaGameDetailOptimizationState.withLaunchProfileSummary(
    text: NovaLaunchProfileText,
    launchOptimization: JSONObject?,
    clientAskedFps: Double,
    clientAskedHdr: Boolean? = null,
    spaceName: String = "",
    clientCodecLabel: String? = null,
): NovaGameDetailOptimizationState = copy(
    profileSummary = buildNovaLaunchProfileSummary(
        text,
        launchOptimization,
        clientAskedFps = clientAskedFps,
        clientAskedHdr = clientAskedHdr,
        spaceName = spaceName,
        clientCodecLabel = clientCodecLabel,
    ),
)

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
     * (Held by History Safe Profile). Blank when nothing is, or when a pin outranks
     * the hold anyway.
     */
    val grantHoldReason: String = "",
    /**
     * The topology the host resolved, when that is not the one this client asked for.
     *
     * Desktop is the case it exists for: its own semantics are the desktop, so Polaris resolves a
     * Host Virtual Display request into Mirror Desktop and says so. The page used to show the
     * request, so it promised a new screen and then took over the one that was there.
     * Blank when the host resolved exactly what was asked, which is every ordinary launch.
     */
    val resolvedTopologyLabel: String = "",
    /** Resolved preset name and plain-language origin shown by Play Setup. */
    val profileLabel: String = "",
    val profileDescription: String = "",
    /** How the last session went, the first of [historyLines] when there was one. */
    val lastSessionLine: String = "",
    /**
     * What [limitingLine] names, alone ("Network"), so a line that says what else limits the
     * launch can say "Limited by" once for both (#10). Blank when [limitingLine] is.
     */
    val limitingReason: String = "",
)

internal fun buildNovaLaunchProfileSummary(
    text: NovaLaunchProfileText,
    optimization: JSONObject?,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
    /** The fps this client actually asked for (its Settings frame rate); 0 = unknown. */
    clientAskedFps: Double = 0.0,
    /** True when Tuning = High FPS is pinning [clientAskedFps] over the host's plan. */
    clientFpsPinned: Boolean = false,
    /**
     * Whether this client's own Settings toggle asks for HDR; null when the caller does not
     * know. "SDR" alone hid the difference between a host that refused HDR and a client that
     * never asked, which is where someone who has fixed everything on the host ends up.
     */
    clientAskedHdr: Boolean? = null,
    /** The Space this game opens in, for a launch the host answered with its Space contract. */
    spaceName: String = "",
    /** Client codec choice for display only; never edits the authenticated host profile. */
    clientCodecLabel: String? = null,
): NovaLaunchProfileSummary? {
    if (optimization == null) return null
    // A choice made here composed onto no host plan, as while the plan is rechecked, is no plan:
    // it read "Profile / 120 FPS" and dropped Launch's preset (in-game smoke #18).
    if (optimization.optString("source", "") == NovaLaunchStreamOverride.UNVERIFIED_SOURCE) return null
    val pinnedFps = if (clientFpsPinned && clientAskedFps > 0.0) clientAskedFps else 0.0
    if (optimization.optString("source", "").equals("deterministic_preset_v1", ignoreCase = true)) {
        return buildDeterministicLaunchPresetSummary(text, optimization, pinnedFps, clientAskedFps, clientAskedHdr, clientCodecLabel)
    }
    if (optimization.optString("source", "").equals(SPACE_LAUNCH_SOURCE, ignoreCase = true)) {
        return buildSpaceLaunchSummary(text, optimization, spaceName, clientAskedFps, clientAskedHdr)
    }

    val profileState = optimization.optJSONObject("profile_state")
    val currentProfile = profileState?.optJSONObject("current_profile")
        ?: optimization.optJSONObject("effective_profile")
    val requestedProfile = optimization.optJSONObject("preference_requested_profile")
        ?: optimization.optJSONObject("requested_profile")
    val lastResult = profileState?.optJSONObject("last_result")
    val actions = profileState?.optJSONObject("actions")

    val preference = normalized(optimization.optString("preference", profileState?.optString("preference", "auto") ?: "auto"))
    val preferenceLabel = (
        profileState
            ?.optString("preference_label", "")
            ?.takeIf { it.isNotBlank() }
            ?.let(::hostProfileName)
            ?: ProfileName.Known(preferenceKind(preference))
        ).say(text)
    val state = normalized(profileState?.optString("state", "") ?: "")
    // The host's own label when it gave one, else what its state means; each is who the profile
    // is, and only [ProfileName.say] turns it into words.
    val hostLabel = profileState?.optString("label", "")?.takeIf { it.isNotBlank() }
    val rawSelected = hostLabel?.let(::hostProfileName) ?: ProfileName.Known(stateKind(state))
    val rawIsRecovery = hostLabel?.contains("recovery", ignoreCase = true) ?: (state == "recovering")
    val rawIsTrial = hostLabel?.contains("trial", ignoreCase = true) ?: (state == "trial")
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
    val selected = when {
        trialProfile -> ProfileName.Known(ProfileKind.HIGH_FPS_TRIAL)
        highFpsRequestSatisfied -> ProfileName.Known(ProfileKind.HIGH_FPS_STREAM)
        else -> rawSelected
    }
    val selectedLabel = selected.say(text)
    val selectedIsRecovery = selected.isRecovery()
    val selectedIsTrial = selected.isTrial()

    val primaryLabel = when {
        // A pin outranks whatever the host planned, so the verb states the pin --
        // promising a recovery launch that will not happen is worse than saying less.
        pinnedFps > 0.0 -> text.get(R.string.nova_launch_plan_label_pinned, formatFps(pinnedFps))
        trialProfile && effectiveFps > 0.0 -> text.get(R.string.nova_launch_plan_label_trial, formatFps(effectiveFps))
        selected == ProfileName.Known(ProfileKind.HIGH_FPS_STREAM) && effectiveFps > 0.0 ->
            text.get(R.string.nova_launch_plan_label_high_fps, formatFps(effectiveFps))
        selected == ProfileName.Known(ProfileKind.RECOVERY) && effectiveFps > 0.0 ->
            text.get(R.string.nova_launch_plan_label_recovery, formatFps(effectiveFps))
        effectiveFps > 0.0 -> text.get(R.string.nova_launch_plan_label_fps, formatFps(effectiveFps))
        selectedLabel.isNotBlank() -> text.get(R.string.nova_launch_plan_label_named, selectedLabel)
        else -> ""
    }

    val requestedLine = if (requestedFps > 0.0) {
        text.get(R.string.nova_launch_plan_requested_fps, preferenceLabel, formatFps(requestedFps))
    } else {
        text.get(R.string.nova_launch_plan_requested, preferenceLabel)
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
        pinnedFps > 0.0 && effectiveFps > 0.0 && pinnedFps > effectiveFps + 0.5 -> text.get(
            R.string.nova_launch_plan_selected_pinned_over,
            formatFps(pinnedFps),
            selectedLabel,
            formatFps(effectiveFps),
        )
        pinnedFps > 0.0 -> text.get(R.string.nova_launch_plan_selected_pinned, formatFps(pinnedFps))
        askedGap && effectiveFps > 0.0 -> text.get(
            R.string.nova_launch_plan_selected_asked_gap,
            selectedLabel,
            formatFps(effectiveFps),
            formatFps(clientAskedFps),
        )
        effectiveFps > 0.0 -> text.get(R.string.nova_launch_plan_selected_fps, selectedLabel, formatFps(effectiveFps))
        else -> text.get(R.string.nova_launch_plan_selected, selectedLabel)
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
        selectedIsRecovery = selectedIsRecovery,
        selectedIsTrial = selectedIsTrial,
        rawIsRecovery = rawIsRecovery,
        rawIsTrial = rawIsTrial,
        trialProfile = trialProfile,
        hasAuthoritativeProfileFps = requestedProfileFps != null && selectedProfileFps != null
    )
    val healthyPerformance = healthyPerformanceStatus != null
    val healthyStatusLabel = healthyPerformanceStatus?.say(text)
    val reasonLine = if (healthyStatusLabel != null) {
        text.get(R.string.nova_launch_plan_performance, healthyStatusLabel)
    } else {
        reasonText.takeIf { it.isNotBlank() }?.let { text.get(R.string.nova_launch_plan_reason, it) }.orEmpty()
    }

    val issue = if (healthyPerformance) "" else reportedIssue
    val limitingReason = issue.takeIf { it.isNotBlank() }?.let { novaLaunchIssueLabel(it, text) }.orEmpty()
    val limitingLine = limitingReason.takeIf { it.isNotBlank() }
        ?.let { text.get(R.string.nova_launch_plan_limited_by, it) }
        .orEmpty()

    val updatedAt = lastResult?.optLong("updated_at", 0L) ?: 0L
    val freshnessLine = when {
        trialProfile -> text.get(R.string.nova_launch_plan_trial_freshness)
        selectedIsRecovery && updatedAt > 0L ->
            text.get(R.string.nova_launch_plan_recovery_active_age, relativeAge(text, updatedAt, nowSeconds))
        selectedIsRecovery -> text.get(R.string.nova_launch_plan_recovery_active)
        else -> ""
    }

    val historyLines = buildHistoryLines(text, lastResult, issue, selectedIsRecovery, healthyStatusLabel)
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
        blockedReason.isNotBlank() -> text.get(R.string.nova_launch_plan_held_by, novaLaunchIssueLabel(blockedReason, text))
        issue.isNotBlank() -> text.get(R.string.nova_launch_plan_held_by, novaLaunchIssueLabel(issue, text))
        selectedIsRecovery -> text.get(R.string.nova_launch_plan_held_by_recovery)
        else -> ""
    }
    val retryLabel = if (requestedFps > effectiveFps + 0.5) {
        text.get(R.string.nova_launch_plan_retry_fps, formatFps(requestedFps))
    } else {
        text.get(R.string.nova_launch_plan_retry_high_fps)
    }
    val noticeDetail = if (healthyPerformance) {
        buildHealthyPerformanceNoticeDetail(text, lastResult, requireNotNull(healthyPerformanceStatus))
    } else {
        buildNoticeDetail(text, lastResult, issue)
    }
    val noticeRecommendation = if (healthyPerformance) {
        text.get(R.string.nova_launch_plan_no_recovery_needed)
    } else {
        buildNoticeRecommendation(
            text = text,
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
        noticeLabel = healthyStatusLabel ?: text.get(R.string.nova_launch_plan_heads_up),
        freshnessLine = freshnessLine,
        historyLines = historyLines.lines,
        showRetryHighFps = showRetryHighFps,
        retryHighFpsLabel = retryLabel,
        grantHoldReason = grantHoldReason,
        lastSessionLine = historyLines.lastSession,
        limitingReason = limitingReason,
    )
}

private fun buildDeterministicLaunchPresetSummary(
    text: NovaLaunchProfileText,
    optimization: JSONObject,
    pinnedFps: Double,
    clientAskedFps: Double,
    clientAskedHdr: Boolean? = null,
    clientCodecLabel: String? = null,
): NovaLaunchProfileSummary? {
    val resolved = optimization.optJSONObject("resolved_profile") ?: return null
    if (resolved.optInt("policy_version", 0) != 1) return null
    val fields = resolved.optJSONObject("fields") ?: return null

    val preset = normalized(resolved.optString("preset", optimization.optString("preset", "auto")))
    val presetLabel = resolved.optString("preset_label", "").takeIf { it.isNotBlank() }
        ?: ProfileName.Known(preferenceKind(preset)).say(text)
    val resolvedFields = resolvedLaunchFields(text, fields, clientAskedHdr, clientCodecLabel)
    val resolvedFps = resolvedFields.fps
    val effectiveFps = if (pinnedFps > 0.0) pinnedFps else resolvedFps
    val selectedParts = resolvedFields.parts
    val hdrNotRequested = resolvedFields.hdrNotRequested

    // Only when the host changed it. Repeating the request back would be noise, and a host that
    // resolved exactly what was asked has nothing to add.
    val topology = optimization.optJSONObject("topology_resolution")
    val requestedTopology = topology?.optString("requested").orEmpty()
    val resolvedTopology = topology?.optString("resolved").orEmpty()
    val resolvedTopologyLabel = if (
        resolvedTopology.isNotBlank() && !resolvedTopology.equals(requestedTopology, ignoreCase = true)
    ) {
        com.papi.nova.api.PolarisClientSettings.labelForMode(resolvedTopology)
    } else {
        ""
    }

    val profileDescription = text.get(R.string.nova_launch_plan_preset_description)
    return NovaLaunchProfileSummary(
        // Auto names no preset a player chose, and "Launch Auto · 120 FPS" read as launching
        // something called Auto (N22): it says the rate alone.
        primaryLaunchLabel = when {
            preset == "auto" && effectiveFps > 0.0 -> text.get(R.string.nova_launch_plan_label_at_fps, formatFps(effectiveFps))
            preset == "auto" -> text.get(R.string.nova_launch_plan_label_plain)
            effectiveFps > 0.0 -> text.get(R.string.nova_launch_plan_label_preset_fps, presetLabel, formatFps(effectiveFps))
            else -> text.get(R.string.nova_launch_plan_label_named, presetLabel)
        },
        requestedLine = if (clientAskedFps > 0.0) {
            text.get(R.string.nova_launch_plan_requested_asked, presetLabel, formatFps(clientAskedFps))
        } else {
            text.get(R.string.nova_launch_plan_requested, presetLabel)
        },
        selectedLine = text.get(R.string.nova_launch_plan_resolved, selectedParts.joinToString(" · ").ifBlank { presetLabel }),
        reasonLine = text.get(R.string.nova_launch_plan_preset_reason),
        limitingLine = "",
        noticeDetail = profileDescription,
        noticeRecommendation = if (hdrNotRequested) {
            text.get(R.string.nova_launch_plan_preset_hdr_off)
        } else {
            text.get(R.string.nova_launch_plan_preset_doctor)
        },
        noticeTone = NovaLaunchProfileNoticeTone.HEALTHY,
        noticeLabel = text.get(R.string.nova_launch_plan_preset_label),
        freshnessLine = text.get(R.string.nova_launch_plan_preset_freshness),
        historyLines = emptyList(),
        showRetryHighFps = false,
        retryHighFpsLabel = "",
        grantHoldReason = "",
        resolvedTopologyLabel = resolvedTopologyLabel,
        profileLabel = presetLabel,
        profileDescription = profileDescription
    )
}

/** The source a host's Space launch answer carries, from its worker contract. */
private const val SPACE_LAUNCH_SOURCE = "worker_profile_v1"

/**
 * A Space launch, which the host answers with its Space contract rather than a quality profile.
 *
 * The host resolves a Space stream from the device and the Space's own limits, states them as
 * locked fields, and sends no profile state at all. That is how this used to fall through to the
 * generic path, where the missing state became "Profile" and Play Setup printed "Granted:
 * Profile". A Space is named by its name, and the stream by what was resolved. What was asked
 * earns a line only when the Space granted less, and nothing here is labelled a profile, because
 * Play Setup draws a profile label under a Profile key.
 */
private fun buildSpaceLaunchSummary(
    text: NovaLaunchProfileText,
    optimization: JSONObject,
    spaceName: String,
    clientAskedFps: Double,
    clientAskedHdr: Boolean?,
): NovaLaunchProfileSummary {
    val space = spaceName.trim().ifBlank { text.get(R.string.nova_launch_plan_your_space) }
    val fields = optimization.optJSONObject("resolved_profile")
        ?.takeIf { it.optInt("policy_version", 0) == 1 }
        ?.optJSONObject("fields")
    val resolved = fields?.let { resolvedLaunchFields(text, it, clientAskedHdr) }
    val fps = resolved?.fps ?: 0.0
    val parts = resolved?.parts.orEmpty()
    val reasoning = optimization.optString("reasoning", "").trim()
    val askedMore = clientAskedFps > 0.0 && fps > 0.0 && clientAskedFps > fps + 0.5
    return NovaLaunchProfileSummary(
        primaryLaunchLabel = if (fps > 0.0) {
            text.get(R.string.nova_launch_plan_label_space_fps, space, formatFps(fps))
        } else {
            text.get(R.string.nova_launch_plan_label_space, space)
        },
        requestedLine = if (askedMore) text.get(R.string.nova_launch_plan_requested_space, formatFps(clientAskedFps)) else "",
        selectedLine = if (parts.isNotEmpty()) text.get(R.string.nova_launch_plan_resolved, parts.joinToString(" · ")) else "",
        reasonLine = reasoning.takeIf { it.isNotBlank() }?.let { text.get(R.string.nova_launch_plan_reason, it) }.orEmpty(),
        limitingLine = "",
        noticeDetail = reasoning,
        noticeRecommendation = "",
        noticeTone = NovaLaunchProfileNoticeTone.HEALTHY,
        noticeLabel = text.get(R.string.nova_launch_plan_space_label),
        freshnessLine = "",
        historyLines = emptyList(),
        showRetryHighFps = false,
        retryHighFpsLabel = "",
        grantHoldReason = "",
        profileLabel = "",
        profileDescription = "",
    )
}

/** What a resolved profile's locked fields say, shared by a launch preset and a Space launch. */
private data class ResolvedLaunchFields(
    val parts: List<String>,
    val fps: Double,
    val hdrNotRequested: Boolean,
)

private fun resolvedLaunchFields(
    text: NovaLaunchProfileText,
    fields: JSONObject,
    clientAskedHdr: Boolean?,
    clientCodecLabel: String? = null,
): ResolvedLaunchFields {
    fun detail(name: String): JSONObject? = fields.optJSONObject(name)
    fun value(name: String): Any? = detail(name)?.opt("value")?.takeUnless { it === JSONObject.NULL }

    val displayMode = value("display_mode") as? String ?: ""
    val resolvedFps = (value("target_fps") as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: parseDisplayModeFps(displayMode)
    val selectedParts = mutableListOf<String>()
    val width = (value("display_width") as? Number)?.toInt()?.takeIf { it > 0 }
    val height = (value("display_height") as? Number)?.toInt()?.takeIf { it > 0 }
    if (width != null && height != null && resolvedFps > 0.0) {
        selectedParts += text.get(R.string.nova_launch_plan_size_at_fps, width, height, formatFps(resolvedFps))
    } else if (displayMode.isNotBlank()) {
        selectedParts += displayMode
    }
    (value("target_bitrate_kbps") as? Number)?.toInt()?.takeIf { it > 0 }?.let {
        // "300 Mbps", not "300.0 Mbps": a tenth shows only where there is one.
        selectedParts += text.get(R.string.nova_launch_plan_mbps, if (it % 1000 == 0) "${it / 1000}" else "${it / 1000.0}")
    }
    if (!clientCodecLabel.isNullOrBlank()) {
        selectedParts += text.get(R.string.nova_launch_plan_client_choice, clientCodecLabel)
    } else {
        (value("preferred_codec") as? String)?.takeIf { it.isNotBlank() }?.let {
            selectedParts += it.uppercase(Locale.US)
        }
    }
    // The host resolves hdr and stamps why (reason_code). Say which kind of SDR this is:
    // the client never asked, the host turned it off, or the host encoder cannot. The
    // client's own toggle is the surest signal for the first case; the host's reason
    // covers a client that does not know its own setting.
    val hdrValue = value("hdr") as? Boolean
    val hdrReason = detail("hdr")?.optString("reason_code", "").orEmpty()
    val hdrNotRequested = hdrValue == false &&
        (clientAskedHdr == false || hdrReason == "requested_hdr_setting")
    hdrValue?.let { hdr ->
        selectedParts += text.get(
            when {
                hdr -> R.string.nova_launch_plan_hdr
                hdrNotRequested -> R.string.nova_launch_plan_sdr_not_requested
                hdrReason == "paired_device_hdr_unsupported" || hdrReason == "client_profile_hdr_lock" ->
                    R.string.nova_launch_plan_sdr_host_off
                hdrReason == "host_encoder_hdr_unsupported" -> R.string.nova_launch_plan_sdr_host_encoder
                else -> R.string.nova_launch_plan_sdr
            },
        )
    }
    return ResolvedLaunchFields(selectedParts, resolvedFps, hdrNotRequested)
}

private fun buildHealthyPerformanceNoticeDetail(
    text: NovaLaunchProfileText,
    lastResult: JSONObject?,
    performanceStatus: HealthyStatus,
): String {
    if (lastResult == null) return ""
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: return ""
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: return ""
    if (deliveredFps <= 0.0 || targetFps <= 0.0) return ""

    val evidence = mutableListOf(
        text.get(R.string.nova_launch_plan_last_stream, formatFps(deliveredFps), formatFps(targetFps))
    )
    val lowOnePercentFps = strictFiniteNumber(lastResult, "low_1_percent_fps")
    if (lowOnePercentFps != null && lowOnePercentFps > 0.0) {
        evidence += text.get(R.string.nova_launch_plan_one_percent_low, formatFps(lowOnePercentFps))
    }
    val badPacingPct = strictFiniteNumber(lastResult, "frame_pacing_bad_pct")
    if (badPacingPct != null) {
        evidence += text.get(R.string.nova_launch_plan_bad_pacing, formatFps(badPacingPct))
    }
    evidence += text.get(
        if (performanceStatus == HealthyStatus.TARGET_MET) {
            R.string.nova_launch_plan_stream_target_met
        } else {
            R.string.nova_launch_plan_normal_variation
        },
    )
    return evidence.joinToString(" ")
}

private fun buildNoticeDetail(text: NovaLaunchProfileText, lastResult: JSONObject?, issue: String): String {
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: 0.0
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: 0.0
    val evidence = if (deliveredFps > 0.0 && targetFps > 0.0) {
        text.get(R.string.nova_launch_plan_last_stream, formatFps(deliveredFps), formatFps(targetFps))
    } else {
        ""
    }
    val impact = when (normalized(issue)) {
        "host_render", "host_render_limited" -> text.get(R.string.nova_launch_plan_impact_host_render)
        "decoder", "decoder_path" -> text.get(R.string.nova_launch_plan_impact_decoder)
        "network" -> text.get(R.string.nova_launch_plan_impact_network)
        "encoder" -> text.get(R.string.nova_launch_plan_impact_encoder)
        "pacing", "frame_pacing" -> text.get(R.string.nova_launch_plan_impact_pacing)
        "" -> ""
        else -> text.get(R.string.nova_launch_plan_impact_other, novaLaunchIssueLabel(issue, text))
    }
    return listOf(evidence, impact).filter { it.isNotBlank() }.joinToString(" ")
}

private fun buildNoticeRecommendation(
    text: NovaLaunchProfileText,
    state: String,
    requestedFps: Double,
    effectiveFps: Double,
    showRetryHighFps: Boolean
): String {
    val recoveryActive = state == "recovering"
    val retry = if (showRetryHighFps && requestedFps > 0.0) {
        text.get(R.string.nova_launch_plan_retry_remains, formatFps(requestedFps))
    } else {
        ""
    }
    if (requestedFps > effectiveFps + 0.5 && effectiveFps > 0.0) {
        val next = if (recoveryActive) {
            text.get(R.string.nova_launch_plan_next_recovery_instead, formatFps(effectiveFps), formatFps(requestedFps))
        } else {
            text.get(R.string.nova_launch_plan_next_selected_instead, formatFps(effectiveFps), formatFps(requestedFps))
        }
        return listOf(next, retry).filter { it.isNotBlank() }.joinToString(" ")
    }
    if (recoveryActive && effectiveFps > 0.0) {
        return text.get(R.string.nova_launch_plan_next_recovery_remains, formatFps(effectiveFps))
    }
    return ""
}

/** How the last session went, and the lines that follow from it, the first said on its own too. */
private data class HistoryLines(val lines: List<String>, val lastSession: String)

private fun buildHistoryLines(
    text: NovaLaunchProfileText,
    lastResult: JSONObject?,
    issue: String,
    selectedIsRecovery: Boolean,
    healthyPerformanceStatus: String?
): HistoryLines {
    if (lastResult == null) return HistoryLines(emptyList(), "")

    val grade = lastResult.optString("grade", "").takeIf { it.isNotBlank() }
    val deliveredFps = strictFiniteNumber(lastResult, "delivered_fps") ?: 0.0
    val targetFps = strictFiniteNumber(lastResult, "target_fps") ?: 0.0
    val lastSession = when {
        healthyPerformanceStatus != null && grade != null && deliveredFps > 0.0 && targetFps > 0.0 -> text.get(
            R.string.nova_launch_plan_last_grade_status,
            grade,
            healthyPerformanceStatus,
            formatFps(deliveredFps),
            formatFps(targetFps),
        )
        grade != null && deliveredFps > 0.0 && targetFps > 0.0 ->
            text.get(R.string.nova_launch_plan_last_grade_fps, grade, formatFps(deliveredFps), formatFps(targetFps))
        grade != null -> text.get(R.string.nova_launch_plan_last_grade, grade)
        else -> ""
    }
    val lines = mutableListOf<String>()
    if (lastSession.isNotBlank()) lines += lastSession
    if (issue.isNotBlank()) {
        lines += text.get(R.string.nova_launch_plan_issue, novaLaunchIssueLabel(issue, text))
    }
    if (selectedIsRecovery) {
        lines += text.get(R.string.nova_launch_plan_next_release)
    }
    return HistoryLines(lines, lastSession)
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
    selectedIsRecovery: Boolean,
    selectedIsTrial: Boolean,
    rawIsRecovery: Boolean,
    rawIsTrial: Boolean,
    trialProfile: Boolean,
    hasAuthoritativeProfileFps: Boolean
): HealthyStatus? {
    if (lastResult == null || !completeIssueEvidence || reportedIssues.isEmpty()) return null
    val issueClasses = reportedIssues.map(::healthyIssueClass)
    if (issueClasses.any { it == null } || issueClasses.distinct().size != 1) return null
    if (trialProfile || state !in setOf("stable", "blocked")) return null
    if (rawIsRecovery || rawIsTrial || selectedIsRecovery || selectedIsTrial) {
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

    return if (deliveredFps >= targetFps) HealthyStatus.TARGET_MET else HealthyStatus.NEAR_TARGET
}

/** A last session that met its target, or came near it, with nothing to recover from. */
private enum class HealthyStatus { TARGET_MET, NEAR_TARGET }

private fun HealthyStatus.say(text: NovaLaunchProfileText): String = text.get(
    when (this) {
        HealthyStatus.TARGET_MET -> R.string.nova_launch_plan_target_met
        HealthyStatus.NEAR_TARGET -> R.string.nova_launch_plan_near_target
    },
)

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

/** The profiles Nova names in its own words. */
private enum class ProfileKind {
    HIGH_FPS_STREAM,
    HIGH_FPS_TRIAL,
    RECOVERY,
    QUALITY,
    STABILITY,
    AUTO,
    HOLDING,
    LEARNING,
    GENERIC,
}

/**
 * A profile's name: one Nova knows, said in the player's language, or the host's own words for
 * one it does not. What the plan decides turns on which profile it is, never on how it is said.
 */
private sealed interface ProfileName {
    data class Known(val kind: ProfileKind) : ProfileName
    data class Host(val words: String) : ProfileName
}

private fun ProfileName.say(text: NovaLaunchProfileText): String = when (this) {
    is ProfileName.Host -> words
    is ProfileName.Known -> text.get(
        when (kind) {
            ProfileKind.HIGH_FPS_STREAM -> R.string.nova_launch_plan_profile_high_fps_stream
            ProfileKind.HIGH_FPS_TRIAL -> R.string.nova_launch_plan_profile_high_fps_trial
            ProfileKind.RECOVERY -> R.string.nova_launch_plan_profile_recovery
            ProfileKind.QUALITY -> R.string.nova_launch_plan_profile_quality
            ProfileKind.STABILITY -> R.string.nova_launch_plan_profile_stability
            ProfileKind.AUTO -> R.string.nova_launch_plan_profile_auto
            ProfileKind.HOLDING -> R.string.nova_launch_plan_profile_holding
            ProfileKind.LEARNING -> R.string.nova_launch_plan_profile_learning
            ProfileKind.GENERIC -> R.string.nova_launch_plan_profile_generic
        },
    )
}

private fun ProfileName.isRecovery(): Boolean = when (this) {
    is ProfileName.Known -> kind == ProfileKind.RECOVERY
    is ProfileName.Host -> words.startsWith("Recovery", ignoreCase = true)
}

private fun ProfileName.isTrial(): Boolean = when (this) {
    is ProfileName.Known -> kind == ProfileKind.HIGH_FPS_TRIAL
    is ProfileName.Host -> words.contains("trial", ignoreCase = true)
}

/** The profile a Tuning preference asks for. */
private fun preferenceKind(preference: String): ProfileKind = when (preference) {
    "quality" -> ProfileKind.QUALITY
    "high_fps" -> ProfileKind.HIGH_FPS_STREAM
    "stability" -> ProfileKind.STABILITY
    else -> ProfileKind.AUTO
}

/** The profile the host's state means, when the host gave no label of its own. */
private fun stateKind(state: String): ProfileKind = when (state) {
    "recovering" -> ProfileKind.RECOVERY
    "trial" -> ProfileKind.HIGH_FPS_TRIAL
    "blocked" -> ProfileKind.HOLDING
    "learning" -> ProfileKind.LEARNING
    "stable" -> ProfileKind.QUALITY
    else -> ProfileKind.GENERIC
}

/**
 * A label from the host, which speaks English, read as the profile Nova knows it as where it is
 * one, so it is said in the player's language; any other label is the host's own words.
 */
private fun hostProfileName(label: String): ProfileName {
    fun isAny(vararg names: String) = names.any { label.equals(it, ignoreCase = true) }
    return when {
        isAny("High FPS", "Prefer High FPS", "High FPS profile", "High FPS stream") -> ProfileName.Known(ProfileKind.HIGH_FPS_STREAM)
        isAny("High FPS Trial") -> ProfileName.Known(ProfileKind.HIGH_FPS_TRIAL)
        isAny("Recovery", "Recovery profile") -> ProfileName.Known(ProfileKind.RECOVERY)
        isAny("Prefer Quality", "Quality", "Quality profile") -> ProfileName.Known(ProfileKind.QUALITY)
        isAny("Prefer Stability", "Stability profile") -> ProfileName.Known(ProfileKind.STABILITY)
        else -> ProfileName.Host(label)
    }
}

internal fun novaLaunchIssueLabel(issue: String, text: NovaLaunchProfileText): String {
    return when (normalized(issue)) {
        "host_render", "host_render_limited" -> text.get(R.string.nova_launch_plan_issue_host_render)
        "decoder", "decoder_path" -> text.get(R.string.nova_launch_plan_issue_decoder)
        "network" -> text.get(R.string.nova_launch_plan_issue_network)
        "encoder" -> text.get(R.string.nova_launch_plan_issue_encoder)
        "pacing", "frame_pacing" -> text.get(R.string.nova_launch_plan_issue_pacing)
        // These are names of a limiting factor, not sentences about one, so an issue
        // Polaris starts reporting tomorrow should read like the five above rather than
        // capitalising only its first word and sitting oddly beside them.
        else -> issue.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}

private fun relativeAge(text: NovaLaunchProfileText, updatedAtSeconds: Long, nowSeconds: Long): String {
    val deltaSeconds = (nowSeconds - updatedAtSeconds).coerceAtLeast(0L)
    return when {
        deltaSeconds < 60L -> text.get(R.string.nova_launch_plan_age_now)
        deltaSeconds < 3600L -> text.get(R.string.nova_launch_plan_age_minutes, (deltaSeconds / 60L).toInt())
        deltaSeconds < 86_400L -> text.get(R.string.nova_launch_plan_age_hours, (deltaSeconds / 3600L).toInt())
        else -> text.get(R.string.nova_launch_plan_age_days, (deltaSeconds / 86_400L).toInt())
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
