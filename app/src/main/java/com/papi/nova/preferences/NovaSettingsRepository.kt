package com.papi.nova.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.preference.PreferenceManager
import java.io.File
import kotlinx.coroutines.flow.first

private val Context.novaSettingsDataStore by preferencesDataStore(name = "nova_settings")

class NovaSharedPreferencesSettingsStore(
    private val prefs: SharedPreferences,
    private val fallbackPrefs: SharedPreferences? = null
) : NovaSettingsStore {
    fun snapshot(): Map<String, NovaSettingValue> {
        return prefs.toSettingsMap()
    }

    override suspend fun snapshot(definitions: NovaSettingsDefinitionSet): Map<String, NovaSettingValue> {
        val values = fallbackPrefs?.toSettingsMap()?.toMutableMap() ?: mutableMapOf()
        values.putAll(snapshot())
        for (definition in definitions.settings) {
            if (!values.containsKey(definition.key)) {
                definition.defaultValue?.let { values[definition.key] = it }
            }
        }
        return values
    }

    override suspend fun set(definition: NovaSettingDefinition, value: NovaSettingValue) {
        prefs.edit().putSettingValue(definition.key, value).apply()
    }

    override suspend fun reset(definition: NovaSettingDefinition) {
        prefs.edit().remove(definition.key).apply()
    }

    override suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet): Set<String> {
        val fallback = fallbackPrefs ?: return emptySet()
        return definitions.settings.mapNotNull { definition ->
            val value = prefs.readSettingValue(definition) ?: return@mapNotNull null
            val baseline = fallback.readSettingValue(definition) ?: definition.defaultValue
            if (value != baseline) definition.key else null
        }.toSet()
    }

    override suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet): Set<String> {
        if (fallbackPrefs == null) return emptySet()
        return definitions.settings.mapNotNull { definition ->
            definition.key.takeIf { prefs.contains(it) }
        }.toSet()
    }

    private fun SharedPreferences.toSettingsMap(): Map<String, NovaSettingValue> {
        return all.mapNotNull { (key, value) ->
            key toSettingValue value
        }.toMap()
    }

    private infix fun String.toSettingValue(value: Any?): Pair<String, NovaSettingValue>? {
        val settingValue = when (value) {
            is Boolean -> NovaSettingValue.BooleanValue(value)
            is Int -> NovaSettingValue.IntValue(value)
            is Number -> NovaSettingValue.IntValue(value.toInt())
            is String -> NovaSettingValue.StringValue(value)
            is Set<*> -> NovaSettingValue.StringSetValue(value.filterIsInstance<String>().toSet())
            else -> return null
        }
        return this to settingValue
    }
}

interface NovaSettingsStore {
    suspend fun snapshot(definitions: NovaSettingsDefinitionSet): Map<String, NovaSettingValue>
    suspend fun set(definition: NovaSettingDefinition, value: NovaSettingValue)
    suspend fun reset(definition: NovaSettingDefinition)
    suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet): Set<String>
    suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet): Set<String>
}

class NovaSettingsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val mirrorPrefs: SharedPreferences
) : NovaSettingsStore {
    override suspend fun snapshot(definitions: NovaSettingsDefinitionSet): Map<String, NovaSettingValue> {
        ensureMigrated(definitions)
        val preferences = dataStore.data.first()
        return definitions.settings.mapNotNull { definition ->
            val value = preferences.readSettingValue(definition)
                ?: mirrorPrefs.readSettingValue(definition)
                ?: definition.defaultValue
            if (value == null) null else definition.key to value
        }.toMap()
    }

    override suspend fun set(definition: NovaSettingDefinition, value: NovaSettingValue) {
        dataStore.edit { preferences ->
            preferences.writeSettingValue(definition, value)
        }
        mirrorPrefs.edit().putSettingValue(definition.key, value).apply()
    }

    override suspend fun reset(definition: NovaSettingDefinition) {
        dataStore.edit { preferences ->
            preferences.removeSettingValue(definition)
        }
        mirrorPrefs.edit().remove(definition.key).apply()
    }

    override suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet): Set<String> = emptySet()

    override suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet): Set<String> = emptySet()

    private suspend fun ensureMigrated(definitions: NovaSettingsDefinitionSet) {
        val preferences = dataStore.data.first()
        if (preferences[MIGRATED_KEY] == true) return

        dataStore.edit { mutablePreferences ->
            for (definition in definitions.settings) {
                val value = mirrorPrefs.readSettingValue(definition) ?: continue
                mutablePreferences.writeSettingValue(definition, value)
            }
            mutablePreferences[MIGRATED_KEY] = true
        }
    }

    companion object {
        fun create(context: Context): NovaSettingsRepository {
            return NovaSettingsRepository(
                dataStore = context.novaSettingsDataStore,
                mirrorPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            )
        }

        fun createForTest(
            context: Context,
            mirrorPrefs: SharedPreferences,
            storeFile: File
        ): NovaSettingsRepository {
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = null,
                migrations = emptyList(),
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
                ),
                produceFile = { storeFile }
            )
            return NovaSettingsRepository(dataStore = dataStore, mirrorPrefs = mirrorPrefs)
        }
    }
}

private val MIGRATED_KEY = booleanPreferencesKey("__nova_settings_datastore_migrated")

private fun Preferences.readSettingValue(definition: NovaSettingDefinition): NovaSettingValue? {
    return when (definition.type) {
        NovaSettingType.Toggle -> this[booleanPreferencesKey(definition.key)]?.let {
            NovaSettingValue.BooleanValue(it)
        }
        NovaSettingType.Slider -> this[intPreferencesKey(definition.key)]?.let {
            NovaSettingValue.IntValue(it)
        }
        NovaSettingType.Select,
        NovaSettingType.Text -> this[stringPreferencesKey(definition.key)]?.let {
            NovaSettingValue.StringValue(it)
        }
        NovaSettingType.Action -> null
    }
}

private fun SharedPreferences.readSettingValue(definition: NovaSettingDefinition): NovaSettingValue? {
    if (!contains(definition.key)) return null
    return when (definition.type) {
        NovaSettingType.Toggle -> NovaSettingValue.BooleanValue(getBoolean(definition.key, false))
        NovaSettingType.Slider -> NovaSettingValue.IntValue(getInt(definition.key, 0))
        NovaSettingType.Select,
        NovaSettingType.Text -> getString(definition.key, null)?.let { NovaSettingValue.StringValue(it) }
        NovaSettingType.Action -> null
    }
}

private fun MutablePreferences.writeSettingValue(
    definition: NovaSettingDefinition,
    value: NovaSettingValue
) {
    when (value) {
        is NovaSettingValue.BooleanValue -> this[booleanPreferencesKey(definition.key)] = value.value
        is NovaSettingValue.IntValue -> this[intPreferencesKey(definition.key)] = value.value
        is NovaSettingValue.StringValue -> this[stringPreferencesKey(definition.key)] = value.value
        is NovaSettingValue.StringSetValue -> this[stringSetPreferencesKey(definition.key)] = value.value
    }
}

private fun MutablePreferences.removeSettingValue(definition: NovaSettingDefinition) {
    when (definition.type) {
        NovaSettingType.Toggle -> remove(booleanPreferencesKey(definition.key))
        NovaSettingType.Slider -> remove(intPreferencesKey(definition.key))
        NovaSettingType.Select,
        NovaSettingType.Text -> remove(stringPreferencesKey(definition.key))
        NovaSettingType.Action -> Unit
    }
}

private fun SharedPreferences.Editor.putSettingValue(
    key: String,
    value: NovaSettingValue
): SharedPreferences.Editor {
    return when (value) {
        is NovaSettingValue.BooleanValue -> putBoolean(key, value.value)
        is NovaSettingValue.IntValue -> putInt(key, value.value)
        is NovaSettingValue.StringValue -> putString(key, value.value)
        is NovaSettingValue.StringSetValue -> putStringSet(key, value.value)
    }
}
