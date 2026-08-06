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
    @SerialName("steam_launch") val steamLaunch: SteamLaunchContract? = null,
    @SerialName("display_planner") val displayPlanner: DisplayPlannerContract? = null,
    @SerialName("artwork") val artwork: ArtworkManifest? = null,
    /**
     * How long the owning launcher says this has been played, or null when none can say.
     *
     * Null rather than zero: a game nobody has played and a game no launcher owns are
     * different answers, and only one of them should read "Not started".
     *
     * Last in the list on purpose. Sixteen places build this positionally, so a field
     * inserted beside lastLaunched where it reads best would silently shift every one of
     * them. Serialisation is by name, so position costs nothing here.
     */
    @SerialName("play_time") val playTime: PlayTime? = null,
    /** Completion estimates, or null when the host's dataset has nothing for this game. */
    @SerialName("beat_time") val beatTime: BeatTime? = null
) {
    @Serializable
    data class ArtworkManifest(
        @SerialName("version") val version: Int = 1,
        @SerialName("revision") val revision: String = "",
        @SerialName("state") val state: String = "partial",
        @SerialName("match") val match: ArtworkMatch? = null,
        @SerialName("cached_at") val cachedAt: Long = 0,
        @SerialName("assets") val assets: ArtworkAssets = ArtworkAssets(),
        @SerialName("override") val override: ArtworkOverride? = null
    ) {
        fun asset(kind: String): ArtworkAsset? = assets.asset(kind)
    }

    /**
     * What the host's dataset says about finishing this game.
     *
     * Every figure is optional on its own: a catalogue that knows the main story but not
     * the completionist run should say so rather than pad the gap with a zero.
     */
    @Serializable
    data class BeatTime(
        @SerialName("main_seconds") val mainSeconds: Long = 0,
        @SerialName("extras_seconds") val extrasSeconds: Long = 0,
        @SerialName("completionist_seconds") val completionistSeconds: Long = 0,
        @SerialName("matched_name") val matchedName: String = "",
        @SerialName("url") val url: String = "",
        @SerialName("cached_at") val cachedAt: Long = 0
    ) {
        /** The bar's full width, falling back through what is actually known. */
        val longestSeconds: Long
            get() = maxOf(completionistSeconds, extrasSeconds, mainSeconds)
    }

    /** What a launcher says about time spent, normalised to seconds before it travels. */
    @Serializable
    data class PlayTime(
        @SerialName("seconds") val seconds: Long = 0,
        @SerialName("source") val source: String = "",
        @SerialName("read_at") val readAt: Long = 0
    )

    @Serializable
    data class ArtworkMatch(
        @SerialName("source") val source: String = "",
        @SerialName("provider_game_id") val providerGameId: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("confidence") val confidence: Double = 0.0,
        @SerialName("manual") val manual: Boolean = false
    )

    @Serializable
    data class ArtworkOverride(
        @SerialName("active") val active: Boolean = false,
        @SerialName("kinds") val kinds: List<String> = emptyList(),
        @SerialName("logo_transform") val logoTransform: ArtworkLogoTransform? = null
    )

    @Serializable
    data class ArtworkLogoTransform(
        @SerialName("x") val x: Double = 0.5,
        @SerialName("y") val y: Double = 0.5,
        @SerialName("scale") val scale: Double = 1.0
    )

    @Serializable
    data class ArtworkAssets(
        @SerialName("poster") val poster: ArtworkAsset? = null,
        @SerialName("hero") val hero: ArtworkAsset? = null,
        @SerialName("logo") val logo: ArtworkAsset? = null,
        @SerialName("icon") val icon: ArtworkAsset? = null,
        @SerialName("screenshots") val screenshots: List<ArtworkAsset> = emptyList(),
        @SerialName("trailer") val trailer: ArtworkAsset? = null
    ) {
        fun asset(kind: String): ArtworkAsset? = when (kind.trim().lowercase()) {
            ARTWORK_KIND_POSTER -> poster
            ARTWORK_KIND_HERO -> hero
            ARTWORK_KIND_LOGO -> logo
            ARTWORK_KIND_ICON -> icon
            ARTWORK_KIND_TRAILER -> trailer
            else -> null
        }?.takeIf { it.url.isNotBlank() }
    }

    @Serializable
    data class ArtworkAsset(
        @SerialName("url") val url: String = "",
        @SerialName("source") val source: String = "",
        @SerialName("mime_type") val mimeType: String = "",
        @SerialName("cached") val cached: Boolean = false
    )

    @Serializable
    data class DisplayPlannerContract(
        @SerialName("available") val available: Boolean = false,
        @SerialName("source_mode") val sourceMode: String = "",
        @SerialName("source_aspect_ratio") val sourceAspectRatio: String = "",
        @SerialName("source_fps") val sourceFps: Double = 0.0,
        @SerialName("recommended_id") val recommendedId: String = "",
        @SerialName("recommended_title") val recommendedTitle: String = "",
        @SerialName("recommended_mode") val recommendedMode: String = "",
        @SerialName("choices") val choices: List<DisplayPlannerChoice> = emptyList(),
        @SerialName("advanced_scale_factors") val advancedScaleFactors: List<DisplayPlannerScaleChoice> = emptyList()
    )

    @Serializable
    data class DisplayPlannerChoice(
        @SerialName("id") val id: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("intent") val intent: String = "",
        @SerialName("target_mode") val targetMode: String = "",
        @SerialName("badge") val badge: String = "",
        @SerialName("reason") val reason: String = "",
        @SerialName("advanced") val advanced: Boolean = false,
        @SerialName("custom") val custom: Boolean = false,
        @SerialName("safe") val safe: Boolean = true,
        @SerialName("hidden") val hidden: Boolean = false,
        @SerialName("scale_factor") val scaleFactor: Double = 1.0,
        @SerialName("aspect_ratio") val aspectRatio: String = ""
    )

    @Serializable
    data class DisplayPlannerScaleChoice(
        @SerialName("scale_factor") val scaleFactor: Double = 1.0,
        @SerialName("label") val label: String = "",
        @SerialName("target_mode") val targetMode: String = "",
        @SerialName("safe") val safe: Boolean = true
    )
    @Serializable
    data class LaunchModeContract(
        @SerialName("preferred_mode") val preferredMode: String = "",
        @SerialName("recommended_mode") val recommendedMode: String = "",
        @SerialName("allowed_modes") val allowedModes: List<String> = emptyList(),
        @SerialName("mode_reason") val modeReason: String = ""
    ) {
        fun allows(mode: String): Boolean = normalizeLaunchModes(allowedModes).contains(normalizeLaunchMode(mode))
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
    fun artworkAsset(kind: String): ArtworkAsset? = artwork?.asset(kind)
    val posterArtwork: ArtworkAsset? get() = artworkAsset(ARTWORK_KIND_POSTER)
    val heroArtwork: ArtworkAsset? get() = artworkAsset(ARTWORK_KIND_HERO)
    val logoArtwork: ArtworkAsset? get() = artworkAsset(ARTWORK_KIND_LOGO)
    val iconArtwork: ArtworkAsset? get() = artworkAsset(ARTWORK_KIND_ICON)
    val screenshotArtwork: List<ArtworkAsset> get() = artwork?.assets?.screenshots.orEmpty().filter { it.url.isNotBlank() }
    val trailerArtwork: ArtworkAsset? get() = artworkAsset(ARTWORK_KIND_TRAILER)

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
        const val ARTWORK_KIND_POSTER = "poster"
        const val ARTWORK_KIND_HERO = "hero"
        const val ARTWORK_KIND_LOGO = "logo"
        const val ARTWORK_KIND_ICON = "icon"
        const val ARTWORK_KIND_SCREENSHOT = "screenshot"
        const val ARTWORK_KIND_TRAILER = "trailer"

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
