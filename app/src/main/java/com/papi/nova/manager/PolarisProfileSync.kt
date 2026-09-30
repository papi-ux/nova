package com.papi.nova.manager

import android.content.Context
import com.papi.nova.api.PolarisClientSettings

object PolarisProfileSync {
    private const val PREF_NAME = "nova_prefs"
    private const val AUTO_SYNC_PREFIX = "polaris_auto_sync_"
    private val autoSyncLock = Any()
    const val AUTO_SYNC_MIN_INTERVAL_MS = 5000L

    data class StreamProfile(
        val displayMode: String,
        val bitrateKbps: Int
    )

    enum class ProfileState {
        UNAVAILABLE,
        POLARIS_UNSET,
        MATCHED,
        DIFFERENT
    }

    @JvmStatic
    fun compare(novaDisplayMode: String, novaBitrateKbps: Int, settings: PolarisClientSettings?): ProfileState {
        if (settings == null) return ProfileState.UNAVAILABLE
        val polaris = polarisOverrideProfile(settings) ?: return ProfileState.POLARIS_UNSET
        return if (profilesMatch(novaDisplayMode, novaBitrateKbps, polaris)) {
            ProfileState.MATCHED
        } else {
            ProfileState.DIFFERENT
        }
    }

    @JvmStatic
    fun polarisOverrideProfile(settings: PolarisClientSettings): StreamProfile? {
        val displayMode = settings.desired.displayMode.ifBlank {
            settings.effective.displayMode
        }
        // Static profile matching intentionally ignores live host tuning values
        // such as adaptiveTargetBitrateKbps, adaptiveBitrateEnabled, and AI optimizer.
        val bitrateKbps = settings.desired.targetBitrateKbps.takeIf { it > 0 }
            ?: settings.effective.targetBitrateKbps.takeIf { it > 0 }
            ?: 0
        if (displayMode.isBlank() && bitrateKbps <= 0) return null
        return StreamProfile(displayMode, bitrateKbps)
    }

    @JvmStatic
    fun profilesMatch(novaDisplayMode: String, novaBitrateKbps: Int, polaris: StreamProfile?): Boolean {
        if (polaris == null) return false
        return polaris.displayMode == novaDisplayMode && polaris.bitrateKbps == novaBitrateKbps
    }

    @JvmStatic
    fun autoSyncKey(serverUuid: String): String = AUTO_SYNC_PREFIX + serverUuid

    @JvmStatic
    fun isAutoSyncEnabled(context: Context, serverUuid: String?): Boolean {
        if (serverUuid.isNullOrBlank()) return false
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(autoSyncKey(serverUuid), false)
    }

    /** Seed only a newly completed pairing; absence still means off for existing hosts. */
    @JvmStatic
    fun initializeAutoSyncForNewPairing(context: Context, serverUuid: String?) {
        if (serverUuid.isNullOrBlank()) return
        synchronized(autoSyncLock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val key = autoSyncKey(serverUuid)
            if (!prefs.contains(key)) prefs.edit().putBoolean(key, true).apply()
        }
    }

    @JvmStatic
    fun setAutoSyncEnabled(context: Context, serverUuid: String?, enabled: Boolean) {
        if (serverUuid.isNullOrBlank()) return
        synchronized(autoSyncLock) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(autoSyncKey(serverUuid), enabled)
                .apply()
        }
    }
}
