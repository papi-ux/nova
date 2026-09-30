package com.papi.nova.ui

import android.content.Context
import android.content.pm.PackageManager
import com.papi.nova.utils.UiHelper

/**
 * The touch menu button: the round button a player drags anywhere on the stream, which opens the
 * Command Center with a tap. It was called the Floating Button, in an app where nothing floats.
 *
 * It is for touch players only. A phone with no controller may need it as a way in; a device with
 * no touchscreen cannot press it, and a television never needs it, so there the button and its
 * rows in Settings and the Command Center are not offered at all (N27, papi's call b).
 */
object NovaTouchMenuButton {
    /** Its Settings row. */
    const val SETTING_KEY = "checkbox_enable_floating_button"

    fun available(touchscreen: Boolean, television: Boolean): Boolean = touchscreen && !television

    fun available(context: Context): Boolean = available(
        touchscreen = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        television = UiHelper.isTvDevice(context),
    )
}
