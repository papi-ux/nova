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
            playtimeMinutes = json.optLong("playtime_minutes", 0).coerceAtLeast(0),
            mangohud = json.optBoolean("mangohud", false),
            hdrSupported = json.optBoolean("hdr_supported", false),
            launchMode = launchMode,
            steamLaunch = steamLaunch,
            artwork = parseArtworkManifest(json.optJSONObject("artwork"))
        )
    }

    @JvmStatic
    internal fun parseArtworkManifest(json: JSONObject?): PolarisGame.ArtworkManifest? {
        if (json == null) return null
        val assets = json.optJSONObject("assets")
        val match = json.optJSONObject("match")?.let { matchJson ->
            PolarisGame.ArtworkMatch(
                source = matchJson.optString("source", ""),
                providerGameId = matchJson.optString("provider_game_id", ""),
                title = matchJson.optString("title", ""),
                confidence = finiteDouble(matchJson, "confidence", 0.0).coerceIn(0.0, 1.0),
                manual = matchJson.optBoolean("manual", false)
            )
        }
        val override = json.optJSONObject("override")?.let { overrideJson ->
            val transform = overrideJson.optJSONObject("logo_transform")?.let { transformJson ->
                PolarisGame.ArtworkLogoTransform(
                    x = finiteDouble(transformJson, "x", 0.5).coerceIn(0.0, 1.0),
                    y = finiteDouble(transformJson, "y", 0.5).coerceIn(0.0, 1.0),
                    scale = finiteDouble(transformJson, "scale", 1.0).coerceIn(0.25, 4.0)
                )
            }
            PolarisGame.ArtworkOverride(
                active = overrideJson.optBoolean("active", false),
                kinds = fetchStringArray(overrideJson.optJSONArray("kinds")),
                logoTransform = transform
            )
        }
        return PolarisGame.ArtworkManifest(
            version = json.optInt("version", 1).takeIf { it > 0 } ?: 1,
            revision = json.opt("revision") as? String ?: "",
            state = json.optString("state", "partial").ifBlank { "partial" },
            match = match,
            cachedAt = json.optLong("cached_at", 0).coerceAtLeast(0),
            assets = PolarisGame.ArtworkAssets(
                poster = parseArtworkAsset(assets?.optJSONObject("poster")),
                hero = parseArtworkAsset(assets?.optJSONObject("hero")),
                logo = parseArtworkAsset(assets?.optJSONObject("logo")),
                icon = parseArtworkAsset(assets?.optJSONObject("icon")),
                screenshots = parseArtworkAssets(assets?.optJSONArray("screenshots")),
                trailer = parseArtworkAsset(assets?.optJSONObject("trailer"))
            ),
            override = override
        )
    }

    private fun parseArtworkAssets(array: JSONArray?): List<PolarisGame.ArtworkAsset> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            parseArtworkAsset(array.optJSONObject(index))
        }
    }

    private fun finiteDouble(json: JSONObject, key: String, fallback: Double): Double {
        return json.optDouble(key, fallback).takeIf { it.isFinite() } ?: fallback
    }

    private fun parseArtworkAsset(json: JSONObject?): PolarisGame.ArtworkAsset? {
        if (json == null) return null
        return PolarisGame.ArtworkAsset(
            url = json.opt("url") as? String ?: "",
            source = json.opt("source") as? String ?: "",
            mimeType = json.opt("mime_type") as? String ?: "",
            cached = json.optBoolean("cached", false)
        ).takeIf { it.url.isNotBlank() }
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
