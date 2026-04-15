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
    val hdrSupported: Boolean = false
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): PolarisGame {
            val genreList = mutableListOf<String>()
            val genreArr = json.optJSONArray("genres")
            if (genreArr != null) {
                for (i in 0 until genreArr.length()) {
                    genreArr.optString(i)?.let { genreList.add(it) }
                }
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
                hdrSupported = json.optBoolean("hdr_supported", false)
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
