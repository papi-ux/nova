package com.papi.nova.ui

import android.content.SharedPreferences

/** Layout size is independent of Nova Text Size and the Android system text preference. */
enum class NovaControlSize(val storedValue: String, val layoutScale: Float) {
    Compact("compact", 0.72f),
    // Standard now uses the former Compact baseline.
    Standard("standard", 0.88f),
    Large("large", 1.15f);
}

internal object NovaControlSizePreferences {
    const val KEY = "nova_control_size"

    fun read(preferences: SharedPreferences): NovaControlSize {
        val stored = runCatching { preferences.getString(KEY, null) }.getOrNull()
        return NovaControlSize.entries.firstOrNull { it.storedValue == stored } ?: NovaControlSize.Standard
    }
}
