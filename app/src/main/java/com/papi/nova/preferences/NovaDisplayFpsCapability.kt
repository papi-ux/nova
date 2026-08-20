package com.papi.nova.preferences

import android.os.Build
import android.view.Display

/**
 * Panel-capability rules for the standard stream FPS options.
 *
 * Extracted from the legacy settings fragment so every surface that offers an FPS
 * choice (legacy PreferenceFragment, Compose settings, Play Setup) culls with the
 * same thresholds instead of each growing its own.
 */
object NovaDisplayFpsCapability {
    /** Minimum panel refresh rate required to offer the 120 FPS option. */
    const val FPS_120_MIN_PANEL_HZ = 118f

    /** Minimum panel refresh rate required to offer the 90 FPS option. */
    const val FPS_90_MIN_PANEL_HZ = 88f

    private val STANDARD_FPS_VALUES = listOf(30, 60, 90, 120)

    /** The panel's fastest refresh rate across all supported display modes. */
    @JvmStatic
    fun maxSupportedFps(display: Display): Float {
        var maxSupportedFps = display.refreshRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (candidate in display.supportedModes) {
                if (candidate.refreshRate > maxSupportedFps) {
                    maxSupportedFps = candidate.refreshRate
                }
            }
        }
        return maxSupportedFps
    }

    /** The standard FPS options this panel can actually present, ascending. */
    @JvmStatic
    fun allowedFpsValues(maxSupportedFps: Float): List<Int> {
        val values = mutableListOf(30, 60)
        if (maxSupportedFps >= FPS_90_MIN_PANEL_HZ) {
            values += 90
        }
        if (maxSupportedFps >= FPS_120_MIN_PANEL_HZ) {
            values += 120
        }
        return values
    }

    /**
     * Coerces a stored standard FPS value down to the fastest option the panel allows.
     * Non-standard values (native/custom rates) pass through untouched, matching the
     * legacy culling which only ever rewrote the standard entries it removed.
     */
    @JvmStatic
    fun coerce(requestedFps: Int, maxSupportedFps: Float): Int {
        val allowed = allowedFpsValues(maxSupportedFps)
        if (requestedFps in allowed || requestedFps !in STANDARD_FPS_VALUES) {
            return requestedFps
        }
        return allowed.last { it <= requestedFps }
    }
}
