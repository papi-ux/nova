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
        val old = this.options.orEmpty()
        val migration = com.papi.nova.preferences.NovaSettingsMigration
        // Gson stores numbers as doubles; preference editors write integers. A type-only
        // representation change is not a player edit and must not select Custom.
        fun sameValue(a: Any?, b: Any?) = if(a is Number && b is Number) a.toDouble()==b.toDouble() else a==b
        val changed = migration.STREAM_KEYS.any { !sameValue(old[it],options?.get(it)) }
        val explicitTier = options?.get(migration.TIER)?.let { it != old[migration.TIER] } == true
        val updated = if (changed && options != null && !explicitTier) options + mapOf(
            migration.TIER to "custom", migration.CUSTOM_EXISTS to true) else options
        this.options = updated?.let { values ->
            val bitrateChanged = !sameValue(old["seekbar_bitrate_kbps"],values["seekbar_bitrate_kbps"])
            val raw = runCatching { com.papi.nova.preferences.PreferenceConfiguration.getDefaultBitrate(
                values["list_resolution"] as? String ?: "1920x1080", values["list_fps"] as? String ?: "60") }.getOrNull()
            val bitrate = (values["seekbar_bitrate_kbps"] as? Number)?.toInt()
            val pointChanged = listOf("list_resolution","list_fps","video_format").any { old[it] != values[it] }
            val automatic = values[migration.CUSTOM_AUTO] == true &&
                (old[migration.CUSTOM_AUTO] != true || pointChanged || bitrate == raw ||
                    (raw != null && bitrate == ((raw+4999)/5000)*5000))
            migration.savedSetup(if (bitrateChanged && bitrate != null && !automatic) values +
                mapOf(migration.AUTO to false, migration.CUSTOM_AUTO to false) else values)
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
