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
            beatTime = json.optJSONObject("beat_time")?.let { beat ->
                PolarisGame.BeatTime(
                    mainSeconds = beat.optLong("main_seconds", 0).coerceAtLeast(0),
                    extrasSeconds = beat.optLong("extras_seconds", 0).coerceAtLeast(0),
                    completionistSeconds = beat.optLong("completionist_seconds", 0).coerceAtLeast(0),
                    matchedName = beat.optString("matched_name", ""),
                    url = beat.optString("url", ""),
                    cachedAt = beat.optLong("cached_at", 0).coerceAtLeast(0)
                )
            },
            playTime = json.optJSONObject("play_time")?.let { played ->
                PolarisGame.PlayTime(
                    seconds = played.optLong("seconds", 0).coerceAtLeast(0),
                    source = played.optString("source", ""),
                    readAt = played.optLong("read_at", 0).coerceAtLeast(0)
                )
            },
            mangohud = json.optBoolean("mangohud", false),
            hdrSupported = json.optBoolean("hdr_supported", false),
            launchMode = launchMode,
            steamLaunch = steamLaunch,
            displayPlanner = parseDisplayPlanner(json.optJSONObject("display_planner")),
            artwork = parseArtworkManifest(json.optJSONObject("artwork"))
        )
    }

    /**
     * Polaris computes this once per request from the host display and attaches the same
     * object to every game, so absence means an older host rather than a game without a
     * plan — and the Resolution row draws nothing rather than guessing.
     *
     * The function and receiver names here are recorded by name in Polaris'
     * docs/nova-contract.json (`nova_reader` for the display_planner objects); renaming
     * them means updating that manifest in the same change.
     */
    @JvmStatic
    internal fun parseDisplayPlanner(plannerJson: JSONObject?): PolarisGame.DisplayPlannerContract? {
        if (plannerJson == null) return null
        return PolarisGame.DisplayPlannerContract(
            available = plannerJson.optBoolean("available", false),
            sourceMode = plannerJson.optString("source_mode", ""),
            sourceAspectRatio = plannerJson.optString("source_aspect_ratio", ""),
            sourceFps = finiteDouble(plannerJson, "source_fps", 0.0).coerceAtLeast(0.0),
            recommendedId = plannerJson.optString("recommended_id", ""),
            recommendedTitle = plannerJson.optString("recommended_title", ""),
            recommendedMode = plannerJson.optString("recommended_mode", ""),
            choices = parseDisplayPlannerChoices(plannerJson.optJSONArray("choices")),
            advancedScaleFactors = parseDisplayPlannerScaleChoices(plannerJson.optJSONArray("advanced_scale_factors"))
        )
    }

    private fun parseDisplayPlannerChoices(array: JSONArray?): List<PolarisGame.DisplayPlannerChoice> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { choiceJson ->
                PolarisGame.DisplayPlannerChoice(
                    id = choiceJson.optString("id", ""),
                    title = choiceJson.optString("title", ""),
                    intent = choiceJson.optString("intent", ""),
                    targetMode = choiceJson.optString("target_mode", ""),
                    badge = choiceJson.optString("badge", ""),
                    reason = choiceJson.optString("reason", ""),
                    advanced = choiceJson.optBoolean("advanced", false),
                    custom = choiceJson.optBoolean("custom", false),
                    // The contract's own defaults: an entry that says nothing is safe and
                    // visible, matching what an older serialisation would have decoded.
                    safe = choiceJson.optBoolean("safe", true),
                    hidden = choiceJson.optBoolean("hidden", false),
                    scaleFactor = finiteDouble(choiceJson, "scale_factor", 1.0),
                    aspectRatio = choiceJson.optString("aspect_ratio", "")
                )
            }
        }
    }

    private fun parseDisplayPlannerScaleChoices(array: JSONArray?): List<PolarisGame.DisplayPlannerScaleChoice> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { scaleJson ->
                PolarisGame.DisplayPlannerScaleChoice(
                    scaleFactor = finiteDouble(scaleJson, "scale_factor", 1.0),
                    label = scaleJson.optString("label", ""),
                    targetMode = scaleJson.optString("target_mode", ""),
                    safe = scaleJson.optBoolean("safe", true)
                )
            }
        }
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
