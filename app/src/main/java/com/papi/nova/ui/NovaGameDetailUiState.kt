package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.resolveLaunchModeChoice
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject

data class NovaGameDetailUiState(
    val game: PolarisGame,
    val launchChoice: PolarisGame.LaunchModeChoice,
    val preferredMode: String,
    val recommendedMode: String,
    val headlessAllowed: Boolean,
    val virtualDisplayAllowed: Boolean,
    val virtualDisplayUnavailable: Boolean,
    val virtualDisplayUnavailableReason: String,
    val playMode: String,
    val playEnabled: Boolean,
    val playUsesVirtualDisplay: Boolean,
    val launchOptionsEnabled: Boolean,
    val showRecommendedModeBadge: Boolean,
    val profilePreference: String,
    val mangoHudRisk: MangoHudRisk,
    val showSteamLaunchMode: Boolean,
    val steamLaunchMode: String,
    val steamLaunchWarning: Boolean
) {
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
            profilePreference: String
        ): NovaGameDetailUiState {
            val choice = game.resolveLaunchModeChoice(defaultToVirtualDisplay, clientSettings)
            val playMode = when {
                choice.recommendedMode == "virtual_display" && choice.virtualDisplayAllowed -> "virtual_display"
                choice.recommendedMode == "headless" && choice.headlessAllowed -> "headless"
                choice.headlessAllowed -> "headless"
                choice.virtualDisplayAllowed -> "virtual_display"
                else -> ""
            }
            val launchOptionsEnabled = choice.headlessAllowed || choice.virtualDisplayAllowed
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

            return NovaGameDetailUiState(
                game = game,
                launchChoice = choice,
                preferredMode = choice.preferredMode,
                recommendedMode = choice.recommendedMode,
                headlessAllowed = choice.headlessAllowed,
                virtualDisplayAllowed = choice.virtualDisplayAllowed,
                virtualDisplayUnavailable = choice.virtualDisplayUnavailable,
                virtualDisplayUnavailableReason = choice.virtualDisplayUnavailableReason,
                playMode = playMode,
                playEnabled = playMode.isNotBlank(),
                playUsesVirtualDisplay = playMode == "virtual_display",
                launchOptionsEnabled = launchOptionsEnabled,
                showRecommendedModeBadge = launchOptionsEnabled,
                profilePreference = AutoQualityProfilePreferences.normalize(profilePreference),
                mangoHudRisk = mangoHudRisk,
                showSteamLaunchMode = showSteamLaunchMode,
                steamLaunchMode = steamLaunchMode,
                steamLaunchWarning = steamLaunchWarning
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
        fun from(uiState: NovaGameDetailUiState, optimization: JSONObject?): NovaDesktopSteamLaunchDecision {
            val policy = optimization?.optJSONObject("launch_policy")
                ?: optimization?.optJSONObject("launchPolicy")
                ?: return NovaDesktopSteamLaunchDecision()
            if (uiState.playUsesVirtualDisplay) return NovaDesktopSteamLaunchDecision()

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
