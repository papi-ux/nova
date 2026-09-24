package com.papi.nova.binding.video

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
class PyroWaveDecoderRenderer : NovaVideoRenderer() {

    private var surface: Surface? = null
    private var handle: Long = 0
    private var format: Int = 0
    /** Frames that reached the screen, and frames that did not. Readable for a proof or a HUD. */
    var framesShown: Long = 0
        private set
    var framesRefused: Long = 0
        private set

    // What the library asks.

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        this.format = format

        val target = surface
        if (target == null) {
            LimeLog.severe("PyroWave: no surface to draw into")
            return -1
        }

        handle = PyroWave.createRenderer(target, width, height)
        if (handle == 0L) {
            LimeLog.severe("PyroWave: could not make a ${width}x$height renderer")
            return -2
        }

        LimeLog.info("PyroWave: rendering ${width}x$height at $redrawRate")
        return 0
    }

    override fun start() = Unit

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

        // Every frame of this codec is a keyframe, so a frame that fails to decode costs exactly
        // itself: the next one stands alone and there is no reference chain to repair. Asking for an
        // IDR would be asking for what is already on its way.
        if (!PyroWave.decodeAndPresent(handle, decodeUnitData, decodeUnitLength)) {
            framesRefused++
            if (framesRefused == 1L || framesRefused % 60L == 0L) {
                LimeLog.warning("PyroWave: $framesRefused frames did not reach the screen")
            }
            return MoonBridge.DR_OK
        }

        framesShown++
        return MoonBridge.DR_OK
    }

    override fun cleanup() {
        if (handle != 0L) {
            PyroWave.destroyRenderer(handle)
            handle = 0
        }
        LimeLog.info("PyroWave: $framesShown frames shown, $framesRefused refused")
    }

    override fun getCapabilities(): Int = 0

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        // SDR only, and the host refuses the stream rather than negotiating otherwise, so there is
        // nothing to switch here and nothing to warn about that has not already been said.
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

    override fun getPreferredColorSpace(): Int = MoonBridge.COLORSPACE_REC_709

    override fun getPreferredColorRange(): Int = MoonBridge.COLOR_RANGE_FULL

    override val activeVideoFormat: Int
        get() = format

    override val activeDecoderName: String = "pyrowave"

    // How the session is going.

    override fun getAverageEndToEndLatency(): Int = 0

    override fun getAverageDecoderLatency(): Int = 0

    // Null rather than false: this renderer does not measure, which is a different answer from
    // having measured and found nothing, and the overlay draws them differently.
    override fun performanceWasTracked(): Boolean? = null

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
