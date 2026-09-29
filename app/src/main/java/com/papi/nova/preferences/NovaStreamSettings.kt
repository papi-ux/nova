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

    fun custom(values: Map<String, Any?>): NovaStreamPlan? {
        if (values.keys.none { it in NovaSettingsMigration.STREAM_KEYS }) return null
        val size = (values["edit_diy_w_h"] as? String)?.takeIf { Regex("[1-9][0-9]*x[1-9][0-9]*").matches(it) }
            ?: values["list_resolution"] as? String ?: "1920x1080"
        val dimensions = size.split('x').mapNotNull { it.toIntOrNull() }
        if (dimensions.size != 2 || dimensions.any { it !in 1..8192 }) return null
        fun validFps(key: String) = (values[key] as? String)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..1000f }
        val fps = (validFps("custom_refresh_rate") ?: validFps("list_fps") ?: 60f).roundToInt()
        val codec = NovaCodecChoice.fromPreference(values["video_format"] as? String)
        val bitrate = (values["seekbar_bitrate_kbps"] as? Number)?.toInt()?.takeIf { it > 0 }
            ?: NovaBitrateAdvice.table(dimensions[0], dimensions[1], fps)
        return NovaStreamPlan(dimensions[0], dimensions[1], fps, codec, bitrate,
            if (values[NovaSettingsMigration.AUTO] == true) NovaBitrateBasis.TABLE_V1 else NovaBitrateBasis.CUSTOM)
    }

    fun tiers(context: Context): NovaStreamTiers {
        NovaSettingsMigration.apply(context)
        val values = com.papi.nova.profiles.ProfilesManager.getInstance().getOverlayingSharedPreferences(context).all
        return NovaStreamTiers.forDevice(NovaCapabilityProbe.deviceInputs(context), custom(values))
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
        profiles.getActive()?.let { active ->
            active.selectStreamTier(tier, automatic)
            profiles.update(active)
        }
    }

    fun resolveInto(context: Context, prefs: SharedPreferences, config: PreferenceConfiguration) {
        val tier = selected(prefs.all)
        if (tier == NovaTier.CUSTOM) return
        val plan = NovaStreamTiers.forDevice(NovaCapabilityProbe.deviceInputs(context)).plan(tier)
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
        selectActiveSetupTier(NovaTier.RECOMMENDED, true)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(NovaSettingsMigration.TIER, "recommended").putBoolean(NovaSettingsMigration.AUTO, true)
            .remove("checkbox_enable_hdr").remove("checkbox_unlock_fps").remove("checkbox_full_range").apply()
    }

    fun classicWrite(prefs: SharedPreferences, key: String?) {
        if (key !in NovaSettingsMigration.STREAM_KEYS) return
        val editor = prefs.edit().putString(NovaSettingsMigration.TIER, "custom")
        if (key == "seekbar_bitrate_kbps") editor.putBoolean(NovaSettingsMigration.AUTO, false)
        editor.apply()
    }

    val metadataDefinitions = listOf(
        NovaSettingDefinition(NovaSettingsMigration.TIER, "Picture", "", "category_stream", NovaSettingType.Select),
        NovaSettingDefinition(NovaSettingsMigration.AUTO, "Auto bitrate", "", "category_stream", NovaSettingType.Toggle)
    )
    fun definition(key: String) = metadataDefinitions.firstOrNull { it.key == key }
}
