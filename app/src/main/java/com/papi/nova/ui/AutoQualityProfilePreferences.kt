package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R

object AutoQualityProfilePreferences {
    private const val PREFS_NAME = "nova_prefs"
    private const val APP_KEY_PREFIX = "launch_preset_app_id_"
    private const val LEGACY_NAME_KEY_PREFIX = "ai_profile_preference_name_"

    private val values = arrayOf("auto", "quality", "high_fps", "stability")

    fun values(): Array<String> = values.copyOf()

    fun normalize(preference: String?): String {
        return preference?.takeIf { it in values } ?: "auto"
    }

    fun load(context: Context, appId: String, gameName: String): String {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val canonicalKey = appKey(appId)
        if (canonicalKey != null && preferences.contains(canonicalKey)) {
            return normalize(preferences.getString(canonicalKey, "auto"))
        }

        val legacyKey = legacyNameKey(gameName)
        if (legacyKey != null && preferences.contains(legacyKey)) {
            val migrated = normalize(preferences.getString(legacyKey, "auto"))
            if (canonicalKey != null) {
                // One bounded migration consumes the shared name key. A second
                // UUID-distinct app with the same title therefore cannot adopt it.
                preferences.edit()
                    .putString(canonicalKey, migrated)
                    .remove(legacyKey)
                    .commit()
            }
            return migrated
        }
        return "auto"
    }

    fun hasSaved(context: Context, appId: String, gameName: String): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return appKey(appId)?.let(preferences::contains) == true ||
            legacyNameKey(gameName)?.let(preferences::contains) == true
    }

    fun save(context: Context, appId: String, gameName: String, preference: String) {
        val canonicalKey = appKey(appId) ?: return
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(canonicalKey, normalize(preference))
        legacyNameKey(gameName)?.let(editor::remove)
        editor.apply()
    }

    fun labelRes(preference: String): Int {
        return when (normalize(preference)) {
            "quality" -> R.string.nova_library_profile_preference_quality
            "high_fps" -> R.string.nova_library_profile_preference_high_fps
            "stability" -> R.string.nova_library_profile_preference_stability
            else -> R.string.nova_library_profile_preference_auto
        }
    }

    fun shortLabelRes(preference: String): Int {
        return when (normalize(preference)) {
            "quality" -> R.string.nova_auto_quality_preference_quality
            "high_fps" -> R.string.nova_auto_quality_preference_high_fps
            "stability" -> R.string.nova_auto_quality_preference_stability
            else -> R.string.nova_auto_quality_preference_auto
        }
    }

    private fun appKey(appId: String): String? = appId.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { "$APP_KEY_PREFIX$it" }

    private fun legacyNameKey(gameName: String): String? = gameName.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { "$LEGACY_NAME_KEY_PREFIX$it" }
}
