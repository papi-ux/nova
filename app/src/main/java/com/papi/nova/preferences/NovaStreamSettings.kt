package com.papi.nova.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlin.math.roundToInt

/** Shared data API for Settings, launch readers and the separately owned UI. */
object NovaStreamSettings {
    fun selected(values: Map<String, Any?>): NovaTier = NovaTier.entries.firstOrNull {
        it.name.equals(values[NovaSettingsMigration.TIER] as? String, true)
    } ?: NovaTier.CUSTOM

    fun customAutomatic(values: Map<String, Any?>): Boolean =
        values[NovaSettingsMigration.CUSTOM_AUTO] as? Boolean ?: values[NovaSettingsMigration.AUTO] as? Boolean ?:
            ((values["seekbar_bitrate_kbps"] as? Number)?.toInt()?.let { it <= 0 } ?: true)

    fun custom(values: Map<String, Any?>): NovaStreamPlan? {
        if (values.keys.none { it in NovaSettingsMigration.STREAM_KEYS }) return null
        if (values[NovaSettingsMigration.CUSTOM_EXISTS] == false) return null
        val storedSize = values["list_resolution"] as? String ?: "1920x1080"
        val size = if ('x' in storedSize) storedSize else PreferenceConfiguration.convertFromLegacyResolutionString(storedSize)
        val dimensions = size.split('x').mapNotNull { it.toIntOrNull() }
        if (dimensions.size != 2 || dimensions.any { it !in 1..8192 }) return null
        val fps = (values["list_fps"] as? String)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..1000f }?.roundToInt() ?: 60
        val codec = NovaCodecChoice.fromPreference(values["video_format"] as? String)
        val bitrate = (values["seekbar_bitrate_kbps"] as? Number)?.toInt()?.takeIf { it > 0 }
            ?: PreferenceConfiguration.getDefaultBitrate(size, fps.toString())
        return NovaStreamPlan(dimensions[0], dimensions[1], fps, codec, bitrate,
            if (customAutomatic(values)) NovaBitrateBasis.TABLE_V1 else NovaBitrateBasis.CUSTOM)
    }

    fun tiers(context: Context): NovaStreamTiers {
        NovaSettingsMigration.apply(context)
        val values = com.papi.nova.profiles.ProfilesManager.getInstance().getOverlayingSharedPreferences(context).all
        return (NovaTierRuntime.snapshot()?.tiers ?: NovaTierRuntime.pendingTiers).copy(custom = custom(values))
    }

    fun select(context: Context, tier: NovaTier) {
        NovaSettingsMigration.apply(context)
        selectActiveSetupTier(tier)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(NovaSettingsMigration.TIER, tier.name.lowercase())
            .apply() // The six Custom keys and their Auto state are deliberately retained.
    }

    internal fun selectActiveSetupTier(tier: NovaTier, automatic: Boolean? = null) {
        val profiles = com.papi.nova.profiles.ProfilesManager.getInstance()
        profiles.getActive()?.takeIf { it.getOptions()?.keys?.any { key -> key in NovaSettingsMigration.STREAM_KEYS } == true }?.let { active ->
            active.selectStreamTier(tier, automatic)
            profiles.update(active)
        }
    }

    fun generatedPlan(prefs: SharedPreferences): NovaStreamPlan? {
        val tier = NovaTier.entries.firstOrNull { it.name.equals(prefs.getString(NovaSettingsMigration.TIER, "custom"), true) } ?: NovaTier.CUSTOM
        if (tier == NovaTier.CUSTOM) return null
        return NovaTierRuntime.snapshot()?.tiers?.plan(tier)
    }

    fun resolveInto(context: Context, prefs: SharedPreferences, config: PreferenceConfiguration) {
        val plan = generatedPlan(prefs) ?: return
        if (!plan.available) return
        config.width = plan.width; config.height = plan.height; config.fps = plan.fps.toFloat(); config.bitrate = plan.bitrateKbps
        // Auto names the local preferred decoder but still negotiates against host support.
        config.videoFormat = PreferenceConfiguration.FormatOption.AUTO
        config.customResolution = null; config.customRefreshRate = null
        if (prefs.getInt("seekbar_metered_bitrate_kbps", 0) == 0) config.meteredBitrate = config.bitrate / 4
    }

    fun resetAfterDecoderCrash(context: Context) {
        NovaSettingsMigration.apply(context)
        NovaCapabilityProbe.recordResetFailure(context)
        NovaTierRuntime.invalidate(context)
        selectActiveSetupTier(NovaTier.RECOMMENDED)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(NovaSettingsMigration.CUSTOM_AUTO,customAutomatic(prefs.all))
            .putString(NovaSettingsMigration.TIER, "recommended")
            .remove("checkbox_enable_hdr").remove("checkbox_unlock_fps").remove("checkbox_full_range").apply()
    }

    fun classicWrite(prefs: SharedPreferences, key: String?) {
        if (key !in NovaSettingsMigration.STREAM_KEYS) return
        val editor = prefs.edit().putString(NovaSettingsMigration.TIER, "custom").putBoolean(NovaSettingsMigration.CUSTOM_EXISTS, true)
        if (key == "seekbar_bitrate_kbps") {
            val raw = runCatching { PreferenceConfiguration.getDefaultBitrate(prefs.getString("list_resolution", "1920x1080")!!,
                prefs.getString("list_fps", "60")!!) }.getOrDefault(20000)
            val value = prefs.getInt(key, 0)
            val automatic = value == raw || value == ((raw + 4999) / 5000) * 5000
            editor.putBoolean(NovaSettingsMigration.AUTO, automatic).putBoolean(NovaSettingsMigration.CUSTOM_AUTO, automatic)
        }
        selectActiveSetupTier(NovaTier.CUSTOM)
        editor.apply()
    }

    val metadataDefinitions = listOf(
        NovaSettingDefinition(NovaSettingsMigration.TIER, "Picture", "", "category_stream", NovaSettingType.Select),
        NovaSettingDefinition(NovaSettingsMigration.AUTO, "Auto bitrate", "", "category_stream", NovaSettingType.Toggle),
        NovaSettingDefinition(NovaSettingsMigration.CUSTOM_AUTO, "Custom Auto bitrate", "", "category_stream", NovaSettingType.Toggle),
        NovaSettingDefinition(NovaSettingsMigration.CUSTOM_EXISTS, "Custom stream exists", "", "category_stream", NovaSettingType.Toggle)
    )
    fun definition(key: String) = metadataDefinitions.firstOrNull { it.key == key }
}
