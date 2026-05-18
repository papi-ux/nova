package com.papi.nova.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
