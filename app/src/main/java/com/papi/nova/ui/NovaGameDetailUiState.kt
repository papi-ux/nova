package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.api.isLaunchModeAvailable
import com.papi.nova.api.isLaunchModeSessionOverridable
import com.papi.nova.api.launchModeUnavailableReason
import com.papi.nova.api.resolveLaunchModeChoice
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject

/**
 * @brief The host's own profile, or blank when it has not set one.
 *
 * Desired rather than effective: this answers "what would the host do", and effective is
 * what it managed this session, which is a different question and already has its own row.
 */
private fun hostProfileLabel(settings: PolarisClientSettings?): String {
    val desired = settings?.desired ?: return ""
    val mode = desired.displayMode.trim()
    val mbps = desired.targetBitrateKbps.takeIf { it > 0 }?.let { it / 1000 }
    return when {
        mode.isNotBlank() && mbps != null -> "$mode \u00b7 $mbps Mbps"
        mode.isNotBlank() -> mode
        mbps != null -> "$mbps Mbps"
        else -> ""
    }
}

data class NovaGameDetailUiState(
    val game: PolarisGame,
    val launchChoice: PolarisGame.LaunchModeChoice,
    val preferredMode: String,
    val recommendedMode: String,
    /** Whether anything actually recommended it, rather than it echoing the preference. */
    val hasRecommendation: Boolean,
    val headlessAllowed: Boolean,
    val virtualDisplayAllowed: Boolean,
    val virtualDisplayUnavailable: Boolean,
    val virtualDisplayUnavailableReason: String,
    val playMode: String,
    /** Explicit per-game mode, or a safe one-launch replacement for an unavailable host default. */
    val launchStreamMode: String,
    val playEnabled: Boolean,
    val playUsesVirtualDisplay: Boolean,
    val launchOptionsEnabled: Boolean,
    val actionableLaunchModeCount: Int,
    val showLaunchOptionsButton: Boolean,
    val showLaunchModeSummary: Boolean,
    val showVirtualUnavailableHint: Boolean,
    val showRecommendedModeBadge: Boolean,
    val profilePreference: String,
    val mangoHudRisk: MangoHudRisk,
    val showSteamLaunchMode: Boolean,
    val steamLaunchMode: String,
    val steamLaunchWarning: Boolean,
    val hostStreamDisplayMode: String,
    val hostStreamDisplayModeLabel: String,
    val hostStreamDisplayModeUnavailableReason: String,
    /** True only when this client stored a per-game launch-mode override. */
    val hasExplicitOverride: Boolean,
    /**
     * The profile the host would use for anything that does not ask for its own, as
     * "1920x1080@60 · 20 Mbps". Blank when the host has no profile set.
     */
    val hostProfileLabel: String,
) {
    /**
     * Whether this game is running somewhere other than where the host would put it.
     *
     * The per-game choice outranks the host default, which is only legible if both are
     * on screen and the disagreement is named.
     */
    val overridesHostMode: Boolean
        get() = hasExplicitOverride &&
            hostStreamDisplayMode.isNotBlank() &&
            playMode.isNotBlank() &&
            // Normalized on both sides so legacy spellings and canonical ids compare
            // as the same mode. Without hasExplicitOverride this reported an override
            // on every game whenever the host default sat outside the per-game pair.
            PolarisStreamDisplayMode.normalize(playMode) !=
            PolarisStreamDisplayMode.normalize(hostStreamDisplayMode)

    /** The mode Nova will actually send, independent of a stale host-default label. */
    val playModeLabel: String
        get() = PolarisStreamDisplayMode.labelForMode(playMode)

    /** Nova selected an available one-launch replacement for a stale host default. */
    val usesSafeHostFallback: Boolean
        get() = !hasExplicitOverride &&
            launchStreamMode.isNotBlank() &&
            PolarisStreamDisplayMode.normalize(launchStreamMode) !=
            PolarisStreamDisplayMode.normalize(hostStreamDisplayMode)

    enum class MangoHudRisk {
        NONE,
        STEAM,
        BIG_PICTURE
    }

    companion object {
        fun from(
            game: PolarisGame,
            defaultToVirtualDisplay: Boolean,
            clientSettings: PolarisClientSettings?,
            profilePreference: String,
            /** A mode chosen for this game on this client, which outranks the host default. */
            launchModeOverride: String? = null,
        ): NovaGameDetailUiState {
            val choice = game.resolveLaunchModeChoice(defaultToVirtualDisplay, clientSettings)
            // Only a deliberate choice reaches here, so it answers before the host does.
            // The contract's preferredMode stays below the host, as it means the app's own
            // default rather than anyone's decision.
            val hostStreamDisplayMode = PolarisStreamDisplayMode.normalize(
                clientSettings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() }
                    ?: clientSettings?.effective?.streamDisplayMode
            )
            val perGameOverride = launchModeOverride
                ?.takeIf { it.isNotBlank() }
                ?.let(PolarisStreamDisplayMode::normalize)
                ?.takeUnless { it == PolarisClientSettings.MODE_HEADLESS_DONGLE }
                ?.takeIf {
                    game.isLaunchModeAvailable(it, clientSettings) &&
                        clientSettings.isLaunchModeSessionOverridable(it)
                }
            val chosen = perGameOverride
                ?.let { PolarisGame.resolveLaunchMode(it, choice.headlessAllowed, choice.virtualDisplayAllowed) }
            val playMode = when {
                chosen == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY && choice.virtualDisplayAllowed &&
                    !choice.virtualDisplayUnavailable -> PolarisGame.MODE_HOST_VIRTUAL_DISPLAY
                chosen == PolarisGame.MODE_HEADLESS_STREAM && choice.headlessAllowed -> PolarisGame.MODE_HEADLESS_STREAM
                // A deliberate override outside the legacy pair resolves to itself;
                // the picker only offers modes the host catalog advertised.
                !chosen.isNullOrBlank() && chosen != PolarisGame.MODE_HOST_VIRTUAL_DISPLAY &&
                    chosen != PolarisGame.MODE_HEADLESS_STREAM &&
                    game.isLaunchModeAvailable(chosen, clientSettings) -> chosen

                choice.recommendedMode == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY && choice.virtualDisplayAllowed -> PolarisGame.MODE_HOST_VIRTUAL_DISPLAY
                choice.recommendedMode == PolarisGame.MODE_HEADLESS_STREAM && choice.headlessAllowed -> PolarisGame.MODE_HEADLESS_STREAM
                // No override and the host default sits outside the pair: follow the
                // host instead of quietly coercing to headless — this is what used to
                // fire the false "This game overrides it" warning.
                choice.recommendedMode.isNotBlank() &&
                    choice.recommendedMode != PolarisGame.MODE_HOST_VIRTUAL_DISPLAY &&
                    choice.recommendedMode != PolarisGame.MODE_HEADLESS_STREAM &&
                    game.isLaunchModeAvailable(choice.recommendedMode, clientSettings) -> choice.recommendedMode
                choice.headlessAllowed -> PolarisGame.MODE_HEADLESS_STREAM
                choice.virtualDisplayAllowed -> PolarisGame.MODE_HOST_VIRTUAL_DISPLAY
                else -> ""
            }
            val hostDefaultUnavailable = hostStreamDisplayMode.isNotBlank() &&
                choice.hostDefaultMode.isBlank()
            val launchStreamMode = when {
                !perGameOverride.isNullOrBlank() -> playMode
                // A stale host default cannot produce the profile Polaris just
                // recommended. Carry the validated fallback for this launch only;
                // normal host-default launches still send no streamMode.
                hostDefaultUnavailable &&
                    clientSettings.isLaunchModeSessionOverridable(playMode) -> playMode
                else -> ""
            }
            val catalogModes = clientSettings?.capabilities?.modes.orEmpty()
            val actionableModeCandidates = when {
                catalogModes.isNotEmpty() -> catalogModes.map { it.value }
                game.launchMode?.allowedModes.orEmpty().isNotEmpty() -> game.launchMode?.allowedModes.orEmpty()
                else -> listOf(
                    PolarisGame.MODE_HEADLESS_STREAM,
                    PolarisGame.MODE_HOST_VIRTUAL_DISPLAY,
                )
            }
            val actionableLaunchModeCount = actionableModeCandidates
                .map(PolarisGame::normalizeLaunchMode)
                .filter { it.isNotBlank() }
                .distinct()
                .count {
                    game.isLaunchModeAvailable(it, clientSettings) &&
                        clientSettings.isLaunchModeSessionOverridable(it)
                }
            val showVirtualUnavailableHint = choice.virtualDisplayUnavailable &&
                choice.virtualDisplayUnavailableReason.isNotBlank()
            val showLaunchOptionsButton = actionableLaunchModeCount > 1
            val showLaunchModeSummary = actionableLaunchModeCount <= 1 || showVirtualUnavailableHint
            val launchOptionsEnabled = showLaunchOptionsButton
            val mangoHudRisk = when {
                game.isSteamBigPicture -> MangoHudRisk.BIG_PICTURE
                game.hasMangoHudCompatibilityRisk -> MangoHudRisk.STEAM
                else -> MangoHudRisk.NONE
            }
            val steamLaunchMode = game.steamLaunchMode
            val steamLaunch = game.steamLaunch
            val showSteamLaunchMode = game.supportsSteamLaunchMode &&
                steamLaunch?.allows("direct") == true &&
                steamLaunch.allows("big-picture")
            val steamLaunchWarning = steamLaunchMode == "big-picture"
            val hostStreamDisplayModeLabel = PolarisStreamDisplayMode.labelForMode(hostStreamDisplayMode)
            val hostStreamDisplayModeUnavailableReason =
                clientSettings.launchModeUnavailableReason(hostStreamDisplayMode)

            return NovaGameDetailUiState(
                game = game,
                launchChoice = choice,
                preferredMode = choice.preferredMode,
                recommendedMode = choice.recommendedMode,
                // The resolver falls back to the preference when neither the host nor
                // the contract recommends anything, so ask whether either did.
                hasRecommendation = choice.hostDefaultMode.isNotBlank() ||
                    !game.launchMode?.recommendedMode.isNullOrBlank(),
                headlessAllowed = choice.headlessAllowed,
                virtualDisplayAllowed = choice.virtualDisplayAllowed,
                virtualDisplayUnavailable = choice.virtualDisplayUnavailable,
                virtualDisplayUnavailableReason = choice.virtualDisplayUnavailableReason,
                playMode = playMode,
                launchStreamMode = launchStreamMode,
                playEnabled = playMode.isNotBlank(),
                playUsesVirtualDisplay = playMode == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY,
                launchOptionsEnabled = launchOptionsEnabled,
                actionableLaunchModeCount = actionableLaunchModeCount,
                showLaunchOptionsButton = showLaunchOptionsButton,
                showLaunchModeSummary = showLaunchModeSummary,
                showVirtualUnavailableHint = showVirtualUnavailableHint,
                showRecommendedModeBadge = launchOptionsEnabled,
                profilePreference = AutoQualityProfilePreferences.normalize(profilePreference),
                mangoHudRisk = mangoHudRisk,
                showSteamLaunchMode = showSteamLaunchMode,
                steamLaunchMode = steamLaunchMode,
                steamLaunchWarning = steamLaunchWarning,
                hostStreamDisplayMode = hostStreamDisplayMode,
                hostStreamDisplayModeLabel = hostStreamDisplayModeLabel,
                hostStreamDisplayModeUnavailableReason = hostStreamDisplayModeUnavailableReason,
                hasExplicitOverride = !perGameOverride.isNullOrBlank(),
                hostProfileLabel = hostProfileLabel(clientSettings),
            )
        }
    }
}

