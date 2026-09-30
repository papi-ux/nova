package com.papi.nova.ui

import com.papi.nova.preferences.*

internal val novaDevicePlaySetupKeys = linkedMapOf(
    NovaPlaySetupRow.DEVICE_QUALITY to NovaTierControls.QUALITY_KEY,
    NovaPlaySetupRow.DEVICE_RESOLUTION to PreferenceConfiguration.RESOLUTION_PREF_STRING,
    NovaPlaySetupRow.DEVICE_FRAME_RATE to PreferenceConfiguration.FPS_PREF_STRING,
    NovaPlaySetupRow.DEVICE_VIDEO_CODEC to "video_format",
    NovaPlaySetupRow.DEVICE_BITRATE to PreferenceConfiguration.BITRATE_PREF_STRING,
    NovaPlaySetupRow.DEVICE_AUTO to NovaSettingsMigration.AUTO,
)

/** Shared device projection; the same ViewModel owns Custom, Auto and saved-setup receipts. */
internal fun buildNovaDevicePlaySetupRows(
    state: NovaSettingsUiState,
    onValue: (NovaSettingDefinition, NovaSettingValue) -> Unit,
) = buildNovaDevicePlaySetupRows(state, onValue,
    com.papi.nova.binding.video.PyroWaveAvailability.Status.CHECKING, "Checking PyroWave compatibility")

internal fun buildNovaDevicePlaySetupRows(
    state: NovaSettingsUiState,
    onValue: (NovaSettingDefinition, NovaSettingValue) -> Unit,
    pyroWave: com.papi.nova.binding.video.PyroWaveAvailability.Status,
    pyroWaveReason: String,
): List<NovaPlaySetupRowState> = novaDevicePlaySetupKeys.mapNotNull { (row,key) ->
    val definition = state.deviceStreamSettings.firstOrNull { it.key == key } ?: return@mapNotNull null
    val current = state.values[key] ?: definition.defaultValue
    val options = when (definition.type) {
        NovaSettingType.Toggle -> listOf(false,true).map { value ->
            NovaPlaySetupOption(if(value) "Auto" else "Manual", "Your device setting",
                current=state.bitrateAuto==value, onSelect={ onValue(definition,NovaSettingValue.BooleanValue(value)) })
        }
        NovaSettingType.Slider -> {
            val value=(current as? NovaSettingValue.IntValue)?.value ?: definition.min ?: 1000
            val range=(definition.min ?: 1000)..(definition.max ?: 300000)
            listOf((value-5000).coerceIn(range),value,(value+5000).coerceIn(range)).distinct().sorted().map { target ->
                NovaPlaySetupOption(NovaBitrateAdvice.text(target,false), "Your device setting",
                    current=target==value, enabled=target in range,
                    onSelect={ onValue(definition,NovaSettingValue.IntValue(target)) })
            }
        }
        else -> definition.options.map { option ->
            val reason = option.disabledReason ?: if (key == "video_format" &&
                !com.papi.nova.binding.video.PyroWaveAvailability.canSelect(option.value, pyroWave)) pyroWaveReason else null
            NovaPlaySetupOption(option.label, reason ?: option.caption ?: definition.summary,
                current=(current as? NovaSettingValue.StringValue)?.value==option.value,
                enabled=reason==null,
                onSelect=if (reason == null) ({ onValue(definition,NovaSettingValue.StringValue(option.value)) }) else null,
                value=option.caption.orEmpty(), focusableWhenDisabled=row in setOf(
                    NovaPlaySetupRow.DEVICE_QUALITY,NovaPlaySetupRow.DEVICE_VIDEO_CODEC))
        }
    }
    val shown = when (row) {
        NovaPlaySetupRow.DEVICE_BITRATE -> (current as? NovaSettingValue.IntValue)?.value?.let {
            NovaBitrateAdvice.text(it,state.bitrateAuto==true) }.orEmpty()
        else -> options.firstOrNull { it.current }?.label.orEmpty()
    }
    NovaPlaySetupRowState(row, definition.title, definition.summary, shown, options,
        enabled=!state.tierSavePending && state.isEnabled(definition),
        ordered=row==NovaPlaySetupRow.DEVICE_BITRATE || row==NovaPlaySetupRow.DEVICE_FRAME_RATE,
        opensPage=row!=NovaPlaySetupRow.DEVICE_AUTO,
        stepWhileOpensPage=true)
}
