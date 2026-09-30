package com.papi.nova.preferences

import android.content.Context
import androidx.compose.runtime.Composable
import com.papi.nova.R
import com.papi.nova.binding.video.PyroWaveAvailability
import com.papi.nova.ui.compose.NovaThemeSwatch
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaField
import com.papi.nova.ui.panel.NovaFieldKind
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaUseDefault

/**
 * What the Settings pane shows: the rows of one category, or a page pushed over them. Choice,
 * Slider and Form pages are the host's own [NovaCommonPage]s; these are the ones Settings draws.
 */
sealed interface SettingsPage : NovaPage {
    /** The pane's root: the rows of the category (or the search results) named by [paneKey]. */
    data class Rows(val paneKey: String, override val title: String) : SettingsPage {
        override val key: String get() = "rows:$paneKey"
    }

    /**
     * The display role composer. It opens on [currentTarget] and hands the chosen target to
     * [onApply]; Compose Settings pushes it in the pane, the legacy screen opens it as a
     * right-edge page. [useDefault], while a preset overrides the target, is its last row (C02).
     */
    class DisplayRole(
        override val title: String,
        val currentTarget: String,
        val onApply: (String) -> Unit,
        val useDefault: NovaUseDefault? = null,
    ) : SettingsPage {
        override val key: String get() = "display-role"
    }
}

/** Whether a Select changes in its own row or opens its list as a page (spec section 7, group 4). */
enum class NovaSelectPresentation { InPlace, Page }

/**
 * The Selects that open a page whatever their size: the preset rewrites other settings, the
 * display target is the role composer, the theme needs its preview and the codec explains why an
 * option is unavailable.
 */
internal val NovaPageSelectKeys: Set<String> = setOf(
    "nova_stream_preset",
    PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING,
    "nova_theme",
    "video_format",
)

/** The Selects whose options are an ordered scale: they stop at their ends instead of wrapping. */
internal val NovaOrderedSelectKeys: Set<String> = setOf(
    PreferenceConfiguration.RESOLUTION_PREF_STRING,
    PreferenceConfiguration.FPS_PREF_STRING,
    "dual_screen_companion_dim_timeout_seconds",
    "nova_disconnect_resume_timeout_seconds",
    "nova_polaris_max_retries",
)

/** An ordered scale longer than this opens a page. */
internal const val NovaInPlaceMaxOrderedOptions = 12

/** An unordered set longer than this opens a page. */
internal const val NovaInPlaceMaxUnorderedOptions = 4

val NovaSettingDefinition.isOrderedScale: Boolean
    get() = key in NovaOrderedSelectKeys

/**
 * How this Select is presented. It opens a page when it is one of [NovaPageSelectKeys], when its
 * risk is not Normal, when it has more than twelve options, or when it has more than four and is
 * not an ordered scale. Every other Select changes in place.
 */
val NovaSettingDefinition.selectPresentation: NovaSelectPresentation
    get() = when {
        key in NovaPageSelectKeys -> NovaSelectPresentation.Page
        risk != NovaSettingRisk.Normal -> NovaSelectPresentation.Page
        options.size > NovaInPlaceMaxOrderedOptions -> NovaSelectPresentation.Page
        options.size > NovaInPlaceMaxUnorderedOptions && !isOrderedScale -> NovaSelectPresentation.Page
        else -> NovaSelectPresentation.InPlace
    }

/**
 * The options of a Select, with the reason an option cannot be picked. Only the codec list has
 * one: PyroWave needs a decoder and device features this device may not have.
 */
internal fun novaSelectOptions(
    context: Context,
    key: String,
    options: List<NovaSettingOption>,
    pyroWave: PyroWaveAvailability.Status?,
): List<NovaOption<String>> = options.map { option ->
    val blocked = key == VIDEO_FORMAT_KEY && pyroWave != null && !PyroWaveAvailability.canSelect(option.value, pyroWave)
    NovaOption(
        value = option.value,
        label = option.label,
        caption = if (key == VIDEO_FORMAT_KEY) com.papi.nova.ui.novaCodecOptionDetail(context, option.value) else null,
        disabledReason = if (blocked) PyroWaveAvailability.reason(context, pyroWave) else null,
    )
}

/** Whether a Select's list needs the PyroWave check before it can say which options are available. */
internal fun needsPyroWaveCheck(key: String, values: List<String>): Boolean =
    key == VIDEO_FORMAT_KEY && values.any { it == PYROWAVE_VALUE }

/** A Select's list as a Choice page. The theme list draws each theme's swatch beside its name. */
internal fun novaSelectChoicePage(
    key: String,
    title: String,
    options: List<NovaOption<String>>,
    current: String?,
    onChoose: (String) -> Unit,
    useDefault: NovaUseDefault? = null,
): NovaCommonPage.Choice<String> = NovaCommonPage.Choice(
    key = "choice:$key",
    title = title,
    options = options,
    current = current,
    onChoose = onChoose,
    leading = if (key == THEME_KEY) themeSwatch else null,
    useDefault = useDefault,
)

private val themeSwatch: @Composable (NovaOption<String>) -> Unit = { option -> NovaThemeSwatch(option.value) }

/**
 * A text setting as a Form page: the field, Save, and the warning for the settings that can stop
 * a stream from starting. [onSave] gets the trimmed value once it is valid.
 */
internal fun novaTextFormPage(
    context: Context,
    key: String,
    title: String,
    current: String,
    risky: Boolean,
    onSave: (String) -> Unit,
    useDefault: NovaUseDefault? = null,
): NovaCommonPage.Form = NovaCommonPage.Form(
    key = "text:$key",
    title = title,
    fields = listOf(NovaField(key = key, label = title, initial = current, kind = NovaFieldKind.Text, maxLength = textMaxLength(key))),
    submitLabel = context.getString(R.string.nova_panel_save),
    warning = if (risky) novaRiskWarning(context, key) else null,
    onSubmit = { values ->
        val value = values[key].orEmpty().trim()
        if (NovaSettingsValidator.isValidTextValue(key, value)) {
            onSave(value)
            null
        } else {
            novaValidationMessage(context, key)
        }
    },
    useDefault = useDefault,
)

/** What can go wrong with a value typed into one of the risky text settings. */
internal fun novaRiskWarning(context: Context, key: String): String = context.getString(
    when (key) {
        PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING -> R.string.nova_settings_warning_custom_resolution
        PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING -> R.string.nova_settings_warning_custom_refresh_rate
        PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING -> R.string.nova_settings_warning_custom_bitrate
        else -> R.string.nova_settings_warning_generic
    },
)

/** Why a typed value was refused. */
internal fun novaValidationMessage(context: Context, key: String): String = when (key) {
    PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING -> context.getString(R.string.nova_settings_invalid_custom_resolution)
    PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING -> context.getString(R.string.nova_settings_invalid_custom_refresh_rate)
    PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING -> context.getString(
        R.string.nova_settings_invalid_custom_bitrate,
        PreferenceConfiguration.MAX_BITRATE_KBPS / 1000,
    )
    else -> context.getString(R.string.nova_settings_invalid_generic)
}

/** The longest value each text setting takes, as the legacy fields limited it. */
private fun textMaxLength(key: String): Int? = when (key) {
    PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING -> 11
    PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING -> 7
    PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING -> 5
    else -> null
}

internal const val VIDEO_FORMAT_KEY = "video_format"
internal const val THEME_KEY = "nova_theme"
private const val PYROWAVE_VALUE = "forcepyrowave"
