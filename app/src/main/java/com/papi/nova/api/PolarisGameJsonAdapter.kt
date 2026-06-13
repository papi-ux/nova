package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONArray
import org.json.JSONObject

object PolarisGameJsonAdapter {
    @JvmStatic
    fun fromJson(json: JSONObject): PolarisGame {
        val source = json.optString("source", "other")
        val launchMode = json.optJSONObject("launch_mode")?.let { modeJson ->
            PolarisGame.LaunchModeContract(
                preferredMode = PolarisGame.normalizeLaunchMode(modeJson.optString("preferred_mode", "")),
                recommendedMode = PolarisGame.normalizeLaunchMode(modeJson.optString("recommended_mode", "")),
                allowedModes = PolarisGame.normalizeLaunchModes(fetchStringArray(modeJson.optJSONArray("allowed_modes")), defaultWhenEmpty = true),
                modeReason = modeJson.optString("mode_reason", "")
            )
        }
        val steamLaunch = json.optJSONObject("steam_launch")?.let { launchJson ->
            PolarisGame.SteamLaunchContract(
                available = launchJson.optBoolean("available", false),
                mode = PolarisGame.SteamLaunchContract.normalizeMode(launchJson.optString("mode", "direct")),
                recommendedMode = PolarisGame.SteamLaunchContract.normalizeMode(launchJson.optString("recommended_mode", "direct")),
                allowedModes = normalizeSteamLaunchModes(fetchStringArray(launchJson.optJSONArray("allowed_modes"))),
                modeReason = launchJson.optString("mode_reason", "")
            )
        }

        return PolarisGame(
            id = json.optString("id", ""),
            appId = json.optString("app_id", "").toIntOrNull() ?: json.optInt("app_id", 0),
            name = json.optString("name", ""),
            source = source,
            launcherSource = json.optString("launcher_source", source),
            launcherDetail = json.optString("launcher_detail", ""),
            platform = json.optString("platform", "unknown").lowercase(),
            runtime = json.optString("runtime", "unknown").lowercase(),
            platformLabelFromServer = json.optString("platform_label", ""),
            runtimeLabelFromServer = json.optString("runtime_label", ""),
            steamAppid = json.optString("steam_appid", ""),
            category = json.optString("category", ""),
            installed = json.optBoolean("installed", true),
            coverUrl = json.optString("cover_url", ""),
            genres = fetchStringArray(json.optJSONArray("genres")),
            lastLaunched = json.optLong("last_launched", 0),
            mangohud = json.optBoolean("mangohud", false),
            hdrSupported = json.optBoolean("hdr_supported", false),
            launchMode = launchMode,
            steamLaunch = steamLaunch
        )
    }

    @JvmStatic
    internal fun fetchStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun normalizeSteamLaunchModes(modes: List<String>): List<String> {
        val normalizedModes = linkedSetOf<String>()
        modes.map { PolarisGame.SteamLaunchContract.normalizeMode(it) }.filter { it.isNotBlank() }.forEach { normalizedModes.add(it) }
        return normalizedModes.toList()
    }
}
