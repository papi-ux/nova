package com.papi.nova.manager

import org.json.JSONObject

/** Typed contract returned over the paired host connection for one assigned profile. */
object WorkerLaunchContract {
    const val SOURCE = "worker_profile_v1"
    const val APP_UUID = "706f6c61-7269-4373-8000-6d756c746973"
    const val APP_ID = 1347244801
    // Catalog identifiers are opaque bounded tokens. Preserve their case:
    // the host's UUID generator also emits uppercase identifiers.
    private val profileId = Regex("[a-zA-Z0-9_][a-zA-Z0-9_-]{0,127}")

    data class Contract(val id: String, val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int,
        val target: String = "", val gameIdentity: String = "")

    private val steamAppId = Regex("[1-9][0-9]{0,9}")
    private val heroicRunners = setOf("epic", "gog", "amazon", "sideload")
    private val heroicAppName = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")

    /**
     * What a Space launcher accepts as a target. Every family has a sentinel
     * that opens the launcher itself and a grammar for one title:
     *
     *   steam   big-picture-v1 | <decimal appid>
     *   heroic  library-v1     | <runner>.<appName>, runner in epic gog amazon sideload
     *   lutris  library-v1     | id.<decimal>
     *
     * Nova carries a target it is given and hands it back; which family may use
     * which grammar is the host's rule, checked where the Space is launched. So
     * this accepts any of them rather than pretending to know the family from
     * an identity that does not carry one.
     */
    fun validTarget(target: String): Boolean = when {
        isLauncherEntry(target) -> true
        target.startsWith("id.") -> validSteamAppId(target.removePrefix("id."))
        target.contains('.') -> target.substringBefore('.') in heroicRunners &&
            heroicAppName.matches(target.substringAfter('.'))
        else -> validSteamAppId(target)
    }

    /**
     * The entry that opens a Space's launcher itself rather than one title: Steam Big Picture, or
     * the library of a launcher that has no such mode. It has no store page, no hero and no
     * playtime, and nothing about it is looked up by a game's name.
     */
    fun isLauncherEntry(target: String?): Boolean = target == "big-picture-v1" || target == "library-v1"

    private fun validSteamAppId(value: String): Boolean =
        steamAppId.matches(value) && (value.toLongOrNull() ?: Long.MAX_VALUE) <= 4294967295L

    fun libraryIdentity(identity: String?): Pair<String, String>? {
        // A target may carry dots of its own, so only the first two separators
        // divide the identity: space, the Space it belongs to, then the rest.
        val parts = identity?.split('.', limit = 3) ?: return null
        if (parts.size != 3 || parts[0] != "space" || !profileId.matches(parts[1]) || !validTarget(parts[2])) return null
        return parts[1] to parts[2]
    }

    fun isLegacyProfileApp(identity: String?) = identity == APP_UUID || identity == APP_ID.toString()

    fun isProfileApp(identity: String?) = isLegacyProfileApp(identity) || libraryIdentity(identity) != null

    fun parse(payload: JSONObject?): Contract? {
        payload ?: return null
        if (payload.opt("source") != SOURCE || payload.opt("status") != true) return null
        val worker = payload.optJSONObject("worker_profile") ?: return null
        val id = worker.opt("id") as? String ?: return null
        if (!profileId.matches(id) || integral(worker.opt("version")) != 1 ||
            worker.opt("app_uuid") != APP_UUID || integral(worker.opt("app_id")) != APP_ID ||
            worker.opt("codec") != "h264" || integral(worker.opt("audio_channels")) != 2) return null
        val profile = payload.optJSONObject("resolved_profile") ?: return null
        if (integral(profile.opt("policy_version")) != 1 || profile.opt("preset") != "worker") return null
        val fields = profile.optJSONObject("fields") ?: return null
        fun field(name: String): Any? {
            val detail = fields.optJSONObject(name) ?: return null
            if (detail.opt("source") != "capability_validation" || detail.opt("locked") != true ||
                detail.opt("normalized") !is Boolean || detail.opt("reason_code") != "worker_media_contract") return null
            return detail.opt("value")
        }
        val width = integral(field("display_width")) ?: return null
        val height = integral(field("display_height")) ?: return null
        val fps = integral(field("target_fps")) ?: return null
        val bitrate = integral(field("target_bitrate_kbps")) ?: return null
        if (width !in 320..4096 || height !in 240..2160 || width % 2 != 0 || height % 2 != 0 ||
            fps !in 15..240 || bitrate !in 1..8000 ||
            field("hdr") != false || field("preferred_codec") != "h264" ||
            field("display_mode") != "${width}x${height}x${fps}" ||
            payload.optJSONObject("topology_resolution")?.opt("resolved") != "gamescope_stream") return null
        val target = worker.optString("target", "")
        val gameIdentity = worker.optString("game_identity", "")
        if (target.isNotEmpty() && libraryIdentity(gameIdentity) != (id to target)) return null
        if (target.isEmpty() && gameIdentity.isNotEmpty() && !isLegacyProfileApp(gameIdentity)) return null
        return Contract(id, width, height, fps, bitrate, target, gameIdentity)
    }

    fun honors(
        payload: JSONObject, appIdentity: String, requestedWidth: Int, requestedHeight: Int,
        requestedFps: Float, clientMaximumFps: Float, displayLocked: Boolean,
        bitrateLocked: Boolean, bitrateCeilingKbps: Int, mirrorDesktop: Boolean,
        forcePrivate: Boolean, encoderBackend: String,
    ): Boolean {
        val contract = parse(payload) ?: return false
        return isProfileApp(appIdentity) &&
            (if (libraryIdentity(appIdentity) != null) contract.gameIdentity == appIdentity &&
                libraryIdentity(appIdentity) == (contract.id to contract.target) else contract.target.isEmpty()) &&
            !mirrorDesktop && !forcePrivate &&
            (encoderBackend.isBlank() || encoderBackend == "auto") &&
            requestedFps.isFinite() && requestedFps > 0 &&
            contract.fps <= requestedFps + .5f &&
            clientMaximumFps.isFinite() && clientMaximumFps > 0 &&
            contract.fps <= clientMaximumFps + .5f &&
            (!displayLocked || contract.width == requestedWidth && contract.height == requestedHeight) &&
            (!bitrateLocked || bitrateCeilingKbps >= contract.bitrateKbps)
    }

    private fun integral(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
    }
}
