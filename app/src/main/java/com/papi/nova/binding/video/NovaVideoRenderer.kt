package com.papi.nova.binding.video

import android.view.Surface
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer

/**
 * Everything Nova asks of a video renderer beyond what moonlight-common-c asks.
 *
 * `VideoDecoderRenderer` is the streaming library's side of the contract: setup, start, stop,
 * submit, capabilities, HDR. It is twenty eight lines and says nothing about which codecs a device
 * can decode, where the picture goes, or how the last session performed. Game asks all of that, and
 * asked it of `MediaCodecDecoderRenderer` by name, through twenty five members across forty
 * references, which is fine while there is one renderer and a wall as soon as there are two.
 *
 * So this names the rest of the contract, on top of the library's. A renderer Nova can hold is one
 * type, not two: `conn.start` wants the library's base and Game wants the rest, and a field cannot
 * be an intersection of the two.
 */
abstract class NovaVideoRenderer : VideoDecoderRenderer() {

    // Where the picture goes.

    /** The surface to draw into, or null when it has gone away. */
    abstract fun setRenderTarget(renderTarget: Surface?)

    /** Let go of the decoder before the surface is destroyed, without tearing the session down. */
    abstract fun prepareForStop()

    /** React to a display whose mode or refresh rate changed under a live session. */
    abstract fun refreshDisplayParameters()

    // What this device can decode. Asked before the stream is configured, to build the format mask.

    abstract val isAvcSupported: Boolean
    abstract val isHevcSupported: Boolean
    abstract val isHevcMain10Hdr10Supported: Boolean
    abstract val isAv1Supported: Boolean
    abstract val isAv1Main10Supported: Boolean

    /**
     * Whether this renderer can take an HDR10 stream at all, whatever it decodes it with.
     *
     * For a MediaCodec renderer that is exactly the two profiles above, which is why they are what
     * this is made of by default. A renderer whose codec carries HDR itself has neither of them and
     * can still present PQ BT.2020, and asking about the profiles instead of asking this is how the
     * one that can spent a while refusing to.
     */
    open val isHdr10Supported: Boolean
        get() = isHevcMain10Hdr10Supported || isAv1Main10Supported

    /** The colour space this renderer would rather be given, as a moonlight constant. */
    abstract fun getPreferredColorSpace(): Int

    /** The colour range this renderer would rather be given, as a moonlight constant. */
    abstract fun getPreferredColorRange(): Int

    // What it is doing now.

    /** The format this session negotiated, as a moonlight VIDEO_FORMAT constant. */
    abstract val activeVideoFormat: Int

    /** The decoder actually in use, for the overlay and the host's report. */
    abstract val activeDecoderName: String

    // How it went. All of these may be unavailable, and say so rather than guessing.

    abstract fun getAverageEndToEndLatency(): Int

    abstract fun getAverageDecoderLatency(): Int

    /** Null when nothing was measured, which is not the same as measuring zero. */
    abstract fun performanceWasTracked(): Boolean?

    abstract fun getMinDecoderLatency(): String

    abstract fun getMinDecoderLatencyFullLog(): String

    // Knobs Game turns from preferences and lifecycle.

    abstract fun setForceTightThresholds(v: Boolean)

    abstract fun setPreferLowerDelays(v: Boolean)

    abstract fun setPreferLowerDelaysTimeoutUs(us: Int)

    abstract fun setPerfTextWanted(wanted: Boolean)

    abstract fun notifyVideoForeground()

    abstract fun notifyVideoBackground()

    // The Nordstern benchmark path. No-ops outside a benchmark build.

    abstract fun armBenchmarkCapture(
        runId: String,
        expectedDurationNs: Long,
        durationToleranceNs: Long,
        drainGraceNs: Long,
        manifestSha256: String?,
    )

    abstract fun stopBenchmarkCapture(): BenchmarkRunResult?
}
