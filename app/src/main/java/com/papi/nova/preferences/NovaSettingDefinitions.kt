package com.papi.nova.preferences

import android.content.Context
import android.util.AttributeSet
import android.util.Xml
import com.papi.nova.R
import org.xmlpull.v1.XmlPullParser

enum class NovaSettingType {
    Toggle,
    Select,
    Slider,
    Text,
    Action
}

enum class NovaSettingRisk {
    Normal,
    Confirm,
    Dangerous
}

enum class NovaSettingApplyTiming(val label: String) {
    Instant("Instant"),
    NextStream("Next stream"),
    RestartApp("Restart app")
}

data class NovaSettingOption(
    val label: String,
    val value: String
)

data class NovaSettingCategory(
    val key: String,
    val title: String,
    val summary: String
)

data class NovaSettingDefinition(
    val key: String,
    val title: String,
    val summary: String,
    val categoryKey: String,
    val type: NovaSettingType,
    val defaultValue: NovaSettingValue? = null,
    val options: List<NovaSettingOption> = emptyList(),
    val dependencyKey: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val suffix: String? = null,
    val risk: NovaSettingRisk = NovaSettingRisk.Normal,
    val applyTiming: NovaSettingApplyTiming = NovaSettingApplyTiming.Instant
)

data class NovaSettingsDefinitionSet(
    val categories: List<NovaSettingCategory>,
    val settings: List<NovaSettingDefinition>
) {
    private val byKey: Map<String, NovaSettingDefinition> = settings.associateBy { it.key }

    fun find(key: String): NovaSettingDefinition? = byKey[key]

    fun require(key: String): NovaSettingDefinition {
        return requireNotNull(find(key)) { "Missing Nova setting definition for $key" }
    }

    fun settingsForCategory(categoryKey: String): List<NovaSettingDefinition> {
        return settings.filter { it.categoryKey == categoryKey }
    }
}

sealed interface NovaSettingValue {
    data class BooleanValue(val value: Boolean) : NovaSettingValue
    data class IntValue(val value: Int) : NovaSettingValue
    data class StringValue(val value: String) : NovaSettingValue
    data class StringSetValue(val value: Set<String>) : NovaSettingValue
}

object NovaSettingDefinitions {
    fun load(context: Context): NovaSettingsDefinitionSet {
        val categories = mutableListOf<NovaSettingCategory>()
        val settings = mutableListOf<NovaSettingDefinition>()
        var activeCategoryKey = ""

        val parser = context.resources.getXml(R.xml.preferences)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            val attrs = Xml.asAttributeSet(parser)
            val tag = parser.name.substringAfterLast('.')
            val key = attrs.getAttributeValue(ANDROID_NS, "key") ?: continue
            if (tag.endsWith("PreferenceCategory")) {
                activeCategoryKey = key
                categories += NovaSettingCategory(
                    key = key,
                    title = attrs.resolveText(context, "title").ifBlank { key },
                    summary = attrs.resolveText(context, "summary")
                )
            } else {
                settings += attrs.toDefinition(context, tag, activeCategoryKey)
            }
        }

