package com.papi.nova.api

import com.papi.nova.shared.polaris.contract.PolarisV1
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONArray
import org.json.JSONObject

object PolarisGameJsonAdapter {
    @JvmStatic
    fun fromJson(json: JSONObject): PolarisGame {
        val spaceIdentity = com.papi.nova.manager.WorkerLaunchContract.libraryIdentity(json.optString(PolarisV1.Game.ID))
        if (spaceIdentity != null && json.optJSONObject(PolarisV1.SpaceLibraryGame.SPACE) == null)
            throw IllegalArgumentException("Missing Space game context")
        val source = json.optString(PolarisV1.Game.SOURCE, "other")
        val launchMode = json.optJSONObject(PolarisV1.Game.LAUNCH_MODE)?.let { modeJson ->
            val hasAppLaunchAsContract = modeJson.has(PolarisV1.LaunchMode.LAUNCH_AS) || modeJson.has(PolarisV1.LaunchMode.LAUNCH_AS_AVAILABLE) ||
                modeJson.has(PolarisV1.LaunchMode.LAUNCH_AS_UNAVAILABLE_REASON)
            PolarisGame.LaunchModeContract(
                preferredMode = PolarisGame.normalizeLaunchMode(modeJson.optString(PolarisV1.LaunchMode.PREFERRED_MODE, "")),
                recommendedMode = PolarisGame.normalizeLaunchMode(modeJson.optString(PolarisV1.LaunchMode.RECOMMENDED_MODE, "")),
                allowedModes = PolarisGame.normalizeLaunchModes(fetchStringArray(modeJson.optJSONArray(PolarisV1.LaunchMode.ALLOWED_MODES)), defaultWhenEmpty = !hasAppLaunchAsContract),
                modeReason = modeJson.optString(PolarisV1.LaunchMode.MODE_REASON, ""),
                followsHostDefault = modeJson.optBoolean(PolarisV1.LaunchMode.FOLLOWS_HOST_DEFAULT, true),
                launchAs = if (hasAppLaunchAsContract) modeJson.opt(PolarisV1.LaunchMode.LAUNCH_AS) as? String ?: "" else null,
                launchAsAvailable = modeJson.opt(PolarisV1.LaunchMode.LAUNCH_AS_AVAILABLE) as? Boolean,
                launchAsUnavailableReason = modeJson.optString(PolarisV1.LaunchMode.LAUNCH_AS_UNAVAILABLE_REASON, "")
            )
        }
        val steamLaunch = json.optJSONObject(PolarisV1.Game.STEAM_LAUNCH)?.let { launchJson ->
            PolarisGame.SteamLaunchContract(
                available = launchJson.optBoolean(PolarisV1.SteamLaunch.AVAILABLE, false),
                mode = PolarisGame.SteamLaunchContract.normalizeMode(launchJson.optString(PolarisV1.SteamLaunch.MODE, "direct")),
                recommendedMode = PolarisGame.SteamLaunchContract.normalizeMode(launchJson.optString(PolarisV1.SteamLaunch.RECOMMENDED_MODE, "direct")),
                allowedModes = normalizeSteamLaunchModes(fetchStringArray(launchJson.optJSONArray(PolarisV1.SteamLaunch.ALLOWED_MODES))),
                modeReason = launchJson.optString(PolarisV1.SteamLaunch.MODE_REASON, "")
            )
        }

        return PolarisGame(
            id = json.optString(PolarisV1.Game.ID, ""),
            appId = json.optString(PolarisV1.Game.APP_ID, "").toIntOrNull() ?: json.optInt(PolarisV1.Game.APP_ID, 0),
            name = if (spaceIdentity?.second == "big-picture-v1") "Steam Big Picture" else json.optString(PolarisV1.Game.NAME, ""),
            source = source,
            launcherSource = json.optString("launcher_source", source),
            launcherDetail = json.optString("launcher_detail", ""),
            platform = json.optString(PolarisV1.Game.PLATFORM, "unknown").lowercase(),
            runtime = json.optString(PolarisV1.Game.RUNTIME, "unknown").lowercase(),
            platformLabelFromServer = json.optString(PolarisV1.Game.PLATFORM_LABEL, ""),
            runtimeLabelFromServer = json.optString(PolarisV1.Game.RUNTIME_LABEL, ""),
            steamAppid = json.optString(PolarisV1.Game.STEAM_APPID, ""),
            category = json.optString(PolarisV1.Game.CATEGORY, ""),
            installed = json.optBoolean(PolarisV1.Game.INSTALLED, true),
            coverUrl = json.optString(PolarisV1.Game.COVER_URL, ""),
            genres = fetchStringArray(json.optJSONArray(PolarisV1.Game.GENRES)),
            lastLaunched = json.optLong(PolarisV1.Game.LAST_LAUNCHED, 0),
            beatTime = json.optJSONObject(PolarisV1.Game.BEAT_TIME)?.let { beat ->
                PolarisGame.BeatTime(
                    mainSeconds = beat.optLong("main_seconds", 0).coerceAtLeast(0),
                    extrasSeconds = beat.optLong("extras_seconds", 0).coerceAtLeast(0),
                    completionistSeconds = beat.optLong("completionist_seconds", 0).coerceAtLeast(0),
                    matchedName = beat.optString("matched_name", ""),
                    url = beat.optString("url", ""),
                    cachedAt = beat.optLong("cached_at", 0).coerceAtLeast(0)
                )
            },
            playTime = json.optJSONObject(PolarisV1.Game.PLAY_TIME)?.let { played ->
                PolarisGame.PlayTime(
                    seconds = played.optLong("seconds", 0).coerceAtLeast(0),
                    source = played.optString("source", ""),
                    readAt = played.optLong("read_at", 0).coerceAtLeast(0)
                )
            },
            mangohud = json.optBoolean(PolarisV1.Game.MANGOHUD, false),
            hdrSupported = json.optBoolean(PolarisV1.Game.HDR_SUPPORTED, false),
            launchMode = launchMode,
            steamLaunch = steamLaunch,
            steamLifecycle = json.optJSONObject(PolarisV1.Game.STEAM_LIFECYCLE)?.takeIf {
                it.opt("keep_in_host_virtual_display") is Boolean && it.opt("keep_in_desktop_display") is Boolean
            }?.let { PolarisGame.SteamLifecycle(it.getBoolean("keep_in_host_virtual_display"), it.getBoolean("keep_in_desktop_display")) },
            displayPlanner = parseDisplayPlanner(json.optJSONObject(PolarisV1.Game.DISPLAY_PLANNER)),
            artwork = parseArtworkManifest(json.optJSONObject(PolarisV1.Game.ARTWORK)),
            space = json.optJSONObject(PolarisV1.SpaceLibraryGame.SPACE)?.let { context ->
                val identity = com.papi.nova.manager.WorkerLaunchContract.libraryIdentity(json.optString(PolarisV1.Game.ID))
                val id = context.optString(PolarisV1.SpaceRef.ID)
                val target = context.optString(PolarisV1.SpaceRef.TARGET)
                val name = context.optString(PolarisV1.SpaceRef.NAME)
                if (identity == null || identity.first != id || identity.second != target || name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 128 || name.any { it.code < 32 || it.code == 127 })
                    throw IllegalArgumentException("Invalid Space game identity")
                PolarisGame.SpaceContext(id, name, target)
            },
            steamBigPicture = json.optBoolean(PolarisV1.Game.STEAM_BIG_PICTURE, false)
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
            available = plannerJson.optBoolean(PolarisV1.DisplayPlanner.AVAILABLE, false),
            sourceMode = plannerJson.optString(PolarisV1.DisplayPlanner.SOURCE_MODE, ""),
            sourceAspectRatio = plannerJson.optString(PolarisV1.DisplayPlanner.SOURCE_ASPECT_RATIO, ""),
            sourceFps = finiteDouble(plannerJson, PolarisV1.DisplayPlanner.SOURCE_FPS, 0.0).coerceAtLeast(0.0),
            recommendedId = plannerJson.optString(PolarisV1.DisplayPlanner.RECOMMENDED_ID, ""),
            recommendedTitle = plannerJson.optString(PolarisV1.DisplayPlanner.RECOMMENDED_TITLE, ""),
            recommendedMode = plannerJson.optString(PolarisV1.DisplayPlanner.RECOMMENDED_MODE, ""),
            choices = parseDisplayPlannerChoices(plannerJson.optJSONArray(PolarisV1.DisplayPlanner.CHOICES)),
            advancedScaleFactors = parseDisplayPlannerScaleChoices(plannerJson.optJSONArray(PolarisV1.DisplayPlanner.ADVANCED_SCALE_FACTORS))
        )
    }

