package com.papi.nova.binding.video

import android.app.Activity
import android.graphics.Point
import android.os.Build
import com.papi.nova.BuildConfig
import com.papi.nova.preferences.PreferenceConfiguration
import kotlin.math.abs

/**
 * Whether a PyroWave stream is upscaled by SGSR 1, and the size of the buffer it is drawn into (#304).
 *
 * SGSR 1 (Snapdragon Game Super Resolution 1) is a spatial, single pass filter. It can only add
 * detail in a buffer bigger than the frame, and Nova normally pins the buffer to the stream's own size
 * and lets the compositor scale it. So an eligible session asks for a buffer the size of the output
 * instead, and the renderer checks the same conditions again against the swapchain it actually gets.
 *
 * Off by default, and ignored outside builds with experimental codecs. Everything else about the
 * stream, its resolution, bitrate, formats and the quality model, stays as it was.
 */
object PyroWaveScalerPolicy {
    // Kept in step with pyrowave_renderer_c.h.
    const val REQUEST_NONE = 0
    const val REQUEST_FIT = 1
    const val REQUEST_STRETCH = 2

    const val STATE_DEFAULT = 0
    const val STATE_SGSR1_ACTIVE = 1
    const val STATE_OUTPUT_BUFFER_BILINEAR = 2

    const val NATIVE_REASON_NONE = 0
    const val NATIVE_REASON_NOT_REQUESTED = 1
    const val NATIVE_REASON_HDR_OR_10BIT = 2
    const val NATIVE_REASON_NOT_UPSCALING = 3
    const val NATIVE_REASON_ASPECT_MISMATCH = 4
    const val NATIVE_REASON_PIPELINE_FAILED = 5

    /** Frame and output aspects within this fraction of each other count as one aspect. */
    const val ASPECT_TOLERANCE = 0.005

    /** Why a session does or does not ask for SGSR 1. */
    enum class Reason {
        ELIGIBLE,

        /** Game never decided for this session, or could not: nothing was asked and nothing resized. */
        NOT_EVALUATED,
        DISABLED,
        NOT_EXPERIMENTAL_BUILD,
        NOT_PYROWAVE,
        HDR_OR_10BIT,
        FILL_MODE,
        NOT_UPSCALING,
        ASPECT_MISMATCH,
    }

    /**
     * What a session asks for, decided once before its surface is sized.
     *
     * @property request one of [REQUEST_NONE], [REQUEST_FIT] or [REQUEST_STRETCH]
     * @property bufferWidth the buffer an eligible session asks for; the frame size otherwise
     */
    data class Decision(
        val request: Int,
        val reason: Reason,
        val frameWidth: Int,
        val frameHeight: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
    ) {
        val requested: Boolean
            get() = request != REQUEST_NONE

        companion object {
            /** A session that never asked, such as one whose surface was not sized by Game. */
            fun notRequested(reason: Reason = Reason.NOT_EVALUATED) = Decision(REQUEST_NONE, reason, 0, 0, 0, 0)
        }
    }

    /** What the renderer did with the buffer it got, as its getter reports it. */
    data class Applied(
        val state: Int,
        val nativeReason: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
    ) {
        val stateName: String
            get() = when (state) {
                STATE_SGSR1_ACTIVE -> "SGSR1_ACTIVE"
                STATE_OUTPUT_BUFFER_BILINEAR -> "OUTPUT_BUFFER_BILINEAR"
                STATE_DEFAULT -> "DEFAULT"
                else -> "UNKNOWN($state)"
            }

        val reasonName: String
            get() = when (nativeReason) {
                NATIVE_REASON_NONE -> "NONE"
                NATIVE_REASON_NOT_REQUESTED -> "NOT_REQUESTED"
                NATIVE_REASON_HDR_OR_10BIT -> "HDR_OR_10BIT"
                NATIVE_REASON_NOT_UPSCALING -> "NOT_UPSCALING"
                NATIVE_REASON_ASPECT_MISMATCH -> "ASPECT_MISMATCH"
                NATIVE_REASON_PIPELINE_FAILED -> "PIPELINE_FAILED"
                else -> "UNKNOWN($nativeReason)"
            }
    }

