package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.papi.nova.BuildConfig

object NovaSettingsAvailability {
    fun filter(
        context: Context,
        definitions: NovaSettingsDefinitionSet
    ): NovaSettingsDefinitionSet {
        val hiddenKeys = hiddenKeys(context)
        return definitions.copy(
            settings = definitions.settings.filterNot { it.key in hiddenKeys }
        )
    }

    fun filterForProfileEditor(definitions: NovaSettingsDefinitionSet): NovaSettingsDefinitionSet {
        val settings = definitions.settings.filter { shouldPersistProfileOverride(it.key) }
        val visibleCategoryKeys = settings.mapTo(linkedSetOf()) { it.categoryKey }
        return definitions.copy(
            categories = definitions.categories.filter { it.key in visibleCategoryKeys },
            settings = settings,
        )
    }

    private fun hiddenKeys(context: Context): Set<String> {
        val pm = context.packageManager
        val keys = linkedSetOf<String>()

        if (BuildConfig.FDROID_BUILD) {
            keys += "option_software_release"
            keys += "option_follow_update"
        }

        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            keys += touchOnlyKeys
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            pm.hasSystemFeature("com.nvidia.feature.shield")
        ) {
            keys += "checkbox_absolute_mouse_mode"
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            keys += "checkbox_gamepad_motion_sensors"
        }

        if (
            !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
            !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
        ) {
            keys += "checkbox_force_device_motion"
            keys += "checkbox_gamepad_motion_fallback"
        }

        if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            keys += "checkbox_usb_bind_all"
            keys += "checkbox_usb_driver"
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !pm.hasSystemFeature("android.software.picture_in_picture") ||
            pm.hasSystemFeature("com.amazon.software.fireos")
        ) {
            keys += "checkbox_enable_pip"
        }

        val vibrator = context.primaryVibrator()
        if (!vibrator.hasVibrator()) {
            keys += "checkbox_vibrate_fallback"
            keys += "seekbar_vibrate_fallback_strength"
            keys += "checkbox_enable_device_rumble"
            keys += "checkbox_vibrate_osc"
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !vibrator.hasAmplitudeControl()) {
            keys += "seekbar_vibrate_fallback_strength"
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            keys += "checkbox_enable_hdr"
        }

        return keys
    }

    private fun Context.primaryVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private val touchOnlyKeys = setOf(
        "list_onscreen_controls_layout_preset",
        "checkbox_hide_osc_when_has_gamepad",
        "checkbox_vibrate_osc",
        "seekbar_osc_opacity",
        "checkbox_only_show_L3R3",
        "checkbox_show_guide_button",
        "seekbar_osc_free_analog_stick_opacity",
        "checkbox_enable_analog_stick_new",
        "option_reset_osc_preference",
        "nova_reset_stream_ui",
        "checkbox_show_onscreen_controls",
        "keyboard_axi_list",
        "import_keyboard_file",
        "export_keyboard_file",
        "checkbox_enable_keyboard"
    )

    fun shouldPersistProfileOverride(key: String): Boolean = key !in profileEditorHiddenKeys

    private val profileEditorHiddenKeys = setOf(
        "nova_ui_font_scale_percent",
        "option_reset_osc_preference",
        "nova_reset_stream_ui",
        "import_keyboard_file",
        "export_keyboard_file",
        "import_special_button_file",
        "option_help_custom_keys",
    )
}
