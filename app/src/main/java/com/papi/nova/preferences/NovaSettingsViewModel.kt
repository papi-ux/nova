package com.papi.nova.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.papi.nova.ui.NovaHudPreferences
import com.papi.nova.ui.NovaMenuPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val NOVA_STREAM_UI_DEFAULT_UPDATES = listOf(
    "nova_polaris_hud" to NovaSettingValue.BooleanValue(false),
    "nova_polaris_hud_mode" to NovaSettingValue.StringValue("minimal"),
    "nova_polaris_hud_position" to NovaSettingValue.StringValue("top_left"),
    NovaHudPreferences.KEY_OPACITY to NovaSettingValue.IntValue(NovaHudPreferences.DEFAULT_OPACITY_PERCENT),
    NovaMenuPreferences.KEY_OPACITY to NovaSettingValue.IntValue(NovaMenuPreferences.DEFAULT_OPACITY_PERCENT),
    "checkbox_enable_perf_overlay" to NovaSettingValue.BooleanValue(false),
    "checkbox_enable_perf_logging" to NovaSettingValue.BooleanValue(false),
    "checkbox_show_onscreen_controls" to NovaSettingValue.BooleanValue(false),
    "seekbar_osc_opacity" to NovaSettingValue.IntValue(90),
    "checkbox_enable_keyboard" to NovaSettingValue.BooleanValue(false),
    "checkbox_enable_floating_button" to NovaSettingValue.BooleanValue(false),
    "checkbox_show_overlay_zoom_toggle_button" to NovaSettingValue.BooleanValue(false),
    "checkbox_disable_warnings" to NovaSettingValue.BooleanValue(false)
)

internal val NOVA_STREAM_UI_RESET_REMOVALS = setOf(
    "nova_polaris_hud_x",
    "nova_polaris_hud_y"
)

internal suspend fun persistNovaStreamUiDefaults(
    store: NovaSettingsStore,
    definitions: NovaSettingsDefinitionSet
) {
    val updates = NOVA_STREAM_UI_DEFAULT_UPDATES.mapNotNull { (key, value) ->
        definitions.find(key)?.let { definition -> definition to value }
    }
    store.updateAtomically(updates, NOVA_STREAM_UI_RESET_REMOVALS)
}

class NovaSettingsViewModel(
    private val definitions: NovaSettingsDefinitionSet,
    private val store: NovaSettingsStore,
    initialCategoryKey: String = definitions.categories.firstOrNull()?.key.orEmpty()
) : ViewModel() {
    private var selectedCategoryKey = initialCategoryKey
    private var searchQuery = ""
    private var values: Map<String, NovaSettingValue> = emptyMap()
    private var overrideKeys: Set<String> = emptySet()
    private var resettableKeys: Set<String> = emptySet()

    private val mutableUiState = MutableStateFlow(
        NovaSettingsUiStateFactory.build(definitions, values, selectedCategoryKey, searchQuery)
    )
    val uiState: StateFlow<NovaSettingsUiState> = mutableUiState.asStateFlow()

    init {
        refresh()
    }

    fun selectCategory(categoryKey: String) {
        selectedCategoryKey = categoryKey
        emit()
    }

    fun updateSearch(query: String) {
        searchQuery = query
        emit()
    }

    fun clearSearch() {
        searchQuery = ""
        emit()
    }

    fun setValue(definition: NovaSettingDefinition, value: NovaSettingValue) {
        viewModelScope.launch {
            store.set(definition, value)
            values = values + (definition.key to value)
            applyPresetIfNeeded(definition, value)
            loadStoreState()
            emit()
        }
    }

    fun resetValue(definition: NovaSettingDefinition) {
        viewModelScope.launch {
            store.reset(definition)
            loadStoreState()
            emit()
        }
    }

    fun resetStreamUiDefaults() {
        viewModelScope.launch {
            persistNovaStreamUiDefaults(store, definitions)
            loadStoreState()
            emit()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadStoreState()
            emit()
        }
    }

    private suspend fun loadStoreState() {
        values = store.snapshot(definitions)
        overrideKeys = store.overrideKeys(definitions)
        resettableKeys = store.resettableKeys(definitions)
    }

    private suspend fun applyPresetIfNeeded(
        definition: NovaSettingDefinition,
        value: NovaSettingValue
    ) {
        if (definition.key != "nova_stream_preset" || value !is NovaSettingValue.StringValue) return

        val preset = StreamPreset.fromKey(value.value) ?: return
        val updates = mapOf(
            PreferenceConfiguration.RESOLUTION_PREF_STRING to NovaSettingValue.StringValue(preset.resolution),
            PreferenceConfiguration.FPS_PREF_STRING to NovaSettingValue.StringValue(preset.fps),
            PreferenceConfiguration.BITRATE_PREF_STRING to NovaSettingValue.IntValue(preset.bitrateKbps),
            "video_format" to NovaSettingValue.StringValue(preset.codec)
        )
        for ((key, settingValue) in updates) {
            val presetDefinition = definitions.find(key) ?: continue
            store.set(presetDefinition, settingValue)
        }
        values = values + updates
    }

    private fun emit() {
        mutableUiState.value = NovaSettingsUiStateFactory.build(
            definitions = definitions,
            values = values,
            selectedCategoryKey = selectedCategoryKey,
            searchQuery = searchQuery,
            overrideKeys = overrideKeys,
            resettableKeys = resettableKeys
        )
    }

    class Factory(
        private val definitions: NovaSettingsDefinitionSet,
        private val store: NovaSettingsStore,
        private val initialCategoryKey: String = definitions.categories.firstOrNull()?.key.orEmpty()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NovaSettingsViewModel(definitions, store, initialCategoryKey) as T
        }
    }
}