data class NovaDesktopSteamLaunchDecision(
    val required: Boolean = false,
    val privateStreamEnabled: Boolean = true,
    val mirrorDesktopEnabled: Boolean = true,
    val forcePrivateAfterSteamCloseEnabled: Boolean = false,
    val forcePrivateAfterSteamCloseLabel: String = "Close desktop Steam and start private stream",
    val reason: String = "",
    val privateStreamUnavailableReason: String = ""
) {
    companion object {
        fun from(
            uiState: NovaGameDetailUiState,
            optimization: JSONObject?,
            usesVirtualDisplay: Boolean = uiState.playUsesVirtualDisplay
        ): NovaDesktopSteamLaunchDecision {
            val policy = optimization?.optJSONObject("launch_policy")
                ?: optimization?.optJSONObject("launchPolicy")
                ?: return NovaDesktopSteamLaunchDecision()
            if (usesVirtualDisplay) return NovaDesktopSteamLaunchDecision()

            val desktopSteamActive = policy.optPolicyBoolean("desktop_steam_active", "desktopSteamActive")
            val physicalDisplayRisk = policy.optPolicyBoolean("physical_display_risk", "physicalDisplayRisk")
            if (!desktopSteamActive && !physicalDisplayRisk) return NovaDesktopSteamLaunchDecision()

            val privateSupported = policy.optPolicyBoolean(
                "canLaunchPrivateStream",
                "private_stream_supported",
                "privateStreamSupported",
                default = true
            )
            val mirrorSupported = policy.optPolicyBoolean(
                "canMirrorDesktop",
                "mirror_desktop_supported",
                "mirrorDesktopSupported",
                default = true
            )
            val forcePrivateSupported = policy.optPolicyBoolean(
                "canForceCloseDesktopSteamForPrivateStream",
                "force_private_after_steam_close_supported",
                "forcePrivateAfterSteamCloseSupported",
                default = false
            )
            val forcePrivateLabel = policy.optPolicyString(
                "forcePrivateStreamLabel",
                "force_private_stream_label"
            ).ifBlank { "Close desktop Steam and start private stream" }
            val reason = policy.optPolicyString("reason", "message")
            val privateReason = policy.optPolicyString(
                "private_stream_unavailable_reason",
                "privateStreamUnavailableReason"
            ).ifBlank {
                if (privateSupported) "" else reason.ifBlank { "Private stream is unavailable while desktop Steam is active." }
            }

            return NovaDesktopSteamLaunchDecision(
                required = true,
                privateStreamEnabled = privateSupported,
                mirrorDesktopEnabled = mirrorSupported,
                forcePrivateAfterSteamCloseEnabled = forcePrivateSupported,
                forcePrivateAfterSteamCloseLabel = forcePrivateLabel,
                reason = reason,
                privateStreamUnavailableReason = privateReason
            )
        }

        private fun JSONObject.optPolicyBoolean(
            vararg names: String,
            default: Boolean = false
        ): Boolean {
            for (name in names) {
                if (has(name)) return optBoolean(name, default)
            }
            return default
        }

        private fun JSONObject.optPolicyString(vararg names: String): String {
            for (name in names) {
                val value = optString(name, "")
                if (value.isNotBlank()) return value
            }
            return ""
        }
    }
}