        // The resolution and FPS lists also carry what the legacy fragment appends at
        // runtime: the device's native modes and the custom values typed under Advanced.
        val deviceLists = NovaDeviceListOptions.forDevice(context)
        return NovaSettingsDefinitionSet(categories = categories, settings = settings.map(deviceLists::augment))
    }

    private fun AttributeSet.toDefinition(
        context: Context,
        tag: String,
        categoryKey: String
    ): NovaSettingDefinition {
        val type = tag.toSettingType()
        val key = getAttributeValue(ANDROID_NS, "key").orEmpty()
        val options = if (type == NovaSettingType.Select) resolveOptions(context) else emptyList()
        return NovaSettingDefinition(
            key = key,
            title = resolveText(context, "title").ifBlank { key },
            summary = resolveText(context, "summary"),
            categoryKey = categoryKey,
            type = type,
            defaultValue = key.resolveSyntheticDefaultValue() ?: resolveDefaultValue(type),
            options = options,
            dependencyKey = getAttributeValue(ANDROID_NS, "dependency"),
            min = if (type == NovaSettingType.Slider) getAttributeIntValue(SEEK_NS, "min", 0) else null,
            max = if (type == NovaSettingType.Slider) getAttributeIntValue(ANDROID_NS, "max", 100) else null,
            step = if (type == NovaSettingType.Slider) {
                getAttributeIntValue(SEEK_NS, "step", 1).takeIf { it > 0 }
            } else {
                null
            },
            suffix = resolveText(context, "text"),
            risk = key.toRisk(),
            applyTiming = key.toApplyTiming(categoryKey)
        )
    }

    private fun String.toSettingType(): NovaSettingType {
        return when {
            contains("CheckBoxPreference", ignoreCase = true) -> NovaSettingType.Toggle
            contains("LanguagePreference", ignoreCase = true) -> NovaSettingType.Select
            contains("ListPreference", ignoreCase = true) -> NovaSettingType.Select
            contains("SeekBarPreference", ignoreCase = true) -> NovaSettingType.Slider
            contains("EditTextPreference", ignoreCase = true) -> NovaSettingType.Text
            else -> NovaSettingType.Action
        }
    }

    private fun AttributeSet.resolveText(context: Context, name: String): String {
        val resId = getAttributeResourceValue(ANDROID_NS, name, 0)
        if (resId != 0) {
            return context.getString(resId)
        }
        return getAttributeValue(ANDROID_NS, name).orEmpty()
    }

    private fun AttributeSet.resolveDefaultValue(type: NovaSettingType): NovaSettingValue? {
        val raw = getAttributeValue(ANDROID_NS, "defaultValue") ?: return null
        return when (type) {
            NovaSettingType.Toggle -> NovaSettingValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false)
            NovaSettingType.Slider -> NovaSettingValue.IntValue(raw.toIntOrNull() ?: 0)
            NovaSettingType.Select,
            NovaSettingType.Text -> NovaSettingValue.StringValue(raw)
            NovaSettingType.Action -> null
        }
    }

    private fun String.resolveSyntheticDefaultValue(): NovaSettingValue? {
        return when (this) {
            "nova_app_version" -> NovaSettingValue.StringValue(NovaAppVersion.current())
            else -> null
        }
    }

    private fun AttributeSet.resolveOptions(context: Context): List<NovaSettingOption> {
        val entriesRes = getAttributeResourceValue(ANDROID_NS, "entries", 0)
        val valuesRes = getAttributeResourceValue(ANDROID_NS, "entryValues", 0)
        if (entriesRes == 0 || valuesRes == 0) return emptyList()

        val entries = context.resources.getTextArray(entriesRes)
        val values = context.resources.getTextArray(valuesRes)
        return entries.zip(values).map { (entry, value) ->
            NovaSettingOption(label = entry.toString(), value = value.toString())
        }
    }

    private fun String.toRisk(): NovaSettingRisk {
        return when (this) {
            PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING,
            PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING,
            PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING,
            "option_reset_osc_preference",
            "import_keyboard_file",
            "export_keyboard_file",
            "import_special_button_file",
            "share_performance_logs" -> NovaSettingRisk.Confirm
            else -> NovaSettingRisk.Normal
        }
    }

    private fun String.toApplyTiming(categoryKey: String): NovaSettingApplyTiming {
        return when {
            this == "list_languages" -> NovaSettingApplyTiming.RestartApp
            this == "nova_theme" -> NovaSettingApplyTiming.Instant
            this == PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING -> NovaSettingApplyTiming.NextStream
            categoryKey == "category_stream_quality" -> NovaSettingApplyTiming.NextStream
            categoryKey == "category_display_audio" -> NovaSettingApplyTiming.NextStream
            categoryKey == "category_dual_screen" -> NovaSettingApplyTiming.NextStream
            else -> NovaSettingApplyTiming.Instant
        }
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val SEEK_NS = "http://schemas.moonlight-stream.com/apk/res/seekbar"
}
