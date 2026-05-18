package com.papi.nova.preferences

data class NovaSettingsUiState(
    val categories: List<NovaSettingCategory>,
    val selectedCategoryKey: String,
    val searchQuery: String,
    val searchResultCount: Int,
    val quickSettings: List<NovaSettingDefinition>,
    val visibleSettings: List<NovaSettingDefinition>,
    val values: Map<String, NovaSettingValue>,
    val overrideKeys: Set<String> = emptySet(),
    val resettableKeys: Set<String> = emptySet()
) {
    fun isSearchActive(): Boolean = searchQuery.isNotBlank()

    fun isOverride(definition: NovaSettingDefinition): Boolean = definition.key in overrideKeys

    fun canReset(definition: NovaSettingDefinition): Boolean = definition.key in resettableKeys
}

object NovaSettingsUiStateFactory {
    private val quickSettingKeys = listOf(
        "nova_stream_preset",
        PreferenceConfiguration.RESOLUTION_PREF_STRING,
        PreferenceConfiguration.FPS_PREF_STRING,
        PreferenceConfiguration.BITRATE_PREF_STRING,
        "video_format",
        "frame_pacing"
    )

    private val aliases = mapOf(
        "checkbox_enable_hdr" to listOf("hdr", "10 bit", "10-bit", "color", "sdr"),
        PreferenceConfiguration.BITRATE_PREF_STRING to listOf("bitrate", "mbps", "bandwidth"),
        PreferenceConfiguration.RESOLUTION_PREF_STRING to listOf("resolution", "width", "height", "display"),
        PreferenceConfiguration.FPS_PREF_STRING to listOf("fps", "frame rate", "refresh"),
        "video_format" to listOf("codec", "h264", "h265", "hevc", "av1"),
        "frame_pacing" to listOf("latency", "smoothness", "frame pacing"),
        "nova_polaris_hud" to listOf("hud", "overlay", "stats"),
        "checkbox_enable_rumble" to listOf("rumble", "vibration", "haptics")
    )

    fun build(
        definitions: NovaSettingsDefinitionSet,
        values: Map<String, NovaSettingValue>,
        selectedCategoryKey: String,
        searchQuery: String,
        overrideKeys: Set<String> = emptySet(),
        resettableKeys: Set<String> = emptySet()
    ): NovaSettingsUiState {
        val normalizedQuery = searchQuery.trim().lowercase()
        val quickSettings = quickSettingKeys.mapNotNull(definitions::find)
        val visibleSettings = if (normalizedQuery.isBlank()) {
            definitions.settingsForCategory(selectedCategoryKey)
        } else {
            definitions.settings.filter { definition ->
                definition.matches(normalizedQuery, values[definition.key])
            }
        }

        return NovaSettingsUiState(
            categories = definitions.categories,
            selectedCategoryKey = selectedCategoryKey,
            searchQuery = searchQuery,
            searchResultCount = if (normalizedQuery.isBlank()) 0 else visibleSettings.size,
            quickSettings = quickSettings,
            visibleSettings = visibleSettings,
            values = values,
            overrideKeys = overrideKeys,
            resettableKeys = resettableKeys
        )
    }

    private fun NovaSettingDefinition.matches(query: String, value: NovaSettingValue?): Boolean {
        val haystack = buildString {
            append(title.lowercase()).append(' ')
            append(summary.lowercase()).append(' ')
            append(categoryKey.lowercase()).append(' ')
            append(value.asSearchText()).append(' ')
            append(options.joinToString(" ") { it.label.lowercase() + " " + it.value.lowercase() })
            append(' ')
            append(aliases[key].orEmpty().joinToString(" "))
        }
        return haystack.contains(query)
    }

    private fun NovaSettingValue?.asSearchText(): String {
        return when (this) {
            is NovaSettingValue.BooleanValue -> value.toString()
            is NovaSettingValue.IntValue -> value.toString()
            is NovaSettingValue.StringValue -> value.lowercase()
            is NovaSettingValue.StringSetValue -> value.joinToString(" ").lowercase()
            null -> ""
        }
    }
}

object NovaSettingsValidator {
    fun isValidTextValue(key: String, value: String): Boolean {
        val trimmed = value.trim()
        return when (key) {
            PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING -> isValidResolution(trimmed)
            PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING -> isValidRefreshRate(trimmed)
            PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING -> isValidManualBitrate(trimmed)
            else -> trimmed.isNotEmpty()
        }
    }

    private fun isValidResolution(value: String): Boolean {
        val parts = value.lowercase().split("x")
        if (parts.size != 2) return false
        val width = parts[0].toIntOrNull() ?: return false
        val height = parts[1].toIntOrNull() ?: return false
        return width > 0 && height > 0
    }

    private fun isValidRefreshRate(value: String): Boolean {
        val refreshRate = value.toFloatOrNull() ?: return false
        return refreshRate > 0f && refreshRate <= 240f
    }

    private fun isValidManualBitrate(value: String): Boolean {
        val bitrate = value.toFloatOrNull() ?: return false
        return bitrate > 0f && bitrate <= 300f
    }
}
