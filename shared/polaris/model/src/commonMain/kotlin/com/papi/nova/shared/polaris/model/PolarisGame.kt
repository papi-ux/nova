package com.papi.nova.shared.polaris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolarisGame(
    @SerialName("id") val id: String = "",
    @SerialName("app_id") val appId: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("source") val source: String = "other",
    @SerialName("launcher_source") val launcherSource: String = source,
    @SerialName("launcher_detail") val launcherDetail: String = "",
    @SerialName("platform") val platform: String = "unknown",
    @SerialName("runtime") val runtime: String = "unknown",
    @SerialName("platform_label") val platformLabelFromServer: String = "",
    @SerialName("runtime_label") val runtimeLabelFromServer: String = "",
    @SerialName("steam_appid") val steamAppid: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("installed") val installed: Boolean = true,
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("last_launched") val lastLaunched: Long = 0,
    @SerialName("mangohud") val mangohud: Boolean = false,
    @SerialName("hdr_supported") val hdrSupported: Boolean = false,
    @SerialName("launch_mode") val launchMode: LaunchModeContract? = null,
    @SerialName("steam_launch") val steamLaunch: SteamLaunchContract? = null
) {
    @Serializable
    data class LaunchModeContract(
        @SerialName("preferred_mode") val preferredMode: String = "",
        @SerialName("recommended_mode") val recommendedMode: String = "",
        @SerialName("allowed_modes") val allowedModes: List<String> = emptyList(),
        @SerialName("mode_reason") val modeReason: String = ""
    ) {
        fun allows(mode: String): Boolean = allowedModes.contains(normalizeLaunchMode(mode))
    }

    @Serializable
    data class SteamLaunchContract(
        @SerialName("available") val available: Boolean = false,
        @SerialName("mode") val mode: String = "direct",
        @SerialName("recommended_mode") val recommendedMode: String = "direct",
        @SerialName("allowed_modes") val allowedModes: List<String> = emptyList(),
        @SerialName("mode_reason") val modeReason: String = ""
    ) {
        fun allows(mode: String): Boolean = allowedModes.contains(normalizeMode(mode))

        companion object {
            fun normalizeMode(mode: String): String {
                return when (mode.trim().lowercase()) {
                    "big-picture", "big_picture", "bigpicture", "gamepadui" -> "big-picture"
                    else -> "direct"
                }
            }
        }
    }

    @Serializable
    data class LaunchModeChoice(
        val preferredMode: String = "",
        val recommendedMode: String = "",
        val headlessAllowed: Boolean = true,
        val virtualDisplayAllowed: Boolean = true,
        val virtualDisplayUnavailable: Boolean = false,
        val virtualDisplayUnavailableReason: String = "",
        val hostDefaultMode: String = "",
        val hostModeReason: String = ""
    )

    val isSteamBigPicture: Boolean get() = name.equals("Steam Big Picture", ignoreCase = true)
    val effectiveSource: String get() = launcherSource.ifBlank { source }
    val isSteamGame: Boolean get() = effectiveSource.equals("steam", ignoreCase = true) || steamAppid.isNotBlank()
    val isProtonGame: Boolean get() = runtime.equals("proton", ignoreCase = true) || (runtime.equals("unknown", ignoreCase = true) && effectiveSource.equals("steam", ignoreCase = true) && steamAppid.isNotEmpty())
    val hasMangoHudCompatibilityRisk: Boolean get() = isSteamBigPicture || isProtonGame
    val supportsSteamLaunchMode: Boolean get() = isSteamGame && steamLaunch?.available == true
    val steamLaunchMode: String get() = steamLaunch?.mode ?: "direct"
    val steamLaunchUsesBigPicture: Boolean get() = steamLaunchMode == "big-picture"

    val categoryLabel: String get() = when (category) {
        "fast_action" -> "Action"
        "cinematic" -> "Cinematic"
        "desktop" -> "Desktop"
        "vr" -> "VR"
        else -> ""
    }
    val sourceLabel: String get() = when (effectiveSource) {
        "steam" -> "Steam"
        "lutris" -> "Lutris"
        "heroic" -> "Heroic"
        "manual" -> "Manual"
        else -> ""
    }
    val platformLabel: String get() = platformLabelFromServer.ifBlank {
        when (platform) {
            "linux" -> "Linux"
            "windows" -> "Windows"
            "macos" -> "macOS"
            else -> ""
        }
    }
    val runtimeLabel: String get() = runtimeLabelFromServer.ifBlank {
        when (runtime) {
            "native" -> "Native"
            "proton" -> "Proton"
            "wine" -> "Wine"
            "steam" -> "Steam"
            "umu" -> "UMU"
            else -> ""
        }
    }
    val sourceRuntimeLabel: String get() {
        val parts = mutableListOf<String>()
        if (sourceLabel.isNotEmpty()) parts += sourceLabel
        if (platformLabel.isNotEmpty()) parts += platformLabel
        if (runtimeLabel.isNotEmpty() && !parts.any { it.equals(runtimeLabel, ignoreCase = true) }) parts += runtimeLabel
        return parts.joinToString(" · ")
    }

    companion object {
        fun normalizeLaunchMode(mode: String): String {
            return when (mode.trim().lowercase()) {
                "headless", "headless_stream", "desktop_display", "windowed_stream", "host_display" -> "headless"
                "virtual_display", "host_virtual_display" -> "virtual_display"
                else -> mode.trim()
            }
        }

        fun normalizeLaunchModes(modes: List<String>, defaultWhenEmpty: Boolean = false): List<String> {
            val normalizedModes = linkedSetOf<String>()
            modes.map { normalizeLaunchMode(it) }.filter { it.isNotBlank() }.forEach { normalizedModes.add(it) }
            if (defaultWhenEmpty && normalizedModes.isEmpty()) {
                normalizedModes.add("headless")
                normalizedModes.add("virtual_display")
            }
            return normalizedModes.toList()
        }

        fun resolveLaunchMode(mode: String, headlessAllowed: Boolean, virtualDisplayAllowed: Boolean): String {
            return when (normalizeLaunchMode(mode)) {
                "virtual_display" -> when {
                    virtualDisplayAllowed -> "virtual_display"
                    headlessAllowed -> "headless"
                    else -> ""
                }
                "headless" -> when {
                    headlessAllowed -> "headless"
                    virtualDisplayAllowed -> "virtual_display"
                    else -> ""
                }
                else -> when {
                    headlessAllowed -> "headless"
                    virtualDisplayAllowed -> "virtual_display"
                    else -> ""
                }
            }
        }
    }
}
