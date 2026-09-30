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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val NOVA_STREAM_UI_DEFAULT_UPDATES = listOf(
    "nova_polaris_hud" to NovaSettingValue.BooleanValue(false),
    "nova_polaris_hud_mode" to NovaSettingValue.StringValue("minimal"),
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

// A preset never writes the fps preference: the user's frame-rate choice is
// orthogonal and must survive preset switches.
internal fun novaPresetSettingUpdates(preset: StreamPreset): Map<String, NovaSettingValue> = mapOf(
    PreferenceConfiguration.RESOLUTION_PREF_STRING to NovaSettingValue.StringValue(preset.resolution),
    PreferenceConfiguration.BITRATE_PREF_STRING to NovaSettingValue.IntValue(preset.bitrateKbps),
    "video_format" to NovaSettingValue.StringValue(preset.codec)
)

internal suspend fun persistNovaStreamUiDefaults(
    store: NovaSettingsStore,
    definitions: NovaSettingsDefinitionSet
) {
    val updates = NOVA_STREAM_UI_DEFAULT_UPDATES.map { (key, value) ->
        definitions.require(key) to value
    }
    store.updateAtomically(updates, NOVA_STREAM_UI_RESET_REMOVALS)
}

class NovaSettingsViewModel(
    private val definitions: NovaSettingsDefinitionSet,
    private val store: NovaSettingsStore,
    private val resetDefinitions: NovaSettingsDefinitionSet = definitions,
    initialCategoryKey: String = definitions.categories.firstOrNull()?.key.orEmpty()
) : ViewModel() {
    private val stateMutex = Mutex()
    private var selectedCategoryKey = initialCategoryKey
    private var searchQuery = ""
    private var values: Map<String, NovaSettingValue> = emptyMap()
    private var overrideKeys: Set<String> = emptySet()
    private var resettableKeys: Set<String> = emptySet()
    private var tierInputs: NovaTierInputs? = null
    private data class TierIntent(val tier: NovaTier, val owner: Any?, val revision: Long)
    private var tierRevision = 0L
    private var tierIntent: TierIntent? = null
    private var tierResult: NovaTierSaveResult? = null
    private var tierPending = false
    private data class FineIntent(val definition: NovaSettingDefinition, val value: NovaSettingValue,
        val owner: Any?, val revision: Long, val epoch: Long)
    private data class FailedFineEdit(val intent: FineIntent, val result: NovaTierSaveResult)
    private var fineEpoch = 0L
    private var snapshotOwner: Any? = null
    private val fineIntents = linkedMapOf<Long, FineIntent>()
    // Completion of another field does not acknowledge this field's failed write.
    private val failedFineEdits = linkedMapOf<String, FailedFineEdit>()
    private val streamCompletions = linkedMapOf<Long, kotlinx.coroutines.CompletableDeferred<Unit>>()

    private val mutableUiState = MutableStateFlow(
        NovaSettingsUiStateFactory.build(
            NovaTierControls.definitions(definitions,NovaTierRuntime.pendingTiers,NovaTier.CUSTOM),
            values + (NovaTierControls.QUALITY_KEY to NovaSettingValue.StringValue("custom")), selectedCategoryKey, searchQuery
        ).copy(generatedQuality = definitions.find(NovaTierControls.QUALITY_KEY) != null)
    )
    val uiState: StateFlow<NovaSettingsUiState> = mutableUiState.asStateFlow()
    private val mutableTiers = MutableStateFlow<NovaStreamTiers?>(null)
    val streamTiers: StateFlow<NovaStreamTiers?> = mutableTiers.asStateFlow()
    val pictureTier: NovaTier get() = NovaStreamSettings.selected(rawValues(displayValues()))
    val bitrateText: String get() {
        val plan = if (pictureTier == NovaTier.CUSTOM) NovaStreamSettings.custom(rawValues(displayValues()))
            else mutableTiers.value?.plan(pictureTier)
        return plan?.let { NovaBitrateAdvice.text(it.bitrateKbps,
            pictureTier != NovaTier.CUSTOM || NovaStreamSettings.customAutomatic(rawValues())) }.orEmpty()
    }

    fun selectPictureTier(tier: NovaTier) = setValue(requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.TIER)),
        NovaSettingValue.StringValue(tier.name.lowercase()))

    fun useRecommended() = selectPictureTier(NovaTier.RECOMMENDED)

    private fun rawValues(source: Map<String, NovaSettingValue> = values): Map<String, Any?> = source.mapValues { (_, value) -> when (value) {
        is NovaSettingValue.StringValue -> value.value
        is NovaSettingValue.IntValue -> value.value
        is NovaSettingValue.BooleanValue -> value.value
        is NovaSettingValue.StringSetValue -> value.value
    } }

    private fun displayValues(): Map<String, NovaSettingValue> = fineIntents.values
        .filter { it.epoch == fineEpoch && it.owner == snapshotOwner && it.owner == store.tierOwner() }
        .fold(values) { current, intent -> current + streamEditUpdates(current, intent.definition, intent.value) }

    /** Launch preflight reads preferences only after all independently owned local edits settle. */
    suspend fun awaitStreamWrites() {
        while (streamCompletions.isNotEmpty()) streamCompletions.values.toList().forEach { it.await() }
    }


    init {
        refresh()
        viewModelScope.launch { store.tierUpdates?.collect { stateMutex.withLock { loadStoreState(); emit() } } }
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

    fun setValue(
        definition: NovaSettingDefinition,
        value: NovaSettingValue,
        onCompleted: () -> Unit = {}
    ) {
        if (definition.key == NovaTierControls.QUALITY_KEY && (value as? NovaSettingValue.StringValue)?.value == "retry_tier") {
            if (failedFineEdits.isNotEmpty()) {
                val failed = failedFineEdits.values.sortedBy { it.intent.revision }
                if (failed.any { it.intent.owner != store.tierOwner() || it.intent.epoch != fineEpoch }) {
                    failedFineEdits.clear(); tierIntent = null
                    tierResult = NovaTierSaveResult.SUPERSEDED; emit(); onCompleted(); return
                }
                var remaining = failed.size
                failed.forEach { edit ->
                    setValue(edit.intent.definition, edit.intent.value) {
                        remaining--
                        if (remaining == 0) onCompleted()
                    }
                }
                return
            }
            val intent = tierIntent
            if (intent == null || intent.owner != store.tierOwner() || intent.revision != tierRevision) {
                tierIntent = null; tierResult = NovaTierSaveResult.SUPERSEDED; emit(); onCompleted(); return
            }
            setValue(requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.TIER)),
                NovaSettingValue.StringValue(intent.tier.name.lowercase()), onCompleted)
            return
        }
        val tier = (value as? NovaSettingValue.StringValue)?.value?.let { name -> NovaTier.entries.firstOrNull { it.name.equals(name,true) } }
        val selection = tier != null && definition.key in setOf(NovaTierControls.QUALITY_KEY, NovaSettingsMigration.TIER)
        val revision = if (selection || definition.key in NovaSettingsMigration.STREAM_KEYS || definition.key == NovaSettingsMigration.AUTO)
            ++tierRevision else tierRevision
        val owner = store.tierOwner()
        if (owner != snapshotOwner) {
            tierResult = NovaTierSaveResult.SUPERSEDED; emit(); refresh(); onCompleted(); return
        }
        val intent = if (selection) TierIntent(tier!!, owner, revision) else null
        if (selection) { fineEpoch++; fineIntents.clear(); failedFineEdits.clear() }
        val fine = if (!selection && (definition.key in NovaSettingsMigration.STREAM_KEYS || definition.key == NovaSettingsMigration.AUTO))
            FineIntent(definition,value,owner,revision,fineEpoch) else null
        if (fine != null) {
            // A deliberate replacement supersedes only the failed choice for this field.
            failedFineEdits.remove(definition.key)
            fineIntents[revision] = fine
        }
        val completion = if (selection || fine != null) kotlinx.coroutines.CompletableDeferred<Unit>().also {
            streamCompletions[revision] = it; tierPending = true
        } else null
        if (!selection && revision == tierRevision && (definition.key in NovaSettingsMigration.STREAM_KEYS || definition.key == NovaSettingsMigration.AUTO)) {
            tierIntent = null; tierResult = fineFailureResult()
        }
        emit()
        viewModelScope.launch {
            try {
                stateMutex.withLock {
                    if (intent != null) {
                        if (NovaTierControls.canSelect(mutableTiers.value, intent.tier)) saveTier(intent)
                    } else if (fine != null) {
                        val newerSameField = fineIntents.values.any { it.revision > revision && it.definition.key == definition.key }
                        if (fine.epoch == fineEpoch && !newerSameField && owner == store.tierOwner()) {
                            loadStoreState()
                            tierPending = true; emit()
                            val result = try { persistStreamEdit(definition, value, owner) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { NovaTierSaveResult.FAILED }
                            val replaced = fine.epoch != fineEpoch || owner != store.tierOwner() ||
                                fineIntents.values.any { it.revision > revision && it.definition.key == definition.key }
                            if (!replaced) {
                                if (result in setOf(NovaTierSaveResult.FAILED, NovaTierSaveResult.PROFILE_FAILED))
                                    failedFineEdits[definition.key] = FailedFineEdit(fine, result)
                                tierResult = fineFailureResult() ?: result
                            } else if (revision == tierRevision) {
                                tierResult = fineFailureResult() ?: NovaTierSaveResult.SUPERSEDED
                            }
                        } else if (revision == tierRevision) {
                            tierResult = fineFailureResult() ?: NovaTierSaveResult.SUPERSEDED
                            tierIntent = null
                        }
                    } else {
                        store.set(definition, value)
                        values = values + (definition.key to value)
                        applyPresetIfNeeded(definition, value)
                    }
                    fineIntents.remove(revision)
                    loadStoreState()
                    emit()
                }
            } finally {
                fineIntents.remove(revision)
                streamCompletions.remove(revision)
                tierPending = streamCompletions.isNotEmpty()
                emit()
                completion?.complete(Unit)
                onCompleted()
            }
        }
    }

    private fun fineFailureResult(): NovaTierSaveResult? = when {
        failedFineEdits.values.any { it.result == NovaTierSaveResult.FAILED } -> NovaTierSaveResult.FAILED
        failedFineEdits.isNotEmpty() -> NovaTierSaveResult.PROFILE_FAILED
        else -> null
    }

    private suspend fun saveTier(intent: TierIntent) {
        if (intent.revision != tierRevision || intent.owner != store.tierOwner()) return
        tierIntent = intent; tierResult = null; tierPending = true; emit()
        val result = try { store.saveTier(intent.tier, intent.owner) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { NovaTierSaveResult.FAILED }
        if (intent.revision == tierRevision) {
            tierResult = if (intent.owner == store.tierOwner()) result else NovaTierSaveResult.SUPERSEDED
            if (tierResult == NovaTierSaveResult.SUPERSEDED) tierIntent = null
        }
    }

    fun resetValue(definition: NovaSettingDefinition) {
        if (definition.key == NovaTierControls.QUALITY_KEY) { useRecommended(); return }
        viewModelScope.launch {
            stateMutex.withLock {
                store.reset(definition)
                loadStoreState()
                emit()
            }
        }
    }

    fun resetStreamUiDefaults() {
        viewModelScope.launch {
            stateMutex.withLock {
                persistNovaStreamUiDefaults(store, resetDefinitions)
                loadStoreState()
                emit()
            }
        }
    }

    fun refresh(onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                stateMutex.withLock {
                    loadStoreState()
                    emit()
                }
            } finally {
                onCompleted()
            }
        }
    }

    private suspend fun loadStoreState() {
        do {
            val before = store.tierOwner()
            val next = store.snapshot(definitions)
            val nextOwner = store.tierOwner()
            if (before == nextOwner) {
                if (snapshotOwner != nextOwner) {
                    fineEpoch++; fineIntents.clear(); failedFineEdits.clear(); tierIntent = null
                }
                snapshotOwner = nextOwner; values = next; break
            }
        } while (true)
        values = values + (NovaSettingsMigration.AUTO to NovaSettingValue.BooleanValue(
            NovaStreamSettings.selected(rawValues()) != NovaTier.CUSTOM || NovaStreamSettings.customAutomatic(rawValues())))
        overrideKeys = store.overrideKeys(definitions)
        resettableKeys = store.resettableKeys(definitions)
        store.deviceTierInputs()?.let { inputs ->
            tierInputs = inputs
            val storedKeys = store.storedStreamKeys()
            val customValues = rawValues().filterKeys { storedKeys == null || it !in NovaSettingsMigration.STREAM_KEYS || it in storedKeys }
            mutableTiers.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                NovaStreamTiers.forDevice(inputs, NovaStreamSettings.custom(customValues))
            }
        }
    }

    private fun streamEditUpdates(source: Map<String,NovaSettingValue>, definition: NovaSettingDefinition,
        value: NovaSettingValue): Map<String,NovaSettingValue> {
        val updates = mutableMapOf<String, NovaSettingValue>()
        val selected = NovaStreamSettings.selected(rawValues(source))
        if (selected != NovaTier.CUSTOM) mutableTiers.value?.plan(selected)?.let { plan ->
            updates["list_resolution"] = NovaSettingValue.StringValue("${plan.width}x${plan.height}")
            updates["list_fps"] = NovaSettingValue.StringValue(plan.fps.toString())
            updates["video_format"] = NovaSettingValue.StringValue("auto")
            updates["seekbar_bitrate_kbps"] = NovaSettingValue.IntValue(plan.bitrateKbps)
        }
        updates[definition.key] = value
        updates[NovaSettingsMigration.TIER] = NovaSettingValue.StringValue("custom")
        val automatic = when (definition.key) {
            "seekbar_bitrate_kbps" -> false
            NovaSettingsMigration.AUTO -> (value as? NovaSettingValue.BooleanValue)?.value == true
            else -> selected != NovaTier.CUSTOM || NovaStreamSettings.customAutomatic(rawValues(source))
        }
        updates[NovaSettingsMigration.AUTO] = NovaSettingValue.BooleanValue(automatic)
        updates[NovaSettingsMigration.CUSTOM_AUTO] = NovaSettingValue.BooleanValue(automatic)
        updates[NovaSettingsMigration.CUSTOM_EXISTS] = NovaSettingValue.BooleanValue(true)
        if (automatic) NovaStreamSettings.custom(rawValues(source + updates))?.let { plan ->
            val advice = NovaBitrateAdvice.recommend(plan.width, plan.height, plan.fps, plan.codec, tierInputs?.distance ?: NovaDistance.HAND)
            updates["seekbar_bitrate_kbps"] = NovaSettingValue.IntValue(advice.kbps)
        }
        return updates
    }

    private suspend fun persistStreamEdit(definition: NovaSettingDefinition, value: NovaSettingValue, expectedOwner: Any?): NovaTierSaveResult {
        val updates = streamEditUpdates(values, definition, value)
        val resolved = updates.map { (key, update) ->
            (definitions.find(key) ?: resetDefinitions.find(key) ?: NovaStreamSettings.definition(key)
                ?: error("Missing stream definition $key")) to update
        }
        return store.saveStreamEdits(resolved, emptySet(), expectedOwner)
    }

    private suspend fun applyPresetIfNeeded(
        definition: NovaSettingDefinition,
        value: NovaSettingValue
    ) {
        if (definition.key != "nova_stream_preset" || value !is NovaSettingValue.StringValue) return

        val preset = StreamPreset.fromKey(value.value) ?: return
        val updates = novaPresetSettingUpdates(preset)
        for ((key, settingValue) in updates) {
            val presetDefinition = definitions.find(key) ?: continue
            store.set(presetDefinition, settingValue)
        }
        store.updateAtomically(listOf(
            requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.TIER)) to NovaSettingValue.StringValue("custom"),
            requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.AUTO)) to NovaSettingValue.BooleanValue(false),
            requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.CUSTOM_AUTO)) to NovaSettingValue.BooleanValue(false),
            requireNotNull(NovaStreamSettings.definition(NovaSettingsMigration.CUSTOM_EXISTS)) to NovaSettingValue.BooleanValue(true)
        ))
        values = values + updates
    }

    private fun emit() {
        val projected = displayValues()
        val tiers = mutableTiers.value?.copy(custom = NovaStreamSettings.custom(rawValues(projected)))
        val tier = pictureTier
        val generated = tiers != null && definitions.find(NovaTierControls.QUALITY_KEY) != null
        val tierMessage = when {
            tierPending -> "Saving this choice"
            tierResult == NovaTierSaveResult.SAVED -> "Saved"
            tierResult == NovaTierSaveResult.PROFILE_FAILED -> "Device applied · saved setup could not be saved"
            tierResult == NovaTierSaveResult.FAILED -> "Could not save this choice. Try again"
            tierResult == NovaTierSaveResult.SUPERSEDED -> "Another change superseded this save"
            else -> null
        }
        val shownDefinitions = (if (generated) NovaTierControls.definitions(definitions, tiers!!, tier) else definitions).let { projected ->
            if (tierMessage == null) projected else projected.copy(settings = projected.settings.map { definition ->
                if (definition.key != NovaTierControls.QUALITY_KEY) definition else definition.copy(summary = tierMessage,
                    options = definition.options + if ((tierIntent != null || failedFineEdits.isNotEmpty()) &&
                        tierResult in setOf(NovaTierSaveResult.FAILED,NovaTierSaveResult.PROFILE_FAILED))
                        listOf(NovaSettingOption("Try again", "retry_tier", caption = tierMessage)) else emptyList())
            })
        }
        val shownValues = if (generated) NovaTierControls.displayValues(projected, tiers!!, tier) else projected
        mutableUiState.value = NovaSettingsUiStateFactory.build(
            definitions = shownDefinitions,
            values = shownValues,
            selectedCategoryKey = selectedCategoryKey,
            searchQuery = searchQuery,
            overrideKeys = overrideKeys,
            resettableKeys = resettableKeys + if (generated && tier != NovaTier.RECOMMENDED &&
                NovaTierControls.canSelect(tiers, NovaTier.RECOMMENDED)) setOf(NovaTierControls.QUALITY_KEY) else emptySet()
        ).copy(generatedQuality = generated, tierSavePending = tierPending, tierSaveResult = tierResult,
            deviceStreamSettings = shownDefinitions.settings.filter { it.key == NovaTierControls.QUALITY_KEY ||
                it.key in NovaSettingsMigration.STREAM_KEYS || it.key == NovaSettingsMigration.AUTO },
            bitrateAuto = if (generated) tier != NovaTier.CUSTOM || NovaStreamSettings.customAutomatic(rawValues(projected)) else null)
    }

    class Factory(
        private val definitions: NovaSettingsDefinitionSet,
        private val store: NovaSettingsStore,
        private val resetDefinitions: NovaSettingsDefinitionSet = definitions,
        private val initialCategoryKey: String = definitions.categories.firstOrNull()?.key.orEmpty()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NovaSettingsViewModel(
                definitions = definitions,
                store = store,
                resetDefinitions = resetDefinitions,
                initialCategoryKey = initialCategoryKey
            ) as T
        }
    }
}
