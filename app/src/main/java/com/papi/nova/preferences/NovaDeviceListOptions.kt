package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.preference.PreferenceManager
import com.papi.nova.R

/**
 * The entries the legacy settings fragment appends to the resolution and FPS
 * lists at runtime: the device's native modes and the custom values the player
 * typed under Advanced. The modern settings read their lists from the XML
 * arrays alone, so a custom resolution set there was only ever offered in the
 * legacy view (nova#275). The option maths is pure so it is testable without a
 * display; [forDevice] reads the device once and [augment] applies it.
 */
object NovaDeviceListOptions {
    data class Labels(
        val native: String,
        val nativeFullscreen: String,
        val portrait: String,
        val landscape: String,
        val custom: String,
        val fpsSuffix: String,
    )

    /** A landscape-normalized native size; [insetsRemoved] marks a mode reported beside a notch-adjusted one. */
    data class NativeSize(val width: Int, val height: Int, val insetsRemoved: Boolean = false)

    class DeviceLists internal constructor(
        private val nativeSizes: List<NativeSize>,
        private val nativeMaxFps: Float?,
        private val customResolution: String?,
        private val customRefreshRate: String?,
        private val labels: Labels,
    ) {
        fun augment(definition: NovaSettingDefinition): NovaSettingDefinition = when (definition.key) {
            PreferenceConfiguration.RESOLUTION_PREF_STRING ->
                definition.copy(options = resolutionOptions(definition.options, nativeSizes, customResolution, labels))
            PreferenceConfiguration.FPS_PREF_STRING ->
                definition.copy(options = fpsOptions(definition.options, nativeMaxFps, customRefreshRate, labels))
            else -> definition
        }
    }

    /** "1920x1080" to (1920, 1080); null for anything else. */
    fun parseResolution(value: String?): Pair<Int, Int>? {
        val segments = value?.trim()?.split("x") ?: return null
        if (segments.size != 2) return null
        val width = segments[0].trim().toIntOrNull() ?: return null
        val height = segments[1].trim().toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    fun resolutionOptions(
        base: List<NovaSettingOption>,
        nativeSizes: List<NativeSize>,
        customResolution: String?,
        labels: Labels,
    ): List<NovaSettingOption> {
        val options = base.toMutableList()
        val known = base.mapTo(mutableSetOf()) { it.value }
        fun add(width: Int, height: Int, prefix: String, orientation: String?) {
            val value = "${width}x$height"
            if (!known.add(value)) return
            val label = buildString {
                append(prefix)
                if (orientation != null) {
                    append(' ')
                    append(orientation)
                }
                append(" ($value)")
            }
            options += NovaSettingOption(label = label, value = value)
        }
        // The legacy fragment lists a squarish screen both ways, portrait first.
        fun addPair(width: Int, height: Int, prefix: String) {
            if (PreferenceConfiguration.isSquarishScreen(width, height)) {
                add(height, width, prefix, labels.portrait)
                add(width, height, prefix, labels.landscape)
            } else {
                add(width, height, prefix, null)
            }
        }
        parseResolution(customResolution)?.let { (width, height) -> addPair(width, height, labels.custom) }
        for (size in nativeSizes) {
            addPair(size.width, size.height, if (size.insetsRemoved) labels.nativeFullscreen else labels.native)
        }
        return options
    }

    fun fpsOptions(
        base: List<NovaSettingOption>,
        nativeMaxFps: Float?,
        customRefreshRate: String?,
        labels: Labels,
    ): List<NovaSettingOption> {
        val options = base.toMutableList()
        val known = base.mapTo(mutableSetOf()) { it.value }
        fun add(value: String, prefix: String) {
            if (!known.add(value)) return
            options += NovaSettingOption(label = "$prefix ($value ${labels.fpsSuffix})", value = value)
        }
        // Same string forms the legacy fragment stores: a custom rate keeps its
        // decimal ("90.0"), the native rate is rounded ("120").
        customRefreshRate?.trim()?.toFloatOrNull()?.takeIf { it > 0f }?.let { add(it.toString(), labels.custom) }
        nativeMaxFps?.let { fps ->
            val rounded = Math.round(fps)
            if (rounded > 0) add(rounded.toString(), labels.native)
        }
        return options
    }

    fun forDevice(context: Context): DeviceLists {
        val labels = Labels(
            native = context.getString(R.string.resolution_prefix_native),
            nativeFullscreen = context.getString(R.string.resolution_prefix_native_fullscreen),
            portrait = context.getString(R.string.resolution_prefix_native_portrait),
            landscape = context.getString(R.string.resolution_prefix_native_landscape),
            custom = context.getString(R.string.resolution_prefix_custom),
            fpsSuffix = context.getString(R.string.fps_suffix_fps),
        )
        val prefs = runCatching { PreferenceManager.getDefaultSharedPreferences(context) }.getOrNull()
        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        return DeviceLists(
            nativeSizes = display?.let { runCatching { nativeSizes(it, context.packageManager) }.getOrNull() }.orEmpty(),
            nativeMaxFps = display?.let { runCatching { NovaDisplayFpsCapability.maxSupportedFps(it) }.getOrNull() },
            customResolution = prefs?.getString(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, null),
            customRefreshRate = prefs?.getString(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING, null),
            labels = labels,
        )
    }

    /** Mirrors the legacy fragment: notch-adjusted real size first, then every supported mode. */
    @Suppress("DEPRECATION")
    internal fun nativeSizes(display: Display, packageManager: PackageManager): List<NativeSize> {
        val sizes = mutableListOf<NativeSize>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            sizes += NativeSize(maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels))
            return sizes
        }
        var hasInsets = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cutout = display.cutout
            if (cutout != null) {
                val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop
                if (widthInsets != 0 || heightInsets != 0) {
                    val metrics = DisplayMetrics()
                    display.getRealMetrics(metrics)
                    sizes += NativeSize(
                        maxOf(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets),
                        minOf(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets),
                    )
                    hasInsets = true
                }
            }
        }
        val television = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        for (mode in display.supportedModes) {
            val width = maxOf(mode.physicalWidth, mode.physicalHeight)
            val height = minOf(mode.physicalWidth, mode.physicalHeight)
            if (!television || width > 3840 || height > 2160) {
                sizes += NativeSize(width, height, insetsRemoved = hasInsets)
            }
        }
        return sizes
    }
}
