package com.papi.nova.binding.video

import android.os.Build
import android.view.Display
import com.papi.nova.binding.video.sourcetime.DisplayFacts
import com.papi.nova.binding.video.sourcetime.DisplayPresentationCapability

/**
 * Reads what a display says about its presentation timing, each call behind the API level that
 * has it, and classifies it (DisplayPresentationCapability). Read-only: it never sets a mode,
 * a frame rate or a category. A display that is gone, or a call that fails, gives no answer
 * rather than a guess.
 */
object DisplayPresentationProbe {
    @JvmStatic
    fun read(display: Display?): DisplayPresentationCapability = DisplayPresentationCapability.from(facts(display))

    @JvmStatic
    fun facts(display: Display?): DisplayFacts {
        val sdkInt = Build.VERSION.SDK_INT
        if (display == null) {
            return DisplayFacts(sdkInt = sdkInt, hasArrSupport = null, modeRefreshHz = 0f)
        }
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) runCatching { display.mode }.getOrNull() else null
        val modeHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mode != null) mode.refreshRate else runCatching { display.refreshRate }.getOrDefault(0f)
        val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mode != null) {
            runCatching {
                display.supportedModes
                    .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                    .map { it.refreshRate }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val alternatives = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mode != null) {
            runCatching { mode.alternativeRefreshRates.toList().sorted() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        var hasArr: Boolean? = null
        var normal: Float? = null
        var high: Float? = null
        // API 36 (Android 16) is the first that can ask; a literal so lint can see the check.
        if (Build.VERSION.SDK_INT >= 36) {
            hasArr = runCatching { display.hasArrSupport() }.getOrNull()
            normal = runCatching { display.getSuggestedFrameRate(Display.FRAME_RATE_CATEGORY_NORMAL) }.getOrNull()
            high = runCatching { display.getSuggestedFrameRate(Display.FRAME_RATE_CATEGORY_HIGH) }.getOrNull()
        }
        return DisplayFacts(
            sdkInt = sdkInt,
            hasArrSupport = hasArr,
            modeRefreshHz = modeHz,
            supportedRefreshRatesHz = supported,
            alternativeRefreshRatesHz = alternatives,
            suggestedFrameRateNormalHz = normal?.takeIf { it.isFinite() && it > 0f },
            suggestedFrameRateHighHz = high?.takeIf { it.isFinite() && it > 0f },
            appVsyncOffsetNs = runCatching { display.appVsyncOffsetNanos }.getOrDefault(0L),
            presentationDeadlineNs = runCatching { display.presentationDeadlineNanos }.getOrDefault(0L),
        )
    }
}
