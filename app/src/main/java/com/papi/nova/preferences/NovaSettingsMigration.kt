package com.papi.nova.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Legacy values live here until the Classic and Compose rows both use generated tiers. */
data class NovaLegacyStreamPreset(val key: String, val label: String, val resolution: String,
    val bitrateKbps: Int, val codec: String) {
    companion object {
        val PERFORMANCE = NovaLegacyStreamPreset("performance", "Performance", "1280x720", 10000, "auto")
        val BALANCED = NovaLegacyStreamPreset("balanced", "Balanced", "1920x1080", 20000, "auto")
        val QUALITY = NovaLegacyStreamPreset("quality", "Quality", "1920x1080", 50000, "forceh265")
        val PRESETS = listOf(PERFORMANCE, BALANCED, QUALITY)
        fun fromKey(key: String) = PRESETS.find { it.key == key }
    }
}
// Source compatibility for the separately owned UI conversion. No independent preset implementation.
typealias StreamPreset = NovaLegacyStreamPreset

object NovaSettingsMigration {
    const val SCHEMA = "nova_settings_schema"
    const val TIER = "nova_picture_tier"
    const val AUTO = "nova_bitrate_auto"
    const val CUSTOM_AUTO = "nova_custom_bitrate_auto"
    const val CUSTOM_EXISTS = "nova_custom_stream_exists"
    const val LEGACY_BALANCED = "__nova_migrated_balanced_default_resolution_20260519_v3"
    val STREAM_KEYS = setOf("list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format",
        "edit_diy_w_h", "custom_refresh_rate")

    fun legacyBalanced(input: Map<String, Any?>): Map<String, Any?> {
        if (input[LEGACY_BALANCED] == true) return input
        val output = input.toMutableMap()
        val legacy = input["list_resolution_fps"] == "720p60"
        val size = if (legacy) "1280x720" else input["list_resolution"] ?: "1920x1080"
        val fps = if (legacy) "60" else input["list_fps"] ?: "60"
        val bitrate = (input["seekbar_bitrate_kbps"] as? Number)?.toInt()
            ?: ((input["seekbar_bitrate"] as? Number)?.toInt() ?: 15) * 1000
        if ((input["nova_stream_preset"] ?: "balanced") == "balanced" && size == "1280x720" &&
            fps == "60" && bitrate in setOf(15000, 20000) && (input["video_format"] ?: "auto") == "auto") {
            output["list_resolution"] = "1920x1080"; output["list_fps"] = "60"
            output.remove("list_resolution_fps")
        }
        output[LEGACY_BALANCED] = true
        return output
    }

    /** Pure and idempotent. The existing legacy normalization precedes the schema transition. */
    fun migrate(input: Map<String, Any?>): Map<String, Any?> {
        if (((input[SCHEMA] as? Number)?.toInt() ?: 0) >= 2) return input
        val output = legacyBalanced(input).toMutableMap()
        output[SCHEMA] = 2
        val upgrade = input.keys.any { it in STREAM_KEYS || it in setOf(SCHEMA, LEGACY_BALANCED,
            "list_resolution_fps", "seekbar_bitrate", "seekbar_metered_bitrate_kbps", "nova_stream_preset") }
        if (!upgrade) {
            output[TIER] = "recommended"; output[AUTO] = true; output[CUSTOM_AUTO] = true; output[CUSTOM_EXISTS] = false
            return output
        }
        val legacy = (output["list_resolution_fps"] as? String)?.let {
            Regex("(360p|720p|1080p|4K)(30|60)", RegexOption.IGNORE_CASE).matchEntire(it)
        }
        if (legacy != null) {
            output["list_resolution"] = PreferenceConfiguration.convertFromLegacyResolutionString(legacy.groupValues[1])
            output["list_fps"] = legacy.groupValues[2]; output.remove("list_resolution_fps")
        }
        val resolution = (output["list_resolution"] as? String ?: "1920x1080").let {
            if (it.contains('x')) it else PreferenceConfiguration.convertFromLegacyResolutionString(it)
        }
        val fps = output["list_fps"] as? String ?: "60"
        val raw = runCatching { PreferenceConfiguration.getDefaultBitrate(resolution, fps) }.getOrDefault(20000)
        val oldBitrate = (output["seekbar_bitrate_kbps"] as? Number)?.toInt()
            ?: ((output["seekbar_bitrate"] as? Number)?.toInt() ?: 0) * 1000
        // Materialize the old effective default. Do not round or move an upgrader's stream.
        output["list_resolution"] = resolution; output["list_fps"] = fps
        output["video_format"] = output["video_format"] ?: "auto"
        // Absence is meaningful: a saved setup derives bitrate from its own effective size/rate.
        if (oldBitrate > 0) output["seekbar_bitrate_kbps"] = oldBitrate
        output[TIER] = "custom"; output[CUSTOM_EXISTS] = true
        output[AUTO] = oldBitrate == 0 || oldBitrate == raw || oldBitrate == ((raw + 4999) / 5000) * 5000
        output[CUSTOM_AUTO] = output[AUTO]
        return output
    }

    fun savedSetup(input: Map<String, Any>): Map<String, Any> {
        val metadata = setOf(TIER,AUTO,CUSTOM_EXISTS,CUSTOM_AUTO)
        if (input.keys.none { it in STREAM_KEYS }) return if (input.keys.none { it in metadata }) input else input - metadata
        val automatic = input[CUSTOM_AUTO] as? Boolean ?: input[AUTO] as? Boolean ?:
            !(input.containsKey("seekbar_bitrate_kbps") || input.containsKey("seekbar_bitrate"))
        val tier = input[TIER].takeIf { it in setOf("saver","recommended","max","custom") } ?: "custom"
        if (input[TIER]==tier && input[CUSTOM_EXISTS]==true && input[CUSTOM_AUTO]==automatic) return input
        return input + mapOf(TIER to tier,CUSTOM_EXISTS to true,CUSTOM_AUTO to automatic,AUTO to automatic)
    }

    fun apply(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getInt(SCHEMA, 0) >= 2) return
        val before = prefs.all
        writeDifference(prefs, migrate(before), before)
    }

    fun writeDifference(prefs: SharedPreferences, result: Map<String, Any?>, before: Map<String, Any?> = prefs.all) {
        if (before == result) return
        val editor = prefs.edit()
        (before.keys - result.keys).forEach(editor::remove)
        result.forEach { (key, value) -> if (before[key] != value) when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        check(editor.commit()) { "Could not migrate stream settings" }
    }
}