    /**
     * The decision itself, with nothing read from the device, so every case can be tested.
     *
     * @param enabled the player's setting
     * @param experimentalBuild whether this build offers experimental codecs at all
     * @param pyrowave whether this session asks for PyroWave
     * @param hdrRequested whether this session offers the ten bit formats, which are HDR on this codec
     * @param frameWidth the stream's width, the size of each decoded frame
     * @param windowWidth the window's width in physical pixels
     */
    fun decide(
        enabled: Boolean,
        experimentalBuild: Boolean,
        pyrowave: Boolean,
        hdrRequested: Boolean,
        scaleMode: PreferenceConfiguration.ScaleMode?,
        frameWidth: Int,
        frameHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): Decision {
        val refused = { reason: Reason -> Decision(REQUEST_NONE, reason, frameWidth, frameHeight, frameWidth, frameHeight) }
        if (!experimentalBuild) return refused(Reason.NOT_EXPERIMENTAL_BUILD)
        if (!enabled) return refused(Reason.DISABLED)
        if (!pyrowave) return refused(Reason.NOT_PYROWAVE)
        if (hdrRequested) return refused(Reason.HDR_OR_10BIT)
        val mode = scaleMode ?: PreferenceConfiguration.ScaleMode.FIT
        if (mode == PreferenceConfiguration.ScaleMode.FILL) return refused(Reason.FILL_MODE)
        if (frameWidth <= 0 || frameHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) {
            return refused(Reason.NOT_UPSCALING)
        }

        val (bufferWidth, bufferHeight) = outputSize(mode, frameWidth, frameHeight, windowWidth, windowHeight)
        if (bufferWidth <= frameWidth || bufferHeight <= frameHeight) return refused(Reason.NOT_UPSCALING)
        val request = if (mode == PreferenceConfiguration.ScaleMode.STRETCH) REQUEST_STRETCH else REQUEST_FIT
        if (request == REQUEST_FIT && !aspectMatches(frameWidth, frameHeight, bufferWidth, bufferHeight)) {
            return refused(Reason.ASPECT_MISMATCH)
        }
        return Decision(request, Reason.ELIGIBLE, frameWidth, frameHeight, bufferWidth, bufferHeight)
    }

    /**
     * The buffer an eligible session draws into.
     *
     * Fit is the frame's aspect fitted into the window, with the arithmetic and the truncation of
     * StreamContainer.onMeasure, so the buffer is the size of the view the compositor shows it in.
     * Stretch is the window. Fill never gets here: its view overflows the screen.
     */
    fun outputSize(
        mode: PreferenceConfiguration.ScaleMode,
        frameWidth: Int,
        frameHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): Pair<Int, Int> {
        if (mode == PreferenceConfiguration.ScaleMode.STRETCH) return windowWidth to windowHeight
        val desiredAspectRatio = frameWidth.toDouble() / frameHeight.toDouble()
        return if (windowWidth > windowHeight * desiredAspectRatio) {
            (windowHeight * desiredAspectRatio).toInt() to windowHeight
        } else {
            windowWidth to (windowWidth / desiredAspectRatio).toInt()
        }
    }

    /** The renderer's aspect guard, the same arithmetic as choose_scaler in pyrowave_renderer.cpp. */
    fun aspectMatches(frameWidth: Int, frameHeight: Int, bufferWidth: Int, bufferHeight: Int): Boolean {
        val frameAspect = frameWidth.toDouble() / frameHeight
        val bufferAspect = bufferWidth.toDouble() / bufferHeight
        return abs(frameAspect / bufferAspect - 1.0) <= ASPECT_TOLERANCE
    }

    /** The getter's six integers, or null when there was no renderer to ask. */
    fun applied(values: IntArray?): Applied? {
        if (values == null || values.size != 6) return null
        return Applied(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    /** One log line for the session: what was asked, why, and what the renderer did. */
    fun describe(decision: Decision, applied: Applied?): String {
        val asked = if (decision.requested) {
            "asked for SGSR 1 (${if (decision.request == REQUEST_STRETCH) "stretch" else "fit"}) " +
                "into ${decision.bufferWidth}x${decision.bufferHeight}"
        } else {
            "kept the stream-size buffer (${decision.reason})"
        }
        val did = applied?.let {
            "${it.stateName} (${it.reasonName}), frame ${it.frameWidth}x${it.frameHeight}, " +
                "buffer ${it.bufferWidth}x${it.bufferHeight}"
        } ?: "no renderer state"
        return "PyroWave scaler: $asked; renderer $did"
    }

    /**
     * The decision for the session Game is starting, read from its preferences and window.
     *
     * The window is read in physical pixels when the surface is sized, which is before the view is
     * measured; the renderer's own check against the swapchain it gets is what decides in the end.
     */
    fun forSession(
        activity: Activity,
        prefConfig: PreferenceConfiguration,
        hdrRequested: Boolean,
        frameWidth: Int,
        frameHeight: Int,
    ): Decision {
        val (windowWidth, windowHeight) = windowBounds(activity)
        return decide(
            enabled = prefConfig.pyrowaveSgsr1,
            experimentalBuild = BuildConfig.EXPERIMENTAL_CODECS,
            pyrowave = prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_PYROWAVE,
            hdrRequested = hdrRequested,
            scaleMode = prefConfig.videoScaleMode,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
        )
    }

    private fun windowBounds(activity: Activity): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
}
