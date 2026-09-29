package com.papi.nova.binding.video

import android.os.SystemClock
import android.view.Surface
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.NovaBitrateAdvice
import com.papi.nova.preferences.PreferenceConfiguration

/**
 * The renderer for a PyroWave stream.
 *
 * It owns no decoder of its own. The native side holds the Vulkan device, the decoder and the
 * swapchain together, because those three share a command buffer, and this class is the handle to
 * them plus the answers Game asks of any renderer.
 *
 * Frames arrive here the same way they arrive at the MediaCodec renderer, through the bridge, and
 * cross back into native to be decoded. That is one transition and one copy per frame, which is
 * what every other codec already pays and what a 60 fps stream is already shown to afford. Skipping
 * it would mean the streaming library calling the codec directly, which means linking the two
 * together, and that trade is not worth making before something measures the hop.
 *
 * It reports no other codec. Asking for PyroWave is exclusive by design: the host either offers the
 * exact profile this client implements or the connection fails with a reason, rather than quietly
 * handing back H.264 under the name of the codec the player chose.
 */
class PyroWaveDecoderRenderer(
    private val perfListener: PerfOverlayListener,
) : NovaVideoRenderer() {

    /** Which rule produced a piece of bitrate advice. The log names it, once per stream. */
    enum class AdviceRule {
        /** The model's own estimate, for a 16:9 picture inside the sizes it was fitted on. */
        MODEL,

        /**
         * The model's own estimate for a shape other than 16:9, inside the sizes it was fitted on. The
         * model is keyed on the pixel count, so it answers, but it never saw the shape, and the flag
         * stays. The Deck's 1280x800 lands here.
         */
        MODEL_NOT_16_9,

        /**
         * Fewer pixels than 1280x720: the bits per pixel the model gives at 1280x720, at the same
         * distance and chroma, times this picture's pixels. An extrapolation.
         */
        BELOW_MODEL_EDGE,

        /**
         * More pixels than 3840x2160: the bits per pixel the model gives at 3840x2160, at the same
         * distance and chroma, times this picture's pixels. An extrapolation.
         */
        ABOVE_MODEL_EDGE,

        /** Nothing the model or its edges could answer: the old flat 0.73 bits per pixel. */
        FLAT_FALLBACK,

        /** A size or frame rate that is not positive, which describes no stream. No advice. */
        NONE,
    }

    /** Bitrate advice, and what produced it. */
    data class BitrateAdvice(
        /** The exact figure, truncated to a whole kbps. Zero when there is no advice. */
        val kbps: Int,
        val rule: AdviceRule,
        /** The viewing distance assumed, as a [PyroWaveRateModel] height factor index. */
        val heightFactor: Int,
        val chroma444: Boolean,
        /** Every flag the model raised for the picture itself, before any edge rule stepped in. */
        val flags: Set<PyroWaveRateModel.Flag>,
    ) {
        /**
         * The advice in whole Mbps, rounded up, so that setting it always satisfies it. Computed in
         * Long, so an absurd figure near the top of an Int does not wrap around to a negative one.
         */
        val mbps: Int
            get() = if (kbps <= 0) 0 else ((kbps.toLong() + 999L) / 1000L).toInt()

        /** One line for the log: the figure, and the rule, quality, distance and chroma behind it. */
        fun describe(): String {
            val chroma = if (chroma444) "4:4:4" else "4:2:0"
            val distance = "H ${PyroWaveRateModel.heightFactor(heightFactor)} (index $heightFactor)"
            val modelFlags = if (flags.isEmpty()) "" else ", model flags ${flags.sorted()}"
            return "$mbps Mbps ($kbps kbps) by rule $rule at ${NovaBitrateAdvice.pyrowaveTargetDb(heightFactor)} dB, $distance, $chroma$modelFlags"
        }
    }

    /** What Game says when a stream is sent under the advice for it. See [bitrateWarning]. */
    data class BitrateWarning(
        /** The line for the log, said for every stream under the advice. */
        val logLine: String,
        /** Whether the player is told too, which needs a bitrate setting that can still go higher. */
        val tellPlayer: Boolean,
    )

    companion object {
        /**
         * The flat figure the advice used before it had a model, kept only as its last fallback.
         *
         * Measured by eye on the same content: soft at 0.18 bits per pixel, good at 0.73. The model
         * replaced it because what this codec needs grows far more slowly than the pixel count, so one
         * figure for every size starves 720p and overshoots 4K. It is still better than no advice, so a
         * question the model and its edge rule give nothing for gets this rather than silence. No
         * stream Nova builds today reaches it: the quality is fixed, [viewingHeightFactor] only names
         * distances the table has, and a frame rate that fits in an Int cannot carry the model's answer
         * past a double. It is there so that a change to any of those can never make the advice vanish.
         */
        private const val FALLBACK_BITS_PER_PIXEL = 0.73

        // The two edges of the sizes the model was fitted on, both 16:9, so the model answers for each
        // without a flag.
        private const val MODEL_SMALLEST_WIDTH = 1280
        private const val MODEL_SMALLEST_HEIGHT = 720
        private const val MODEL_LARGEST_WIDTH = 3840
        private const val MODEL_LARGEST_HEIGHT = 2160

        /**
         * The viewing distance the advice assumes, as a [PyroWaveRateModel] height factor index.
         *
         * H 2.0 (index 8) when the device is a television or the stream is shown on an external
         * display: a screen across a room or on a desk, watched from about a monitor's distance, which
         * is also the distance upstream's subjective tests used. H 2.87 (index 15) otherwise, which is a
         * phone, a handheld or a tablet showing the stream on its own screen. A phone or a handheld held
         * at arm's length sits farther away than any distance the model covers (a 5.5 inch 16:9 panel at
         * 35 cm is about H 5), and the farther away, the less detail an eye can find to miss. So the
         * farthest distance the model does cover is the nearest answer it has, and for those screens it
         * errs toward asking for more than they need.
         * A large tablet held nearer than H 2.87 can need more than this assumption asks for.
         *
         * [television] is the UI mode, the same check the system bars make. [onExternalDisplay] is
         * Game's isOnExternalDisplay, set once from the display the stream's window is on: true when
         * that is not the default display. It is the one signal Nova has for where the stream is shown,
         * and every other external display decision in Game reads it. Nothing Nova reads tells a second
         * built in panel from a monitor, so a dual screen handheld such as the AYN Thor that streams onto
         * a panel other than its default one is advised for H 2.0. That asks for more than the panel
         * needs, not less.
         * A mirrored phone still uses the default display's assumption: these signals do not
         * distinguish mirroring from watching the phone's own panel.
         */
        fun viewingHeightFactor(television: Boolean, onExternalDisplay: Boolean): Int =
            if (television || onExternalDisplay) {
                PyroWaveRateModel.HEIGHT_FACTOR_2_00
            } else {
                PyroWaveRateModel.HEIGHT_FACTOR_2_87
            }

        /**
         * Whether the advice assumes 4:4:4, given the formats Nova offers the host.
         *
         * The advice is given before the host and client negotiate, so it cannot read the format they
         * settle on. The player chooses PyroWave, not a chroma, and Nova's offer for it carries both.
         * The streaming library settles on 4:4:4 whenever it is offered and the host advertises it, and
         * Polaris advertises 4:4:4 whenever it serves PyroWave at all, because the two are one encoder
         * an enum apart. So what the offer holds is what the stream will carry: 4:4:4 if the offer has
         * it, 4:2:0 if not. A host that answered 4:2:0 to a 4:4:4 offer would be advised up to about a
         * fifth more than it needed, which is the side to err on.
         */
        fun adviceChroma444(offeredFormats: Int): Boolean =
            (offeredFormats and
                (MoonBridge.VIDEO_FORMAT_PYROWAVE_444 or MoonBridge.VIDEO_FORMAT_PYROWAVE_444_10BIT)) != 0

        /**
         * Requested stream kbps for the calibrated target: 31 dB at handheld distance, 35 dB
         * across the room. The model and nearest-edge rule produce encoder kbps; gross-up uses
         * the same audio/FEC allowance as NovaBitrateAdvice before comparing to launch requests.
         * Advice remains uncapped, with its model/extrapolation provenance for diagnostics.
         */
        fun bitrateAdvice(width: Int, height: Int, fps: Int, chroma444: Boolean, heightFactor: Int): BitrateAdvice {
            if (width <= 0 || height <= 0 || fps <= 0) {
                return BitrateAdvice(0, AdviceRule.NONE, heightFactor, chroma444, emptySet())
            }
            val pixelsPerSecond = width.toDouble() * height.toDouble() * fps.toDouble()
            val estimate = PyroWaveRateModel.estimate(
                NovaBitrateAdvice.pyrowaveTargetDb(heightFactor), width, height, heightFactor, chroma444, fps.toDouble(),
            )
            val flags = estimate.flags
            val mbps = estimate.mbps
            if (mbps != null) {
                val rule = if (estimate.extrapolated) AdviceRule.MODEL_NOT_16_9 else AdviceRule.MODEL
                return BitrateAdvice(kbpsOf(mbps * 1_000_000.0), rule, heightFactor, chroma444, flags)
            }

            val edge = when {
                PyroWaveRateModel.Flag.PIXELS_BELOW_MODEL in flags -> AdviceRule.BELOW_MODEL_EDGE
                PyroWaveRateModel.Flag.PIXELS_ABOVE_MODEL in flags -> AdviceRule.ABOVE_MODEL_EDGE
                else -> null
            }
            if (edge != null) {
                val edgeWidth = if (edge == AdviceRule.BELOW_MODEL_EDGE) MODEL_SMALLEST_WIDTH else MODEL_LARGEST_WIDTH
                val edgeHeight = if (edge == AdviceRule.BELOW_MODEL_EDGE) MODEL_SMALLEST_HEIGHT else MODEL_LARGEST_HEIGHT
                val edgeMbps = PyroWaveRateModel.estimate(
                    NovaBitrateAdvice.pyrowaveTargetDb(heightFactor), edgeWidth, edgeHeight, heightFactor, chroma444, fps.toDouble(),
                ).mbps
                if (edgeMbps != null) {
                    val bitsPerPixel =
                        edgeMbps * 1_000_000.0 / (edgeWidth.toDouble() * edgeHeight.toDouble() * fps.toDouble())
                    return BitrateAdvice(kbpsOf(bitsPerPixel * pixelsPerSecond), edge, heightFactor, chroma444, flags)
                }
            }

            return BitrateAdvice(
                kbpsOf(FALLBACK_BITS_PER_PIXEL * pixelsPerSecond),
                AdviceRule.FLAT_FALLBACK,
                heightFactor,
                chroma444,
                flags,
            )
        }

        /** A bitrate in bits per second as whole kbps, truncated, as the advice has always been said. */
        private fun kbpsOf(bitsPerSecond: Double): Int =
            NovaBitrateAdvice.requestForEncoder((bitsPerSecond / 1000.0).toInt().coerceAtLeast(1))

        /** The advice in kbps. See [bitrateAdvice] for how it is reached. */
        fun recommendedKbps(width: Int, height: Int, fps: Int, chroma444: Boolean, heightFactor: Int): Int =
            bitrateAdvice(width, height, fps, chroma444, heightFactor).kbps

        /** Whole requested Mbps, rounded up so following the displayed advice is sufficient. */
        fun advisedMbps(width: Int, height: Int, fps: Int, chroma444: Boolean, heightFactor: Int): Int =
            bitrateAdvice(width, height, fps, chroma444, heightFactor).mbps

        /** Compare exact requested kbps; rounded display text must not warn on calibrated Auto. */
        fun bitrateWarning(streamKbps: Int, width: Int, height: Int, fps: Int, advice: BitrateAdvice, maximumKbps: Int = NovaBitrateAdvice.LEGACY_MANUAL_MAX_KBPS, automatic: Boolean = false): BitrateWarning? {
            val wantedMbps = advice.mbps
            if (wantedMbps <= 0 || streamKbps >= advice.kbps) {
                return null
            }
            val line = "PyroWave: $streamKbps kbps for ${width}x$height at $fps fps; it wants about $wantedMbps Mbps"
            val actionableMaximum = minOf(maximumKbps, PreferenceConfiguration.MAX_BITRATE_KBPS,
                if (automatic) NovaBitrateAdvice.AUTOMATIC_MAX_KBPS else NovaBitrateAdvice.MANUAL_MAX_KBPS)
            if (streamKbps >= actionableMaximum) {
                return BitrateWarning(
                    "$line, over the ${actionableMaximum / 1000} Mbps maximum of the " +
                        "bitrate setting, so the player is not told",
                    tellPlayer = false,
                )
            }
            return BitrateWarning(line, tellPlayer = true)
        }

        /**
         * How many frames the transport did not deliver between two that it did.
         *
         * Zero before the first frame, because there is nothing to have missed yet and a first frame
         * numbered in the thousands would otherwise read as a thousand losses. Zero for a number that
         * does not advance, which is a repeat rather than a gap, and zero for one that goes backwards,
         * which is a stream numbering itself from the start again.
         */
        fun framesMissedBetween(previous: Int, next: Int): Int {
            if (previous <= 0 || next <= previous) {
                return 0
            }
            return next - previous - 1
        }
    }

    private var surface: Surface? = null
    private val renderer = PyroWaveRendererLifetime(PyroWave::destroyRenderer)
    private var format: Int = 0
    private var width: Int = 0
    private var height: Int = 0

    // The window the HUD reads, reset every time it is reported.
    private var windowStartedMs: Long = 0
    private var windowFrames: Long = 0
    private var windowDrawn: Long = 0
    private var windowDecodeNs: Long = 0
    private var totalDecodeNs: Long = 0

    // What the host says it spent capturing and encoding each frame, in tenths of a millisecond,
    // which is the unit the frame header carries it in.
    private var windowHostLatency: Long = 0
    private var windowHostLatencyFrames: Long = 0
    /** Frames that reached the screen, and frames that did not. Readable for a proof or a HUD. */
    var framesShown: Long = 0
        private set
    var framesRefused: Long = 0
        private set

    /**
     * Frames the transport never delivered, counted from the gaps in the numbers that arrive.
     *
     * The same way MediaCodecDecoderRenderer counts it, and the only kind of loss this codec can
     * suffer: a frame either arrives whole or is not offered at all, because the library reassembles
     * it or drops it before this sees it.
     */
    var framesMissing: Long = 0
        private set

    private var lastFrameNumber = 0
    private var windowMissing: Long = 0

    // What the library asks.

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        this.format = format
        this.width = width
        this.height = height

        val target = surface
        if (target == null) {
            LimeLog.severe("PyroWave: no surface to draw into")
            return -1
        }

        // The format the host and client settled on says which chroma this stream carries, and the
        // decoder has to be built for it: it reads the chroma out of every frame's sequence header
        // and refuses one that disagrees with how it was made.
        val chroma444 = (format and
            (MoonBridge.VIDEO_FORMAT_PYROWAVE_444 or MoonBridge.VIDEO_FORMAT_PYROWAVE_444_10BIT)) != 0

        // The colourimetry, from the format the two ends settled on. It decides the swapchain, the
        // precision of the decoded planes and the matrix the present shader inverts, and nothing would
        // refuse a mismatch: an HDR frame shown as Rec. 709 is dark and oversaturated, and an SDR frame
        // shown as PQ is washed out, and neither says anything.
        val hdr = (format and
            (MoonBridge.VIDEO_FORMAT_PYROWAVE_10BIT or MoonBridge.VIDEO_FORMAT_PYROWAVE_444_10BIT)) != 0

        val handle = renderer.create {
            PyroWave.createRenderer(target, width, height, chroma444, hdr)
        }
        if (handle == 0L) {
            LimeLog.severe("PyroWave: could not make a ${width}x$height renderer")
            return -2
        }

        LimeLog.info("PyroWave: rendering ${width}x$height at $redrawRate")
        return 0
    }

    override fun start() {
        windowStartedMs = SystemClock.elapsedRealtime()
    }

    override fun stop() = Unit

    override fun submitDecodeUnit(
        decodeUnitData: ByteArray?,
        decodeUnitLength: Int,
        decodeUnitType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long,
    ): Int {
        if (decodeUnitData == null || decodeUnitLength <= 0) {
            return MoonBridge.DR_NEED_IDR
        }

        // A gap in the numbering is a frame the transport did not deliver. A stream that starts its
        // numbering over is not a gap, so the run restarts with it.
        if (frameNumber < lastFrameNumber) {
            lastFrameNumber = 0
        }
        val missing = framesMissedBetween(lastFrameNumber, frameNumber).toLong()
        windowMissing += missing
        framesMissing += missing
        lastFrameNumber = frameNumber

        // Every frame of this codec is a keyframe, so a frame that fails to decode costs exactly
        // itself: the next one stands alone and there is no reference chain to repair. Asking for an
        // IDR would be asking for what is already on its way.
        // Zero means the host did not report it, which is different from reporting zero, so it is
        // left out of the average rather than counted as a fast frame.
        val hostTenths = frameHostProcessingLatency.code
        if (hostTenths > 0) {
            windowHostLatency += hostTenths.toLong()
            windowHostLatencyFrames++
        }

        val startedNs = System.nanoTime()
        val drew = renderer.useOrNull { handle ->
            PyroWave.decodeAndPresent(handle, decodeUnitData, decodeUnitLength)
        } ?: return MoonBridge.DR_NEED_IDR
        val elapsedNs = System.nanoTime() - startedNs

        // Decode and present together, because that is what the call does: the compute work and the
        // draw that reads it are one submit under one fence, and timing half of it would be timing
        // nothing anyone waits for.
        windowFrames++
        windowDecodeNs += elapsedNs
        totalDecodeNs += elapsedNs

        if (drew) {
            framesShown++
            windowDrawn++
        }
        else {
            framesRefused++
            if (framesRefused == 1L || framesRefused % 60L == 0L) {
                LimeLog.warning("PyroWave: $framesRefused frames did not reach the screen")
            }
        }

        reportIfWindowElapsed()
        return MoonBridge.DR_OK
    }

    /**
     * Hand the HUD a second's worth of measurements, once a second.
     *
     * Counted here rather than sampled on a timer, because this is the only thread that knows a
     * frame happened and there is no queue between it and the screen to ask instead.
     */
    private fun reportIfWindowElapsed() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - windowStartedMs
        if (elapsedMs < 1000L) {
            return
        }

        val seconds = elapsedMs.toDouble() / 1000.0
        val decodeMs = if (windowFrames > 0) {
            windowDecodeNs.toDouble() / windowFrames.toDouble() / 1_000_000.0
        }
        else {
            0.0
        }
        val rttInfo = try {
            MoonBridge.getEstimatedRttInfo()
        } catch (e: Throwable) {
            0L
        }

        perfListener.onPerfSample(
            PerfOverlaySample(
                fps = windowDrawn.toDouble() / seconds,
                incomingFps = windowFrames.toDouble() / seconds,
                renderedFps = windowDrawn.toDouble() / seconds,
                width = width,
                height = height,
                codec = "PyroWave",
                rttMs = (rttInfo shr 32).toInt(),
                rttVarianceMs = rttInfo.toInt(),
                decodeTimeMs = decodeMs,
                // What the network lost, which is what this field is read as: the HUD prints it as
                // packet loss and turns red on it.
                //
                // It used to be the frames this renderer was handed and could not draw, with a comment
                // saying those are not network loss. They are not, and putting them here blamed the
                // network for a fault on this device, which is the one place a player cannot fix it.
                // They are still visible: drawn frames and received frames are both reported above, a
                // gap between them is a refusal, and every refusal is logged.
                packetLossPct = if (windowFrames + windowMissing > 0) {
                    windowMissing.toDouble() / (windowFrames + windowMissing).toDouble() * 100.0
                }
                else {
                    0.0
                },
                monotonicTimestampMs = now,
                framesExpected = framesShown + framesRefused + framesMissing,
                framesReceived = framesShown + framesRefused,
                framesRendered = framesShown,
                framesLost = framesMissing,
                hostProcessingLatencyMs = windowHostLatencyFrames
                    .takeIf { it > 0 }
                    ?.let { windowHostLatency.toDouble() / 10.0 / it.toDouble() },
            )
        )

        windowStartedMs = now
        windowFrames = 0
        windowMissing = 0
        windowDrawn = 0
        windowDecodeNs = 0
        windowHostLatency = 0
        windowHostLatencyFrames = 0
    }

    override fun cleanup() {
        renderer.close()
        LimeLog.info(
            "PyroWave: $framesShown frames shown, $framesRefused refused, $framesMissing never arrived",
        )
    }

    override fun getCapabilities(): Int = 0

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        // Nothing to switch. This renderer's colourimetry is decided by the format the session
        // negotiated and built into the swapchain at creation, so a stream is HDR or it is not for its
        // whole life, and the metadata carries nothing this presentation path reads.
    }

    // Where the picture goes.

    override fun setRenderTarget(renderTarget: Surface?) {
        surface = renderTarget
    }

    override fun prepareForStop() {
        // Stop admitting frames and wait for the current native call before releasing the
        // swapchain. Surface teardown can run before the network decode thread has stopped.
        renderer.close()
    }

    override fun refreshDisplayParameters() {
        // The swapchain is sized from the surface and rebuilt with it, and this codec's frame size
        // is the stream's rather than the display's, so a display that changed mode changes nothing
        // that is decided here.
    }

    // What this device can decode.

    override val isAvcSupported: Boolean = false
    override val isHevcSupported: Boolean = false
    override val isHevcMain10Hdr10Supported: Boolean = false
    override val isAv1Supported: Boolean = false
    override val isAv1Main10Supported: Boolean = false

    // None of the above and HDR10 all the same. There is no hardware decoder profile in this codec to
    // ask about: the ten bit formats are the codec's own, the frames arrive as PQ BT.2020, and this
    // renderer builds a swapchain to match.
    //
    // Whether this particular surface can present that is a question only the surface can answer, so
    // it is asked at creation, where a no fails the renderer with a line saying which one said it
    // rather than guessing here. The panel's own HDR capability is asked earlier still, by the part of
    // Nova that decides whether to request an HDR stream in the first place.
    override val isHdr10Supported: Boolean = true

    override fun getPreferredColorSpace(): Int = MoonBridge.COLORSPACE_REC_709

    override fun getPreferredColorRange(): Int = MoonBridge.COLOR_RANGE_FULL

    override val activeVideoFormat: Int
        get() = format

    override val activeDecoderName: String = "pyrowave"

    // How the session is going.

    // Decode and present, which for this renderer is the whole of it: there is no queue between
    // the frame arriving and the picture appearing, so the two numbers are the same measurement.
    override fun getAverageEndToEndLatency(): Int = averageDecodeMs()

    override fun getAverageDecoderLatency(): Int = averageDecodeMs()

    private fun averageDecodeMs(): Int {
        val frames = framesShown + framesRefused
        if (frames == 0L) {
            return 0
        }
        return (totalDecodeNs / frames / 1_000_000L).toInt()
    }

    override fun performanceWasTracked(): Boolean = framesShown + framesRefused > 0

    override fun getMinDecoderLatency(): String = ""

    override fun getMinDecoderLatencyFullLog(): String = ""

    // Knobs for a decoder that queues. This one does not: a frame is decoded and presented inside
    // the call that delivers it, so there is no delay to prefer or threshold to tighten.

    override fun setForceTightThresholds(v: Boolean) = Unit

    override fun setPreferLowerDelays(v: Boolean) = Unit

    override fun setPreferLowerDelaysTimeoutUs(us: Int) = Unit

    override fun setPerfTextWanted(wanted: Boolean) = Unit

    override fun notifyVideoForeground() = Unit

    override fun notifyVideoBackground() = Unit

    override fun armBenchmarkCapture(
        runId: String,
        expectedDurationNs: Long,
        durationToleranceNs: Long,
        drainGraceNs: Long,
        manifestSha256: String?,
    ) = Unit

    override fun stopBenchmarkCapture(): BenchmarkRunResult? = null
}
