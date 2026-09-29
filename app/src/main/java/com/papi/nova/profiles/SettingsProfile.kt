package com.papi.nova.profiles

import java.util.UUID

class SettingsProfile(
    private val uuid: UUID,
    private var name: String,
    private val createdUtc: Long,
    private var modifiedUtc: Long,
    private var options: Map<String, Any>?,
) {
    private var isActive = false

    init { migrateStreamOptions() }

    fun migrateStreamOptions(): Boolean {
        val migrated = options?.let(com.papi.nova.preferences.NovaSettingsMigration::savedSetup)
        if (migrated == options) return false
        options = migrated
        return true
    }

    fun getUuid(): UUID = uuid

    fun getName(): String = name

    fun setName(name: String) {
        this.name = name
    }

    fun getCreatedUtc(): Long = createdUtc

    fun getModifiedUtc(): Long = modifiedUtc

    fun setModifiedUtc(modifiedUtc: Long) {
        this.modifiedUtc = modifiedUtc
    }

    fun getOptions(): Map<String, Any>? = options?.let(com.papi.nova.preferences.NovaSettingsMigration::savedSetup)

    fun setOptions(options: Map<String, Any>?) {
        val changed = com.papi.nova.preferences.NovaSettingsMigration.STREAM_KEYS.any { this.options?.get(it) != options?.get(it) }
        val bitrateChanged = this.options?.get("seekbar_bitrate_kbps") != options?.get("seekbar_bitrate_kbps")
        val updated = if (changed && options != null) options + mapOf(com.papi.nova.preferences.NovaSettingsMigration.TIER to "custom",
            com.papi.nova.preferences.NovaSettingsMigration.CUSTOM_EXISTS to true) else options
        this.options = updated?.let { values ->
            com.papi.nova.preferences.NovaSettingsMigration.savedSetup(
                if (bitrateChanged && values.containsKey("seekbar_bitrate_kbps")) values +
                    mapOf(com.papi.nova.preferences.NovaSettingsMigration.AUTO to false,
                        com.papi.nova.preferences.NovaSettingsMigration.CUSTOM_AUTO to false) else values)
        }
    }

    /** A crash reset changes selection while retaining this setup's Custom stream values. */
    fun selectStreamTier(tier: com.papi.nova.preferences.NovaTier, automatic: Boolean? = null) {
        options = options.orEmpty() + mapOf(
            com.papi.nova.preferences.NovaSettingsMigration.TIER to tier.name.lowercase()) +
            (automatic?.let { mapOf(com.papi.nova.preferences.NovaSettingsMigration.AUTO to it,
                com.papi.nova.preferences.NovaSettingsMigration.CUSTOM_AUTO to it) } ?: emptyMap())
    }

    fun isActive(): Boolean = isActive

    fun setActive(active: Boolean) {
        isActive = active
    }
}
