package com.papi.nova.preferences

import android.content.SharedPreferences
import androidx.core.content.edit

internal object NovaUpdatePromptPreferences {
    const val AUTO_CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    private const val KEY_LAST_AUTO_CHECK_MS = "nova_update_last_auto_check_ms"
    private const val KEY_SKIPPED_RELEASE_TAG = "nova_update_skipped_release_tag"

    fun shouldRunAutomaticCheck(prefs: SharedPreferences, nowMs: Long): Boolean {
        val lastCheckMs = prefs.getLong(KEY_LAST_AUTO_CHECK_MS, 0L)
        if (lastCheckMs <= 0L) return true
        return nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS
    }

    fun recordAutomaticCheck(prefs: SharedPreferences, nowMs: Long) {
        prefs.edit { putLong(KEY_LAST_AUTO_CHECK_MS, nowMs) }
    }

    fun recordAutomaticCheckResult(
        prefs: SharedPreferences,
        nowMs: Long,
        result: Result<NovaUpdateCheckResult>,
    ) {
        if (result.isSuccess) recordAutomaticCheck(prefs, nowMs)
    }

    fun shouldShowAutomaticPrompt(prefs: SharedPreferences, release: NovaUpdateRelease): Boolean {
        val skippedTag = prefs.getString(KEY_SKIPPED_RELEASE_TAG, null)
        return skippedTag == null || skippedTag != release.tagName
    }

    fun skipRelease(prefs: SharedPreferences, release: NovaUpdateRelease) {
        prefs.edit().putString(KEY_SKIPPED_RELEASE_TAG, release.tagName).apply()
    }
}
