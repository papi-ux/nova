package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGame

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
            val showSteamLaunchMode = game.supportsSteamLaunchMode &&
                game.steamLaunch?.allows("direct") == true &&
                game.steamLaunch.allows("big-picture")
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
