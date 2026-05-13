package com.papi.nova.api

data class PolarisGame(
    val id: String,
    val appId: Int = 0,
    val name: String,
    val source: String = "other",
    val launcherSource: String = source,
    val launcherDetail: String = "",
    val platform: String = "unknown",
    val runtime: String = "unknown",
    val platformLabelFromServer: String = "",
    val runtimeLabelFromServer: String = "",
    val steamAppid: String = "",
    val category: String = "",
    val installed: Boolean = true,
    val coverUrl: String = "",
    val genres: List<String> = emptyList(),
    val lastLaunched: Long = 0,
    val mangohud: Boolean = false,
    val hdrSupported: Boolean = false,
    val launchMode: LaunchModeContract? = null
) {
    data class LaunchModeContract(
        val preferredMode: String = "",
        val recommendedMode: String = "",
        val allowedModes: List<String> = emptyList(),
        val modeReason: String = ""
    ) {
        fun allows(mode: String): Boolean = allowedModes.contains(mode)
    }

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

    fun resolveLaunchModeChoice(
        defaultToVirtualDisplay: Boolean,
        clientSettings: PolarisClientSettings? = null
    ): LaunchModeChoice {
        val contract = launchMode
        val headlessAvailable = modeAvailability(clientSettings, "headless")
        val virtualAvailable = modeAvailability(clientSettings, "virtual_display")
        val headlessAllowed = (contract?.allows("headless") ?: true) && headlessAvailable != false
        val virtualDisplayAllowed =
            (contract?.allows("virtual_display") ?: true) && virtualAvailable != false
        val hostDefaultMode = resolveLaunchMode(
            clientSettings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() }
                ?: clientSettings?.effective?.streamDisplayMode
                ?: "",
            headlessAllowed,
            virtualDisplayAllowed
        )

        val fallbackMode = if (defaultToVirtualDisplay && virtualDisplayAllowed) {
            "virtual_display"
        } else {
            "headless"
        }
        val preferredMode = resolveLaunchMode(
            contract?.preferredMode?.takeIf { it.isNotBlank() } ?: fallbackMode,
            headlessAllowed,
            virtualDisplayAllowed
        )
        val recommendedMode = resolveLaunchMode(
            hostDefaultMode.takeIf { it.isNotBlank() }
                ?: contract?.recommendedMode?.takeIf { it.isNotBlank() }
                ?: preferredMode,
            headlessAllowed,
            virtualDisplayAllowed
        )
        val virtualUnavailableReason = modeUnavailableReason(clientSettings, "virtual_display")

        return LaunchModeChoice(
            preferredMode = preferredMode,
            recommendedMode = recommendedMode,
            headlessAllowed = headlessAllowed,
            virtualDisplayAllowed = virtualDisplayAllowed,
            virtualDisplayUnavailable = (contract?.allows("virtual_display") ?: defaultToVirtualDisplay) &&
                virtualAvailable == false,
            virtualDisplayUnavailableReason = virtualUnavailableReason,
            hostDefaultMode = hostDefaultMode,
            hostModeReason = clientSettings?.desired?.streamDisplayModeReason?.takeIf { it.isNotBlank() }
                ?: clientSettings?.effective?.streamDisplayModeReason
                ?: ""
        )
    }

    companion object {
        private val HEADLESS_MODE_ALIASES = setOf(
            "headless",
            "headless_stream",
            "desktop_display",
            "windowed_stream",
            "host_display"
        )
        private val VIRTUAL_DISPLAY_MODE_ALIASES = setOf(
            "virtual_display",
            "host_virtual_display"
        )

        private fun normalizeLaunchMode(mode: String): String {
            return when (mode) {
                "headless", "headless_stream", "desktop_display", "windowed_stream", "host_display" -> "headless"
                "virtual_display", "host_virtual_display" -> "virtual_display"
                else -> mode
            }
        }

        private fun resolveLaunchMode(
            mode: String,
            headlessAllowed: Boolean,
            virtualDisplayAllowed: Boolean
        ): String {
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

        private fun modeAvailability(
            clientSettings: PolarisClientSettings?,
            mode: String
        ): Boolean? {
            val modes = clientSettings?.capabilities?.modes ?: return null
            val aliases = aliasesForMode(mode)
            val matches = modes.filter { it.value in aliases }
            if (matches.isEmpty()) {
                return null
            }
            return matches.any { it.available }
        }

        private fun modeUnavailableReason(
            clientSettings: PolarisClientSettings?,
            mode: String
        ): String {
            val modes = clientSettings?.capabilities?.modes ?: return ""
            val aliases = aliasesForMode(mode)
            return modes.firstOrNull { it.value in aliases && !it.available }
                ?.reason
                .orEmpty()
        }

        private fun aliasesForMode(mode: String): Set<String> {
            return when (normalizeLaunchMode(mode)) {
                "virtual_display" -> VIRTUAL_DISPLAY_MODE_ALIASES
                else -> HEADLESS_MODE_ALIASES
            }
        }

        fun fromJson(json: org.json.JSONObject): PolarisGame {
            val genreList = mutableListOf<String>()
            val genreArr = json.optJSONArray("genres")
            if (genreArr != null) {
                for (i in 0 until genreArr.length()) {
                    genreArr.optString(i)?.let { genreList.add(it) }
                }
            }
            val launchMode = json.optJSONObject("launch_mode")?.let { modeJson ->
                val allowedModes = linkedSetOf<String>()
                val allowedArr = modeJson.optJSONArray("allowed_modes")
                if (allowedArr != null) {
                    for (i in 0 until allowedArr.length()) {
                        allowedArr.optString(i)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { normalizeLaunchMode(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { allowedModes.add(it) }
                    }
                }
                if (allowedModes.isEmpty()) {
                    allowedModes.add("headless")
                    allowedModes.add("virtual_display")
                }
                LaunchModeContract(
                    preferredMode = normalizeLaunchMode(modeJson.optString("preferred_mode", "")),
                    recommendedMode = normalizeLaunchMode(modeJson.optString("recommended_mode", "")),
                    allowedModes = allowedModes.toList(),
                    modeReason = modeJson.optString("mode_reason", "")
                )
            }
            return PolarisGame(
                id = json.optString("id", ""),
                appId = json.optString("app_id", "").toIntOrNull() ?: json.optInt("app_id", 0),
                name = json.optString("name", ""),
                source = json.optString("source", "other"),
                launcherSource = json.optString("launcher_source", json.optString("source", "other")),
                launcherDetail = json.optString("launcher_detail", ""),
                platform = json.optString("platform", "unknown").lowercase(),
                runtime = json.optString("runtime", "unknown").lowercase(),
                platformLabelFromServer = json.optString("platform_label", ""),
                runtimeLabelFromServer = json.optString("runtime_label", ""),
                steamAppid = json.optString("steam_appid", ""),
                category = json.optString("category", ""),
                installed = json.optBoolean("installed", true),
                coverUrl = json.optString("cover_url", ""),
                genres = genreList,
                lastLaunched = json.optLong("last_launched", 0),
                mangohud = json.optBoolean("mangohud", false),
                hdrSupported = json.optBoolean("hdr_supported", false),
                launchMode = launchMode
            )
        }
    }

    val isSteamBigPicture get() = name.equals("Steam Big Picture", ignoreCase = true)
    val effectiveSource get() = launcherSource.ifBlank { source }
    val isProtonGame get() = runtime == "proton" || (runtime == "unknown" && effectiveSource == "steam" && steamAppid.isNotEmpty())
    val hasMangoHudCompatibilityRisk get() = isSteamBigPicture || isProtonGame
    val categoryLabel get() = when (category) {
        "fast_action" -> "Action"
        "cinematic" -> "Cinematic"
        "desktop" -> "Desktop"
        "vr" -> "VR"
        else -> ""
    }
    val sourceLabel get() = when (effectiveSource) {
        "steam" -> "Steam"
        "lutris" -> "Lutris"
        "heroic" -> "Heroic"
        "manual" -> "Manual"
        else -> ""
    }
    val platformLabel get() = platformLabelFromServer.ifBlank {
        when (platform) {
            "linux" -> "Linux"
            "windows" -> "Windows"
            "macos" -> "macOS"
            else -> ""
        }
    }
    val runtimeLabel get() = runtimeLabelFromServer.ifBlank {
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
        if (runtimeLabel.isNotEmpty() && !parts.any { it.equals(runtimeLabel, ignoreCase = true) }) {
            parts += runtimeLabel
        }
        return parts.joinToString(" · ")
    }
}
