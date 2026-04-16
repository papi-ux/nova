package com.papi.nova.api

data class PolarisGame(
    val id: String,
    val appId: Int = 0,
    val name: String,
    val source: String = "other",
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

    companion object {
        fun fromJson(json: org.json.JSONObject): PolarisGame {
            val genreList = mutableListOf<String>()
            val genreArr = json.optJSONArray("genres")
            if (genreArr != null) {
                for (i in 0 until genreArr.length()) {
                    genreArr.optString(i)?.let { genreList.add(it) }
                }
            }
            val launchMode = json.optJSONObject("launch_mode")?.let { modeJson ->
                val allowedModes = mutableListOf<String>()
                val allowedArr = modeJson.optJSONArray("allowed_modes")
                if (allowedArr != null) {
                    for (i in 0 until allowedArr.length()) {
                        allowedArr.optString(i)?.takeIf { it.isNotBlank() }?.let { allowedModes.add(it) }
                    }
                }
                LaunchModeContract(
                    preferredMode = modeJson.optString("preferred_mode", ""),
                    recommendedMode = modeJson.optString("recommended_mode", ""),
                    allowedModes = allowedModes,
                    modeReason = modeJson.optString("mode_reason", "")
                )
            }
            return PolarisGame(
                id = json.optString("id", ""),
                appId = json.optString("app_id", "").toIntOrNull() ?: json.optInt("app_id", 0),
                name = json.optString("name", ""),
                source = json.optString("source", "other"),
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
    val isProtonGame get() = source == "steam" && steamAppid.isNotEmpty()
    val hasMangoHudCompatibilityRisk get() = isSteamBigPicture || isProtonGame
    val categoryLabel get() = when (category) {
        "fast_action" -> "Action"
        "cinematic" -> "Cinematic"
        "desktop" -> "Desktop"
        "vr" -> "VR"
        else -> ""
    }
    val sourceLabel get() = when (source) {
        "steam" -> "Steam"
        "lutris" -> "Lutris"
        "heroic" -> "Heroic"
        else -> ""
    }
}
