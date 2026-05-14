package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R

object AutoQualityProfilePreferences {
    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_PREFIX = "ai_profile_preference_name_"

    private val values = arrayOf("auto", "quality", "high_fps", "stability")

    fun values(): Array<String> = values.copyOf()

    fun normalize(preference: String?): String {
        return preference?.takeIf { it in values } ?: "auto"
    }

    fun load(context: Context, gameName: String): String {
        return normalize(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key(gameName), "auto")
        )
    }

    fun hasSaved(context: Context, gameName: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(key(gameName))
    }

    fun save(context: Context, gameName: String, preference: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(gameName), normalize(preference))
            .apply()
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

    private fun key(gameName: String): String {
        return "$KEY_PREFIX$gameName"
    }
}