    private fun parseDisplayPlannerChoices(array: JSONArray?): List<PolarisGame.DisplayPlannerChoice> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { choiceJson ->
                PolarisGame.DisplayPlannerChoice(
                    id = choiceJson.optString(PolarisV1.DisplayPlannerChoice.ID, ""),
                    title = choiceJson.optString(PolarisV1.DisplayPlannerChoice.TITLE, ""),
                    intent = choiceJson.optString(PolarisV1.DisplayPlannerChoice.INTENT, ""),
                    targetMode = choiceJson.optString(PolarisV1.DisplayPlannerChoice.TARGET_MODE, ""),
                    badge = choiceJson.optString(PolarisV1.DisplayPlannerChoice.BADGE, ""),
                    reason = choiceJson.optString(PolarisV1.DisplayPlannerChoice.REASON, ""),
                    advanced = choiceJson.optBoolean(PolarisV1.DisplayPlannerChoice.ADVANCED, false),
                    custom = choiceJson.optBoolean(PolarisV1.DisplayPlannerChoice.CUSTOM, false),
                    // The contract's own defaults: an entry that says nothing is safe and
                    // visible, matching what an older serialisation would have decoded.
                    safe = choiceJson.optBoolean(PolarisV1.DisplayPlannerChoice.SAFE, true),
                    hidden = choiceJson.optBoolean(PolarisV1.DisplayPlannerChoice.HIDDEN, false),
                    scaleFactor = finiteDouble(choiceJson, PolarisV1.DisplayPlannerChoice.SCALE_FACTOR, 1.0),
                    aspectRatio = choiceJson.optString(PolarisV1.DisplayPlannerChoice.ASPECT_RATIO, "")
                )
            }
        }
    }

    private fun parseDisplayPlannerScaleChoices(array: JSONArray?): List<PolarisGame.DisplayPlannerScaleChoice> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { scaleJson ->
                PolarisGame.DisplayPlannerScaleChoice(
                    scaleFactor = finiteDouble(scaleJson, PolarisV1.DisplayPlannerScaleChoice.SCALE_FACTOR, 1.0),
                    label = scaleJson.optString(PolarisV1.DisplayPlannerScaleChoice.LABEL, ""),
                    targetMode = scaleJson.optString(PolarisV1.DisplayPlannerScaleChoice.TARGET_MODE, ""),
                    safe = scaleJson.optBoolean(PolarisV1.DisplayPlannerScaleChoice.SAFE, true)
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
