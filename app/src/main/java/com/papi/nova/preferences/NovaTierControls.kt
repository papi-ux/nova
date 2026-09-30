package com.papi.nova.preferences

/** A projection of the prepared engine, never a second set of saved stream defaults. */
internal object NovaTierControls {
    const val QUALITY_KEY = "nova_stream_preset"

    fun options(tiers: NovaStreamTiers): List<NovaSettingOption> = buildList {
        NovaTier.entries.filter { it != NovaTier.MAX || !tiers.mergedMax }.forEach { tier ->
            val plan = tiers.plan(tier)
            val missingCustom = tier == NovaTier.CUSTOM && tiers.custom == null
            val reason = when {
                missingCustom -> "Change a stream value to create Custom"
                !plan.available -> plan.limits.firstOrNull()?.message ?: plan.reasons.firstOrNull()?.message
                    ?: "This device cannot decode this choice"
                else -> null
            }
            add(NovaSettingOption(tier.name.lowercase().replaceFirstChar(Char::uppercase), tier.name.lowercase(),
                caption = if (missingCustom) null else listOfNotNull(plan.numbers,
                    plan.reasons.firstOrNull { it.code == "above_native" }?.message,
                    plan.limits.firstOrNull()?.message).joinToString(" · "), disabledReason = reason))
        }
        (tiers.fourK as? NovaFourK.Unavailable)?.let {
            add(NovaSettingOption("4K", "four_k", disabledReason = it.because.message))
        }
    }

    fun canSelect(tiers: NovaStreamTiers?, tier: NovaTier): Boolean = tiers != null &&
        (tier != NovaTier.CUSTOM || tiers.custom != null) && tiers.plan(tier).available

    fun caption(tiers: NovaStreamTiers, tier: NovaTier): String {
        val plan = tiers.plan(tier)
        val source = if (tier == NovaTier.RECOMMENDED) "Recommended for this device" else "Your device setting"
        if (tier == NovaTier.CUSTOM && tiers.custom != null) return NovaStreamTiers.customDelta(tiers.custom, tiers.recommended)
        // Max is an explicit above-panel choice. Keep that explanation even when its
        // requested cadence also hit a decoder ceiling; the option retains both facts.
        return plan.reasons.firstOrNull { it.code == "above_native" }?.message
            ?: plan.limits.firstOrNull()?.message?.takeIf { it.length <= 56 }
            ?: plan.reasons.firstOrNull()?.message?.takeIf { it.length <= 56 } ?: source
    }

    fun definitions(original: NovaSettingsDefinitionSet, tiers: NovaStreamTiers, tier: NovaTier): NovaSettingsDefinitionSet {
        val category = original.find(QUALITY_KEY)?.categoryKey ?: return original
        val settings = original.settings.flatMap { definition ->
            when (definition.key) {
                QUALITY_KEY -> listOf(definition.copy(title = "Quality", summary = caption(tiers, tier), options = options(tiers)))
                PreferenceConfiguration.BITRATE_PREF_STRING -> listOf(definition,
                    requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.AUTO)).copy(categoryKey = category))
                else -> listOf(definition)
            }
        }
        return original.copy(settings = settings)
    }

    fun displayValues(values: Map<String, NovaSettingValue>, tiers: NovaStreamTiers, tier: NovaTier): Map<String, NovaSettingValue> {
        val projected = values + (QUALITY_KEY to NovaSettingValue.StringValue(tier.name.lowercase()))
        if (tier == NovaTier.CUSTOM) return projected
        val plan = tiers.plan(tier).takeIf { it.available } ?: return projected
        return projected + mapOf(
            "list_resolution" to NovaSettingValue.StringValue("${plan.width}x${plan.height}"),
            "list_fps" to NovaSettingValue.StringValue(plan.fps.toString()),
            "seekbar_bitrate_kbps" to NovaSettingValue.IntValue(plan.bitrateKbps),
            "video_format" to NovaSettingValue.StringValue("auto"))
    }
}
