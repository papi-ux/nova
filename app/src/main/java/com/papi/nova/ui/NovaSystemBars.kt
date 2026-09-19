package com.papi.nova.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.papi.nova.R

/**
 * Hide System Bars: Nova's own screens without the status and navigation bars, so
 * the artwork runs to every edge the way a console launcher does. A swipe from an
 * edge shows the bars for a moment, as in a stream.
 *
 * It starts on for a gaming handheld, where a clock and a battery icon over the
 * art are the last thing that does not look like a console, and off everywhere
 * else: a device whose controls report as built in, or one from a handheld maker
 * whose firmware reports them as a paired controller. The default is written once, the first time Nova runs
 * with this setting; after that the device keeps whatever its owner chose.
 */
object NovaSystemBars {
    const val KEY_HIDE_SYSTEM_BARS = "nova_hide_system_bars"

    /** The status and navigation bars; a window's caption bar in desktop windowing stays. */
    private val BARS = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()

    /** What Android says about one input device, reduced to what the default needs. */
    data class InputProbe(
        val sources: Int,
        val external: Boolean,
        val virtual: Boolean,
    )

    /** A gamepad that is part of the device itself, not one paired or plugged in. */
    fun hasBuiltInGamepad(devices: List<InputProbe>): Boolean =
        devices.any { device ->
            !device.virtual &&
                !device.external &&
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        }

    /**
     * Makers of Android handhelds whose controls are part of the device. Retroid's
     * firmware presents its controls as an external "Xbox Wireless Controller", so the
     * built-in gamepad check alone misses the very handhelds this setting is for.
     */
    private val handheldMakers = setOf("moorechip", "retroid", "ayn", "ayaneo", "anbernic")

    /** A device its maker ships as a gaming handheld, from what Android reports about it. */
    fun isKnownHandheld(manufacturer: String, model: String): Boolean =
        manufacturer.trim().lowercase() in handheldMakers ||
            model.trim().lowercase().startsWith("retroid")

    /** The first-run default: on for a gaming handheld, never on a TV. */
    fun defaultHidden(television: Boolean, builtInGamepad: Boolean, knownHandheld: Boolean = false): Boolean =
        !television && (builtInGamepad || knownHandheld)

    /** Writes the device's default once; a stored choice is never replaced. */
    fun seedDefault(context: Context) {
        // The probe asks Android about every input device, so it runs only when there is
        // no default yet.
        if (PreferenceManager.getDefaultSharedPreferences(context).contains(KEY_HIDE_SYSTEM_BARS)) return
        seedDefault(context, defaultHidden(context))
    }

    fun seedDefault(context: Context, hiddenByDefault: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(KEY_HIDE_SYSTEM_BARS)) {
            prefs.edit().putBoolean(KEY_HIDE_SYSTEM_BARS, hiddenByDefault).apply()
        }
    }

    fun isHidden(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_HIDE_SYSTEM_BARS, false)

    /** Marks a screen that took Nova's theme, so the stream is never touched. */
    fun markManaged(activity: Activity) {
        activity.window.decorView.setTag(R.id.nova_system_bars_managed, true)
    }

    fun isManaged(activity: Activity): Boolean =
        activity.window?.decorView?.getTag(R.id.nova_system_bars_managed) == true

    /** Marks the stream, which runs full screen whatever the setting says. */
    fun markStream(activity: Activity) {
        activity.window.decorView.setTag(R.id.nova_system_bars_stream, true)
    }

    fun isStream(activity: Activity): Boolean =
        activity.window?.decorView?.getTag(R.id.nova_system_bars_stream) == true

    /**
     * Hides the bars for a Nova screen when the setting is on. Turned off, it shows
     * again only the bars this screen hid, so with the setting off a screen is never
     * touched.
     */
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val decorView = window.decorView
        val controller = WindowCompat.getInsetsController(window, decorView)
        if (isHidden(activity)) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(BARS)
            decorView.setTag(R.id.nova_system_bars_hidden, true)
        } else if (decorView.getTag(R.id.nova_system_bars_hidden) == true) {
            controller.show(BARS)
            decorView.setTag(R.id.nova_system_bars_hidden, null)
        }
    }

    /**
     * A sheet or dialog is a window of its own, and a window that says nothing about the
     * bars brings them back over the screen under it. With the setting on it hides them
     * as well; with it off the window is left as it was.
     */
    fun applyToDialog(context: Context, window: Window) {
        if (!isHidden(context)) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(BARS)
    }

    /**
     * A sheet or dialog over the stream. The stream is full screen whatever the setting
     * says, so a dialog that said nothing about the bars brought them back over the game:
     * End Session showed the clock, the battery and the navigation bar above Control.
     */
    fun applyToStreamDialog(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(BARS)
    }

    private fun defaultHidden(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val television = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        return defaultHidden(
            television,
            hasBuiltInGamepad(probeInputDevices()),
            isKnownHandheld(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
        )
    }

    private fun probeInputDevices(): List<InputProbe> {
        // Android says whether a device is external from Android 10 on. Earlier than
        // that there is no reliable answer, so nothing counts as built in.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.let { device ->
                InputProbe(sources = device.sources, external = device.isExternal, virtual = device.isVirtual)
            }
        }
    }
}
