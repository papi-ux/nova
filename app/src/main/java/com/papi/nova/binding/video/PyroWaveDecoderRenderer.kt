package com.papi.nova.binding.video

import android.os.SystemClock
import android.view.Surface
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.jni.MoonBridge

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

    companion object {
        /**
         * Bits per pixel at which this codec looks like itself.
         *
         * Every frame is coded from scratch, so quality follows the per frame budget directly and
         * there is no prediction to lean on. Measured by eye on the same content: soft at 0.18, good
         * at 0.73.
         *
         * This is the good one, and it used to be 0.35. That number is halfway to soft, and the
         * paragraph describing it said so in as many words: where a picture stops being worth looking
         * at. Advice is read as what to set, not as a floor to stay above, so a player who followed it
         * saw the codec at its worst and had every reason to think that was the codec.
         *
         * It is a large number, 91 Mbps for 1080p60 against the 20 Mbps Nova defaults to, and that is
         * the honest shape of an intra only codec rather than something to round down out of
         * politeness. A player who cannot spend it is better served knowing why the picture is soft.
         */
        private const val GOOD_BITS_PER_PIXEL = 0.73

        /**
         * The bitrate this codec wants for a stream of this shape, in kbps.
         *
         * Frame rate multiplies it exactly, unlike an inter frame codec where the extra frames are
         * more similar to their neighbours and cost far less than the first one.
         */
        fun recommendedKbps(width: Int, height: Int, fps: Int): Int {
            if (width <= 0 || height <= 0 || fps <= 0) return 0
            val bits = GOOD_BITS_PER_PIXEL * width.toDouble() * height.toDouble() * fps.toDouble()
            return (bits / 1000.0).toInt()
        }

        /**
         * The same advice as a whole number of Mbps, which is the unit it is said in.
         *
         * Said and compared in the same unit on purpose. Comparing the exact figure against a rounded
         * one is advice nobody can take: a player told 91 Mbps who then sets 91 is at 91000 kbps,
         * still under the 90846 this actually wanted rounded up, and is told the same thing again on
         * every launch forever. Rounding up rather than down so that following the advice is always
         * enough to satisfy it.
         */
        fun advisedMbps(width: Int, height: Int, fps: Int): Int {
            val kbps = recommendedKbps(width, height, fps)
            return if (kbps <= 0) 0 else (kbps + 999) / 1000
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
    private var handle: Long = 0
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

        handle = PyroWave.createRenderer(target, width, height, chroma444, hdr)
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
        if (handle == 0L || decodeUnitData == null || decodeUnitLength <= 0) {
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
        val drew = PyroWave.decodeAndPresent(handle, decodeUnitData, decodeUnitLength)
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
        if (handle != 0L) {
            PyroWave.destroyRenderer(handle)
            handle = 0
        }
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
        // The renderer holds the swapchain that holds the surface, so it goes before the surface
        // does. Destroying it here rather than in cleanup is what keeps a surface teardown from
        // pulling the ground out from under a submit in flight.
        if (handle != 0L) {
            PyroWave.destroyRenderer(handle)
            handle = 0
        }
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
