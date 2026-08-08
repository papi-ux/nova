package com.papi.nova.binding.video

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.WindowManager
import com.papi.nova.BuildConfig
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.TrafficStatsHelper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters

class MediaCodecDecoderRenderer(
    activity: Activity,
    private val prefs: PreferenceConfiguration,
    private val crashListener: CrashListener,
    private val consecutiveCrashCount: Int,
    meteredData: Boolean,
    requestedHdr: Boolean,
    private val invertResolution: Boolean,
    private val glRenderer: String,
    private val perfListener: PerfOverlayListener,
) : VideoDecoderRenderer(), Choreographer.FrameCallback {
    private var preferLowerDelays = false

    @Volatile
    private var forceTightThresholds = false

    fun setForceTightThresholds(v: Boolean) {
        forceTightThresholds = v
    }

    // Bounded + synchronized: put() runs on the decode-unit submission thread,
    // get()/remove() run on the renderer thread, and android.util.LongSparseArray
    // is neither thread-safe nor bounded. Unconsumed entries otherwise only
    // clear on a full stream reset (resetRollingPerfStatsForNewStream), so any
    // frame whose output is dropped rather than presented - routine under the
    // non-BALANCED frame-pacing policies - leaked forever.
    private val enqueueNsByPtsUs = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
                size > ENQUEUE_MAP_MAX_ENTRIES
        },
    )

    @Volatile
    private var preferLowerDelaysTimeoutUs = 2000

    fun setPreferLowerDelaysTimeoutUs(us: Int) {
        preferLowerDelaysTimeoutUs = max(0, us)
    }

    private fun releaseWithPolicy(bufferIndex: Int, frameTimeNanos: Long) {
        try {
            val now = System.nanoTime()
            val immediate = preferLowerDelays && frameTimeNanos <= now + 300_000L
            if (immediate) {
                videoDecoder!!.releaseOutputBuffer(bufferIndex, true)
            } else {
                videoDecoder!!.releaseOutputBuffer(bufferIndex, frameTimeNanos)
            }
        } catch (_: Throwable) {
            try {
                videoDecoder!!.releaseOutputBuffer(bufferIndex, true)
            } catch (_: Throwable) {
            }
        }
    }

    private fun presentFrame(bufferIndex: Int, timestampNs: Long) {
        if (preferLowerDelays) {
            releaseWithPolicy(bufferIndex, System.nanoTime())
        } else {
            videoDecoder!!.releaseOutputBuffer(bufferIndex, timestampNs)
        }
    }

    private fun dropFrame(bufferIndex: Int, intentional: Boolean = false) {
        videoDecoder!!.releaseOutputBuffer(bufferIndex, false)
        if (intentional) {
            activeWindowVideoStats.intentionalFrameDrops++
        }
    }

    private fun getOutputDequeueTimeoutUs(): Int =
        if (preferLowerDelays) max(250, preferLowerDelaysTimeoutUs) else preferLowerDelaysTimeoutUs

    private fun updateDecodeLatencyStats(presentationTimeUs: Long) {
        val enqNs = enqueueNsByPtsUs[presentationTimeUs]
        if (enqNs != null) {
            enqueueNsByPtsUs.remove(presentationTimeUs)
            val decMs = (System.nanoTime() - enqNs) / 1_000_000L
            if (decMs in 0..999) {
                activeWindowVideoStats.decoderTimeMs += decMs
                if (!USE_FRAME_RENDER_TIME) {
                    activeWindowVideoStats.totalTimeMs += decMs
                }
            }
        }

        logSampledStageTiming(presentationTimeUs)
    }

    private var t3t4LogCounter = 0

    // Nordstern T3->T4. presentationTimeUs is enqueueTimeMs (native - the
    // moment reassembly completed and the frame was queued for the decoder,
    // per moonlight-common-c's Limelight.h) converted to microseconds by
    // queueNextInputBuffer(), with a rare +1us bump only on same-millisecond
    // collisions. Deriving T3 from it directly, rather than from a second
    // stored value, means this sample doesn't depend on enqueueNsByPtsUs
    // still holding an entry, so it isn't affected by that map's eviction or
    // by this particular frame being dropped rather than presented. T3 and
    // System.nanoTime() (T4) share Android's CLOCK_MONOTONIC domain - the
    // same cross-boundary assumption queueNextInputBuffer() already makes.
    private fun logSampledStageTiming(presentationTimeUs: Long) {
        t3t4LogCounter++
        if (t3t4LogCounter % T3_T4_LOG_SAMPLE_INTERVAL != 0) {
            return
        }

        val t3Ns = presentationTimeUs * 1000L
        val t3ToT4Ms = (System.nanoTime() - t3Ns) / 1_000_000.0
        if (t3ToT4Ms in 0.0..1000.0) {
            LimeLog.info(
                "Nova: stage_timing t3_to_t4_ms=" +
                    String.format(Locale.US, "%.2f", t3ToT4Ms) +
                    " pts_us=$presentationTimeUs",
            )
        }
    }

    fun setPreferLowerDelays(v: Boolean) {
        preferLowerDelays = v
    }

    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var av1Decoder: MediaCodecInfo? = null

    private val vpsBuffers = ArrayList<ByteArray>()
    private val spsBuffers = ArrayList<ByteArray>()
    private val ppsBuffers = ArrayList<ByteArray>()
    private var submittedCsd = false
    private var currentHdrMetadata: ByteArray? = null

    private var nextInputBufferIndex = -1
    private var nextInputBuffer: ByteBuffer? = null

    private val context: Context = activity
    private val activity: Activity = activity
    private var videoDecoder: MediaCodec? = null
    private var rendererThread: Thread? = null
    private var needsSpsBitstreamFixup = false
    private var isExynos4 = false
    private var adaptivePlayback = false
    private var directSubmit = false
    private var fusedIdrFrame = false
    private var constrainedHighProfile = false
    private var refFrameInvalidationAvc = false
    private var refFrameInvalidationHevc = false
    private var refFrameInvalidationAv1 = false
    private var optimalSlicesPerFrame: Byte = 0
    private var refFrameInvalidationActive = false
    private var initialWidth = 0
    private var initialHeight = 0
    private var videoFormat = 0

    @Volatile
    private var activeDecoderNameValue = ""
    val activeDecoderName: String
        get() = activeDecoderNameValue
    private var renderTarget: Surface? = null

    @Volatile
    private var stopping = false
    private var reportedCrash = false
    private var foreground = true

    private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val codecRecoveryMonitor = Object()
    private var codecRecoveryThreadQuiescedFlags = 0
    private var codecRecoveryAttempts = 0

    private var inputFormat: MediaFormat? = null
    private var outputFormat: MediaFormat? = null
    private var configuredFormat: MediaFormat? = null

    private var needsBaselineSpsHack = false
    private var savedSps: SeqParameterSet? = null

    private var initialException: RendererException? = null
    private var initialExceptionTimestamp: Long = 0

    private val activeWindowVideoStats = VideoStats()
    private val lastWindowVideoStats = VideoStats()
    private val globalVideoStats = VideoStats()

    private var lastTimestampUs: Long = 0
    private var lastFrameNumber = 0
    private var refreshRate = 0

    private var minDecodeTime = Float.MAX_VALUE
    private var minDecodeTimeFullLog = ""

    // Set from the UI thread when the perf overlay or NovaHUD becomes visible; read on the
    // decode thread to skip building overlay text nobody is going to display.
    @Volatile
    private var perfTextWanted = false

    // appVsyncOffsetNanos goes through a locked WindowManager display lookup; cache it off
    // the frame path and refresh on display changes.
    @Volatile
    private var cachedAppVsyncOffsetNanos: Long = 0

    private var lastNetDataNum: Long = 0
    private val outputBufferQueue = ArrayBlockingQueue<Int>(OUTPUT_BUFFER_QUEUE_LIMIT)
    private var lastRenderedFrameTimeNanos: Long = 0
    private var choreographerHandlerThread: HandlerThread? = null
    private var choreographerHandler: Handler? = null
    private val stopPrepared = AtomicBoolean(false)

    private var numSpsIn = 0
    private var numPpsIn = 0
    private var numVpsIn = 0
    private var numFramesIn = 0
    private var numFramesOut = 0

    private var targetFps = 0

    private fun resetRollingPerfStatsForNewStream(reason: String) {
        LimeLog.info("Nova: Resetting decoder perf stats for $reason")
        activeWindowVideoStats.clear()
        lastWindowVideoStats.clear()
        globalVideoStats.clear()
        lastFrameNumber = 0
        lastTimestampUs = 0
        lastNetDataNum = 0
        minDecodeTime = Float.MAX_VALUE
        minDecodeTimeFullLog = ""
        enqueueNsByPtsUs.clear()
    }

    init {
        avcDecoder = findAvcDecoder()
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: " + avcDecoder!!.name)
        } else {
            LimeLog.warning("No AVC decoder found")
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr)
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: " + hevcDecoder!!.name)
        } else {
            LimeLog.info("No HEVC decoder found")
        }

        av1Decoder = findAv1Decoder(prefs)
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: " + av1Decoder!!.name)
        } else {
            LimeLog.info("No AV1 decoder found")
        }

        var avcOptimalSlicesPerFrame = 0
        var hevcOptimalSlicesPerFrame = 0
        if (avcDecoder != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder!!.name)
            refFrameInvalidationAvc =
                MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder!!.name, initialHeight)
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder!!.name).toInt()

            if (directSubmit) {
                LimeLog.info("Decoder " + avcDecoder!!.name + " will use direct submit")
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder " + avcDecoder!!.name + " will use reference frame invalidation for AVC")
            }
            LimeLog.info("Decoder " + avcDecoder!!.name + " wants " + avcOptimalSlicesPerFrame + " slices per frame")
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder!!)
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder!!.name).toInt()

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder " + hevcDecoder!!.name + " will use reference frame invalidation for HEVC")
            }

            LimeLog.info("Decoder " + hevcDecoder!!.name + " wants " + hevcOptimalSlicesPerFrame + " slices per frame")
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder!!)

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder " + av1Decoder!!.name + " will use reference frame invalidation for AV1")
            }
        }

        optimalSlicesPerFrame = max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame).toByte()
        LimeLog.info("Requesting $optimalSlicesPerFrame slices per frame")

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = false
            refFrameInvalidationHevc = false
            LimeLog.warning("Disabling RFI due to previous crash")
        }
    }

    private fun findAvcDecoder(): MediaCodecInfo? {
        var decoder = MediaCodecHelper.findProbableSafeDecoder(
            "video/avc",
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        )
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc")
        }
        return decoder
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun decoderCanMeetPerformancePoint(
        caps: MediaCodecInfo.VideoCapabilities?,
        prefs: PreferenceConfiguration,
    ): Boolean {
        if (caps == null) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetPerfPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(
                initialWidth,
                initialHeight,
                Math.round(prefs.fps),
            )
            val perfPoints = caps.supportedPerformancePoints
            if (perfPoints != null) {
                for (perfPoint in perfPoints) {
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true
                    }
                }

                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val fpsRange = caps.getAchievableFrameRatesFor(initialWidth, initialHeight)
                if (fpsRange != null) {
                    return prefs.fps <= fpsRange.upper
                }
            } catch (_: IllegalArgumentException) {
                return false
            }
        }

        return caps.areSizeAndRateSupported(initialWidth, initialHeight, prefs.fps.toDouble())
    }

    private fun decoderCanMeetPerformancePointWithHevcAndNotAvc(
        hevcDecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo?,
        prefs: PreferenceConfiguration,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && avcDecoderInfo != null) {
            val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities
            val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) &&
                decoderCanMeetPerformancePoint(hevcCaps, prefs)
        }
        return false
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotHevc(
        av1DecoderInfo: MediaCodecInfo,
        hevcDecoderInfo: MediaCodecInfo?,
        prefs: PreferenceConfiguration,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && hevcDecoderInfo != null) {
            val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities
            val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities

            return !decoderCanMeetPerformancePoint(hevcCaps, prefs) &&
                decoderCanMeetPerformancePoint(av1Caps, prefs)
        }
        return false
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotAvc(
        av1DecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo?,
        prefs: PreferenceConfiguration,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && avcDecoderInfo != null) {
            val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities
            val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) &&
                decoderCanMeetPerformancePoint(av1Caps, prefs)
        }
        return false
    }

    private fun findHevcDecoder(
        prefs: PreferenceConfiguration,
        meteredNetwork: Boolean,
        requestedHdr: Boolean,
    ): MediaCodecInfo? {
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null
        }

        val hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - " + hevcDecoderInfo.name)

                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder")
                } else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming")
                } else if (initialWidth > 4096 || initialHeight > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming")
                } else if (
                    avcDecoder != null &&
                    decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)
                ) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point")
                } else {
                    return null
                }
            }
        }

        return hevcDecoderInfo
    }

    private fun findAv1Decoder(prefs: PreferenceConfiguration): MediaCodecInfo? {
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null
        }

        val decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1)
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - " + decoderInfo.name)

                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder")
                } else if (hevcDecoder != null &&
                    decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)
                ) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                } else if (hevcDecoder == null &&
                    decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)
                ) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                } else {
                    return null
                }
            }
        }

        return decoderInfo
    }

    fun setRenderTarget(renderTarget: Surface?) {
        this.renderTarget = renderTarget
    }

    val isHevcSupported: Boolean
        get() = hevcDecoder != null

    val isAvcSupported: Boolean
        get() = avcDecoder != null

    val isHevcMain10Hdr10Supported: Boolean
        get() {
        val decoder = hevcDecoder ?: return false

        for (profileLevel in decoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder " + decoder.name + " supports HEVC Main10 HDR10")
                return true
            }
        }

        return false
    }

    val isAv1Supported: Boolean
        get() = av1Decoder != null

    val isAv1Main10Supported: Boolean
        get() {
        val decoder = av1Decoder ?: return false

        for (profileLevel in decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder " + decoder.name + " supports AV1 Main 10 HDR10")
                return true
            }
        }

        return false
    }

    fun getPreferredColorSpace(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
            MoonBridge.COLORSPACE_REC_709
        } else {
            MoonBridge.COLORSPACE_REC_601
        }
    }

    fun getPreferredColorRange(): Int {
        return if (prefs.fullRange) {
            MoonBridge.COLOR_RANGE_FULL
        } else {
            MoonBridge.COLOR_RANGE_LIMITED
        }
    }

    fun notifyVideoForeground() {
        foreground = true
        refreshDisplayParameters()
    }

    fun setPerfTextWanted(wanted: Boolean) {
        perfTextWanted = wanted
    }

    fun refreshDisplayParameters() {
        cachedAppVsyncOffsetNanos = runCatching {
            activity.windowManager.defaultDisplay.appVsyncOffsetNanos
        }.getOrDefault(0L)
    }

    private fun buildPerfText(
        lastTwo: VideoStats,
        fps: VideoStatsFps,
        decoder: String,
        decodeTimeMs: Float,
        rttInfo: Long,
    ): String {
        val sb = StringBuilder()
        if (prefs.enablePerfOverlayLite) {
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()) {
                val netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid()) +
                    TrafficStatsHelper.getPackageTxBytes(Process.myUid())
                if (lastNetDataNum != 0L) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth) + ": ")
                    val realtimeNetData = (netData - lastNetDataNum) / 1024f
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format("%.2f", realtimeNetData / 1024f) + "M/s\t ")
                    } else {
                        sb.append(String.format("%.2f", realtimeNetData) + "K/s\t ")
                    }
                }
                lastNetDataNum = netData
            }
            sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay) + ": ")
            sb.append(context.getString(R.string.perf_overlay_lite_net, (rttInfo shr 32).toInt()))
            sb.append(" / ")
            sb.append(context.getString(R.string.perf_overlay_lite_dectime, decodeTimeMs))
            sb.append("\t")
            sb.append(context.getString(R.string.perf_overlay_lite_packet_loss) + ": ")
            sb.append(
                context.getString(
                    R.string.perf_overlay_lite_netdrops,
                    lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100,
                ),
            )
            sb.append("\t FPS：")
            sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps))
        } else {
            sb.append(context.getString(R.string.perf_overlay_streamdetails, "${initialWidth}x$initialHeight", fps.totalFps))
            sb.append('\n')
            sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n')
            sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n')
            sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n')
            sb.append(
                context.getString(
                    R.string.perf_overlay_netdrops,
                    lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100,
                ),
            ).append('\n')
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()) {
                val netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid()) +
                    TrafficStatsHelper.getPackageTxBytes(Process.myUid())
                if (lastNetDataNum != 0L) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth) + ": ")
                    val realtimeNetData = (netData - lastNetDataNum) / 1024f
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format("%.2f", realtimeNetData / 1024f) + "M/s\n")
                    } else {
                        sb.append(String.format("%.2f", realtimeNetData) + "K/s\n")
                    }
                }
                lastNetDataNum = netData
            }
            sb.append(context.getString(R.string.perf_overlay_netlatency, (rttInfo shr 32).toInt(), rttInfo.toInt()))
                .append('\n')
            if (lastTwo.framesWithHostProcessingLatency > 0) {
                sb.append(
                    context.getString(
                        R.string.perf_overlay_hostprocessinglatency,
                        lastTwo.minHostProcessingLatency.code.toFloat() / 10,
                        lastTwo.maxHostProcessingLatency.code.toFloat() / 10,
                        lastTwo.totalHostProcessingLatency.toFloat() / 10 / lastTwo.framesWithHostProcessingLatency,
                    ),
                ).append('\n')
            }
            sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs))
        }
        return sb.toString()
    }

    fun notifyVideoBackground() {
        foreground = false
    }

    val activeVideoFormat: Int
        get() = videoFormat

    private fun createBaseMediaFormat(mimeType: String): MediaFormat {
        val videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)
        }

        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth)
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_RANGE,
                if (getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL) {
                    MediaFormat.COLOR_RANGE_FULL
                } else {
                    MediaFormat.COLOR_RANGE_LIMITED
                },
            )

            if ((activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
                videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                when (getPreferredColorSpace()) {
                    MoonBridge.COLORSPACE_REC_601 ->
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                    MoonBridge.COLORSPACE_REC_709 ->
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    MoonBridge.COLORSPACE_REC_2020 ->
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                }
            }
        }

        return videoFormat
    }

    private fun configureAndStartDecoder(format: MediaFormat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hdrMetadataBytes = currentHdrMetadata
            if (hdrMetadataBytes != null) {
                val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                val hdrMetadata = ByteBuffer.wrap(hdrMetadataBytes).order(ByteOrder.LITTLE_ENDIAN)

                hdrStaticInfo.put(0.toByte())
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)
                hdrStaticInfo.putShort(hdrMetadata.short)

                hdrStaticInfo.rewind()
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
            }
        }

        LimeLog.info("Configuring with format: $format")

        videoDecoder!!.configure(format, renderTarget, null, 0)

        try {
            applySurfaceFrameRate(renderTarget, targetFps)
        } catch (_: Throwable) {
        }

        try {
            val info = if (Build.VERSION.SDK_INT >= 21) videoDecoder!!.codecInfo else null
            val name = info?.name ?: "<unknown>"
            LimeLog.info("Decoder name: $name")
        } catch (_: Throwable) {
            LimeLog.info("Decoder name: <unavailable>")
        }

        configuredFormat = format

        submittedCsd = false
        vpsBuffers.clear()
        spsBuffers.clear()
        ppsBuffers.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            inputFormat = videoDecoder!!.inputFormat
            LimeLog.info("Input format: $inputFormat")
        }

        videoDecoder!!.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        videoDecoder!!.start()

        try {
            val inF = videoDecoder!!.inputFormat
            val outF = videoDecoder!!.outputFormat
            LimeLog.info("Decoder input format: " + inF.toString())
            LimeLog.info("Decoder output format: " + outF.toString())
        } catch (_: Throwable) {
            LimeLog.info("Decoder formats unavailable after start")
        }
    }

    private fun tryConfigureDecoder(
        selectedDecoderInfo: MediaCodecInfo,
        format: MediaFormat,
        throwOnCodecError: Boolean,
    ): Boolean {
        var configured = false
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.name)
            configureAndStartDecoder(format)
            LimeLog.info("Using codec " + selectedDecoderInfo.name + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME))
            configured = true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            if (throwOnCodecError) {
                throw e
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            if (throwOnCodecError) {
                throw e
            }
        } catch (e: IOException) {
            e.printStackTrace()
            if (throwOnCodecError) {
                throw RuntimeException(e)
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder!!.release()
                videoDecoder = null
            }
        }
        return configured
    }

    fun initializeDecoder(throwOnCodecError: Boolean): Int {
        val mimeType: String
        val selectedDecoderInfo: MediaCodecInfo

        if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc"
            selectedDecoderInfo = avcDecoder ?: run {
                LimeLog.severe("No available AVC decoder!")
                return -1
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC")
                return -1
            }

            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.name)
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.name)
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.name)
            isExynos4 = MediaCodecHelper.isExynos4Device()
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder " + selectedDecoderInfo.name + " needs SPS bitstream restrictions fixup")
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder " + selectedDecoderInfo.name + " needs baseline SPS hack")
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder " + selectedDecoderInfo.name + " needs constrained high profile")
            }
            if (isExynos4) {
                LimeLog.info("Decoder " + selectedDecoderInfo.name + " is on Exynos 4")
            }

            refFrameInvalidationActive = refFrameInvalidationAvc
        } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc"
            selectedDecoderInfo = hevcDecoder ?: run {
                LimeLog.severe("No available HEVC decoder!")
                return -2
            }

            refFrameInvalidationActive = refFrameInvalidationHevc
        } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01"
            selectedDecoderInfo = av1Decoder ?: run {
                LimeLog.severe("No available AV1 decoder!")
                return -2
            }

            refFrameInvalidationActive = refFrameInvalidationAv1
        } else {
            LimeLog.severe("Unknown format")
            return -3
        }
        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType)
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType)
        activeDecoderNameValue = selectedDecoderInfo.name

        var tryNumber = 0
        while (true) {
            LimeLog.info("Decoder configuration try: $tryNumber")

            val mediaFormat = createBaseMediaFormat(mimeType)
            val newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(
                mediaFormat,
                selectedDecoderInfo,
                prefs.enableUltraLowLatency,
                tryNumber,
            )
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                break
            }

            if (!newFormat) {
                return -5
            }
            tryNumber++
        }

        if (USE_FRAME_RENDER_TIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder!!.setOnFrameRenderedListener(
                { _, presentationTimeUs, renderTimeNanos ->
                    val delta = renderTimeNanos / 1000000L - presentationTimeUs / 1000
                    if (delta in 0..999 && USE_FRAME_RENDER_TIME) {
                        activeWindowVideoStats.totalTimeMs += delta
                    }
                },
                null,
            )
        }

        return 0
    }

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        resetRollingPerfStatsForNewStream("stream setup")
        targetFps = if (redrawRate > 0) redrawRate else 60
        initialWidth = if (invertResolution) height else width
        initialHeight = if (invertResolution) width else height
        videoFormat = format
        refreshRate = redrawRate
        refreshDisplayParameters()
        LimeLog.info("Nova: frame pacing mode=" + prefs.framePacing + " preferLowerDelays=" + preferLowerDelays)

        return initializeDecoder(false)
    }

    private fun doCodecRecoveryIfRequired(quiescenceFlag: Int): Boolean {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            return false
        }

        synchronized(codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or CR_FLAG_CHOREOGRAPHER
            }

            codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag

            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                nextInputBuffer = null
                nextInputBufferIndex = -1
                outputBufferQueue.clear()

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder")
                    try {
                        videoDecoder!!.flush()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                    }
                }

                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++
                    LimeLog.info("Codec recovery attempt: $codecRecoveryAttempts")
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException")
                    try {
                        videoDecoder!!.stop()
                        configureAndStartDecoder(configuredFormat!!)
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ) {
                    LimeLog.warning("Trying to reset decoder after CodecException")
                    try {
                        videoDecoder!!.reset()
                        configureAndStartDecoder(configuredFormat!!)
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException")
                    videoDecoder!!.release()

                    try {
                        val err = initializeDecoder(true)
                        if (err != 0) {
                            throw IllegalStateException("Decoder reset failed: $err")
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        if (!reportedCrash) {
                            reportedCrash = true
                            crashListener.notifyCrash(e)
                        }
                        throw RendererException(this, e)
                    }
                }

                codecRecoveryThreadQuiescedFlags = 0
                codecRecoveryMonitor.notifyAll()
            } else {
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: $codecRecoveryThreadQuiescedFlags")
                        codecRecoveryMonitor.wait(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        return true
    }

    private fun handleDecoderException(e: IllegalStateException): Boolean {
        if (stopping) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && e is CodecException) {
            if (e.isTransient) {
                LimeLog.warning(e.diagnosticInfo)
                return true
            }

            LimeLog.severe(e.diagnosticInfo)

            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (e.isRecoverable) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (
                        codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET &&
                        codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART
                    ) {
                        throw IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get())
                    }
                } else {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get())
                    }
                }

                return false
            }
        } else {
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get())
                }

                return false
            }
        }

        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            if (initialException != null) {
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    if (!reportedCrash) {
                        reportedCrash = true
                        crashListener.notifyCrash(initialException!!)
                    }
                    throw initialException!!
                }
            } else {
                initialException = RendererException(this, e)
                initialExceptionTimestamp = SystemClock.uptimeMillis()
            }
        }

        return false
    }

    override fun doFrame(frameTimeNanos: Long) {
        var frameTime = frameTimeNanos
        if (stopping) {
            return
        }

        frameTime -= cachedAppVsyncOffsetNanos

        val actualFrameTimeDeltaNs = frameTime - lastRenderedFrameTimeNanos
        val expectedFrameTimeDeltaNs = 800000000L / refreshRate
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            val nextOutputBuffer = outputBufferQueue.poll()
            if (nextOutputBuffer != null) {
                try {
                    presentFrame(nextOutputBuffer, frameTime)
                    lastRenderedFrameTimeNanos = frameTime
                    activeWindowVideoStats.totalFramesRendered++
                } catch (_: IllegalStateException) {
                    try {
                        dropFrame(nextOutputBuffer)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        handleDecoderException(e)
                    }
                }
            }
        }

        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            return
        }

        choreographerHandlerThread = HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        choreographerHandlerThread!!.start()

        choreographerHandler = Handler(choreographerHandlerThread!!.looper)
        choreographerHandler!!.post {
            Choreographer.getInstance().postFrameCallback(this@MediaCodecDecoderRenderer)
        }
    }

    private fun startRendererThread() {
        rendererThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

            var displayHz = 60f
            try {
                if (Build.VERSION.SDK_INT >= 17) {
                    val d = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                    displayHz = d.refreshRate
                }
            } catch (_: Throwable) {
            }
            if (displayHz <= 0f) displayHz = 60f
            val vsyncPeriodNs = (1_000_000_000L / displayHz).toLong()

            val tfps = if (targetFps > 0) targetFps else 60
            val streamPeriodNs = 1_000_000_000L / max(1, tfps)
            val periodNs = FramePacingPolicy.renderPeriodNs(preferLowerDelays, vsyncPeriodNs, streamPeriodNs)

            val ewmaAlpha = 0.25

            var lastDecoderPtsUs = 0L
            var lastPresentNs = 0L
            var lastDropNs = 0L
            var lateStreak = 0
            var tryAgainStreak = 0
            var recentDrops = 0

            var ewmaInterArrivalNs = 1_000_000_000.0 / max(1, tfps)
            var ewmaDecodeToPresentNs = periodNs * 0.7
            val ewmaJitterNs = periodNs * 0.1

            val info = BufferInfo()
            val tmpInfo = BufferInfo()
            var lastOutputNs = System.nanoTime()
            while (!stopping) {
                if (preferLowerDelays && prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                    try {
                        var idx = videoDecoder!!.dequeueOutputBuffer(tmpInfo, 0)
                        var last = -1
                        var lastPtsUs = -1L

                        while (idx >= 0) {
                            if (last >= 0) {
                                try {
                                    dropFrame(last, intentional = true)
                                } catch (_: Throwable) {
                                }
                            }
                            last = idx
                            lastPtsUs = tmpInfo.presentationTimeUs
                            idx = videoDecoder!!.dequeueOutputBuffer(tmpInfo, 0)
                        }

                        if (last >= 0) {
                            val nowNs = System.nanoTime()
                            lastOutputNs = nowNs
                            presentFrame(last, nowNs)

                            if (lastPtsUs >= 0) {
                                val d2pNs = nowNs - lastPtsUs * 1000L
                                ewmaDecodeToPresentNs += ewmaAlpha * (d2pNs - ewmaDecodeToPresentNs)
                                try {
                                    updateDecodeLatencyStats(lastPtsUs)
                                } catch (_: Throwable) {
                                }
                            }

                            continue
                        }
                    } catch (_: Throwable) {
                    }
                }

                try {
                    var outIndex = videoDecoder!!.dequeueOutputBuffer(info, getOutputDequeueTimeoutUs().toLong())

                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        tryAgainStreak++
                        val backoffUs = if (tryAgainStreak <= 2) 250 else 500
                        outIndex = videoDecoder!!.dequeueOutputBuffer(info, backoffUs.toLong())
                    } else {
                        tryAgainStreak = 0
                    }

                    if (outIndex >= 0) {
                        var statsUpdated = false
                        var frameDropped = false

                        var presentationTimeUs = info.presentationTimeUs
                        var lastIndex = outIndex

                        lastOutputNs = System.nanoTime()
                        numFramesOut++

                        if (lastDecoderPtsUs != 0L) {
                            val interUs = presentationTimeUs - lastDecoderPtsUs
                            if (interUs > 0) {
                                val sample = interUs * 1000.0
                                ewmaInterArrivalNs += ewmaAlpha * (sample - ewmaInterArrivalNs)
                            }
                        }
                        lastDecoderPtsUs = presentationTimeUs

                        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                            while (true) {
                                val nextOut = videoDecoder!!.dequeueOutputBuffer(info, getOutputDequeueTimeoutUs().toLong())
                                if (nextOut < 0) break
                                dropFrame(lastIndex, intentional = true)
                                frameDropped = true

                                numFramesOut++
                                lastIndex = nextOut
                                presentationTimeUs = info.presentationTimeUs
                            }

                            if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS
                            ) {
                                val nowNs = System.nanoTime()
                                val frameAgeNs = nowNs - presentationTimeUs * 1000L

                                val dropDecision = FramePacingPolicy.smoothnessDropDecision(
                                    periodNs = periodNs,
                                    vsyncPeriodNs = vsyncPeriodNs,
                                    ewmaJitterNs = ewmaJitterNs,
                                    recentDrops = recentDrops,
                                    frameAgeNs = frameAgeNs,
                                )

                                if (dropDecision.shouldDrop) {
                                    dropFrame(lastIndex, intentional = true)
                                    frameDropped = true
                                    lastDropNs = nowNs
                                    recentDrops = min(10, recentDrops + 1)
                                    continue
                                }

                                presentFrame(lastIndex, nowNs)
                                lastPresentNs = nowNs
                                recentDrops = max(0, recentDrops - 1)
                                updateDecodeLatencyStats(presentationTimeUs)
                                statsUpdated = true
                            } else {
                                val nowNs = System.nanoTime()
                                val frameAgeNs = nowNs - presentationTimeUs * 1000L

                                val dropDecision = FramePacingPolicy.latencyDropDecision(
                                    periodNs = periodNs,
                                    vsyncPeriodNs = vsyncPeriodNs,
                                    targetFps = tfps,
                                    displayHz = displayHz,
                                    ewmaJitterNs = ewmaJitterNs,
                                    tryAgainStreak = tryAgainStreak,
                                    previousLateStreak = lateStreak,
                                    lastPresentNs = lastPresentNs,
                                    lastDropNs = lastDropNs,
                                    nowNs = nowNs,
                                    frameAgeNs = frameAgeNs,
                                )
                                lateStreak = dropDecision.nextLateStreak

                                if (dropDecision.shouldDrop) {
                                    dropFrame(lastIndex, intentional = true)
                                    frameDropped = true
                                    lastDropNs = nowNs
                                    recentDrops = min(10, recentDrops + 1)
                                    continue
                                }

                                presentFrame(lastIndex, nowNs)
                                lastPresentNs = nowNs
                                if (!dropDecision.isLate) lateStreak = 0
                                recentDrops = max(0, recentDrops - 1)
                                updateDecodeLatencyStats(presentationTimeUs)
                                statsUpdated = true
                            }

                            activeWindowVideoStats.totalFramesRendered++
                        } else {
                            if (outputBufferQueue.size == OUTPUT_BUFFER_QUEUE_LIMIT) {
                                try {
                                    dropFrame(outputBufferQueue.take(), intentional = true)
                                    frameDropped = true
                                } catch (_: InterruptedException) {
                                    return@Thread
                                }
                            }

                            outputBufferQueue.add(lastIndex)
                        }

                        if (!statsUpdated && !frameDropped) {
                            updateDecodeLatencyStats(presentationTimeUs)
                        }
                    } else {
                        when (outIndex) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                activeWindowVideoStats.decoderStarvationEvents++
                            }
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                activeWindowVideoStats.outputFormatChanges++
                                LimeLog.info("Output format changed")
                                outputFormat = videoDecoder!!.outputFormat
                                LimeLog.info("New output format: $outputFormat")
                            }
                        }
                    }
                } catch (e: IllegalStateException) {
                    handleDecoderException(e)
                } finally {
                    doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
                }

                try {
                    val nowNs = System.nanoTime()
                    if (nowNs - lastOutputNs > 1_200_000_000L) {
                        LimeLog.warning("Decoder watchdog: no output >1.2s, scheduling codec flush to recover...")
                        if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_FLUSH)) {
                            activeWindowVideoStats.watchdogFlushes++
                        }
                        try {
                            val poke = Bundle()
                            poke.putInt("priority", 0)
                            videoDecoder!!.setParameters(poke)
                        } catch (_: Throwable) {
                        }
                        lastOutputNs = nowNs
                    }
                } catch (_: Throwable) {
                }
            }
        }
        rendererThread!!.name = "Video - Renderer (MediaCodec)"
        rendererThread!!.priority = Thread.NORM_PRIORITY + 2
        rendererThread!!.start()
    }

    private fun fetchNextInputBuffer(): Boolean {
        val startTime: Long
        val codecRecovered: Boolean

        if (nextInputBuffer != null) {
            return true
        }

        startTime = SystemClock.uptimeMillis()

        try {
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = videoDecoder!!.dequeueInputBuffer(10000)
            }

            if (nextInputBufferIndex >= 0) {
                nextInputBuffer = videoDecoder!!.getInputBuffer(nextInputBufferIndex)
                if (nextInputBuffer == null) {
                    nextInputBufferIndex = -1
                }
            }
        } catch (e: IllegalStateException) {
            handleDecoderException(e)
            return false
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        }

        if (codecRecovered) {
            return false
        }

        val deltaMs = (SystemClock.uptimeMillis() - startTime).toInt()

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: $deltaMs ms")
        }

        if (nextInputBuffer == null) {
            if (deltaMs >= 5000 && initialException == null) {
                val decoderHungException = DecoderHungException(deltaMs)
                if (!reportedCrash) {
                    reportedCrash = true
                    crashListener.notifyCrash(decoderHungException)
                }
                throw RendererException(this, decoderHungException)
            }

            return false
        }

        return true
    }

    override fun start() {
        startRendererThread()
        startChoreographerThread()
    }

    fun prepareForStop() {
        stopping = true

        if (!stopPrepared.compareAndSet(false, true)) {
            return
        }

        rendererThread?.interrupt()

        synchronized(codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
            codecRecoveryMonitor.notifyAll()
        }

        val handler = choreographerHandler
        val handlerThread = choreographerHandlerThread
        if (handler != null && handlerThread != null && handlerThread.isAlive) {
            val posted = handler.post {
                Choreographer.getInstance().removeFrameCallback(this@MediaCodecDecoderRenderer)
                handlerThread.quit()
            }
            if (!posted) {
                handlerThread.quit()
            }
        }
    }

    override fun stop() {
        prepareForStop()

        val handlerThread = choreographerHandlerThread
        if (handlerThread != null) {
            try {
                handlerThread.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            choreographerHandlerThread = null
            choreographerHandler = null
        }

        val renderer = rendererThread
        if (renderer != null) {
            try {
                renderer.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            rendererThread = null
        }
    }

    override fun cleanup() {
        videoDecoder!!.release()
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null
            } else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata
            } else {
                return
            }

            codecRecoveryAttempts = 0

            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
            }
        }
    }

    private fun queueNextInputBuffer(timestampUs: Long, codecFlags: Int): Boolean {
        val codecRecovered: Boolean

        try {
            videoDecoder!!.queueInputBuffer(
                nextInputBufferIndex,
                0,
                nextInputBuffer!!.position(),
                timestampUs,
                codecFlags,
            )

            try {
                enqueueNsByPtsUs.put(timestampUs, System.nanoTime())
            } catch (_: Throwable) {
            }

            nextInputBufferIndex = -1
            nextInputBuffer = null
        } catch (e: IllegalStateException) {
            if (handleDecoderException(e)) {
                nextInputBuffer!!.clear()
            } else {
                nextInputBufferIndex = -1
                nextInputBuffer = null
            }
            return false
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        }

        if (codecRecovered) {
            return false
        }

        return fetchNextInputBuffer()
    }

    private fun doProfileSpecificSpsPatching(sps: SeqParameterSet) {
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile")
            sps.constraintSet4Flag = true
            sps.constraintSet5Flag = true
        } else {
            sps.constraintSet4Flag = false
            sps.constraintSet5Flag = false
        }
    }

    @Suppress("DEPRECATION")
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
        if (stopping) {
            return MoonBridge.DR_OK
        }
        val decodeData = decodeUnitData!!
        if (frameNumber < lastFrameNumber) {
            resetRollingPerfStatsForNewStream("frame sequence restart")
        }

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.frameLossEvents++
        }

        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
        }

        lastFrameNumber = frameNumber

        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            val lastTwo = VideoStats()
            lastTwo.add(lastWindowVideoStats)
            lastTwo.add(activeWindowVideoStats)
            val fps = lastTwo.getFps()
            val decoder = if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                avcDecoder!!.name
            } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                hevcDecoder!!.name
            } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                av1Decoder!!.name
            } else {
                "(unknown)"
            }

            val decodeTimeMs = lastTwo.decoderTimeMs.toFloat() / lastTwo.totalFramesReceived
            val rttInfo = MoonBridge.getEstimatedRttInfo()
            // Overlay text costs resource lookups and TrafficStats kernel round trips on the
            // decode thread; skip all of it unless something is actually going to display or
            // log it. The numeric sample below stays unconditional: it feeds adaptive quality.
            if (perfTextWanted || prefs.enablePerfLogging) {
                val fullLog = buildPerfText(lastTwo, fps, decoder, decodeTimeMs, rttInfo)
                perfListener.onPerfUpdate(fullLog)
                val targetFpsMatched = fps.totalFps.toInt() == prefs.fps.toInt()
                if (minDecodeTime > decodeTimeMs && targetFpsMatched) {
                    minDecodeTime = decodeTimeMs
                    minDecodeTimeFullLog = fullLog
                }
            }
            val packetLossPct = if (lastTwo.totalFrames > 0) {
                lastTwo.framesLost.toDouble() / lastTwo.totalFrames.toDouble() * 100.0
            } else {
                0.0
            }
            val perfSample = PerfOverlaySample(
                fps = fps.totalFps.toDouble(),
                incomingFps = fps.receivedFps.toDouble(),
                renderedFps = fps.renderedFps.toDouble(),
                width = initialWidth,
                height = initialHeight,
                codec = decoder,
                rttMs = (rttInfo shr 32).toInt(),
                rttVarianceMs = rttInfo.toInt(),
                decodeTimeMs = decodeTimeMs.toDouble(),
                packetLossPct = packetLossPct
            )
            perfListener.onPerfSample(perfSample)

            globalVideoStats.add(activeWindowVideoStats)
            lastWindowVideoStats.copy(activeWindowVideoStats)
            activeWindowVideoStats.clear()
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        }

        var csdSubmittedForThisFrame = false

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS &&
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0
            ) {
                numSpsIn++

                val spsBuf = ByteBuffer.wrap(decodeData)
                val startSeqLen = if (decodeData[2] == 0x01.toByte()) 3 else 4

                spsBuf.position(startSeqLen + 1)

                val sps = H264Utils.readSPS(spsBuf)

                if (!refFrameInvalidationActive) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 31")
                        sps.levelIdc = 31
                    } else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 32")
                        sps.levelIdc = 32
                    } else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
                        LimeLog.info("Patching level_idc to 42")
                        sps.levelIdc = 42
                    }
                }

                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS")
                    sps.numRefFrames = 1
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                    sps.vuiParams != null &&
                    hevcDecoder == null &&
                    av1Decoder == null
                ) {
                    sps.vuiParams.videoSignalTypePresentFlag = false
                    sps.vuiParams.colourDescriptionPresentFlag = false
                    sps.vuiParams.chromaLocInfoPresentFlag = false
                }

                if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (sps.vuiParams == null) {
                        LimeLog.info("Adding VUI parameters")
                        sps.vuiParams = VUIParameters()
                    }

                    if (sps.vuiParams.bitstreamRestriction == null) {
                        LimeLog.info("Adding bitstream restrictions")
                        sps.vuiParams.bitstreamRestriction = VUIParameters.BitstreamRestriction()
                        sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16
                        sps.vuiParams.bitstreamRestriction.numReorderFrames = 0
                    } else {
                        LimeLog.info("Patching bitstream restrictions")
                    }

                    sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1
                    }
                } else if (sps.vuiParams != null) {
                    sps.vuiParams.bitstreamRestriction = null
                }

                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline")
                    sps.profileIdc = 66
                    savedSps = sps
                }

                doProfileSpecificSpsPatching(sps)

                val escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength)

                val naluBuffer = ByteArray(startSeqLen + 1 + escapedNalu.limit())
                System.arraycopy(decodeData, 0, naluBuffer, 0, startSeqLen + 1)
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit())

                spsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++

                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeData, 0, naluBuffer, 0, decodeUnitLength)
                vpsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++

                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeData, 0, naluBuffer, 0, decodeUnitLength)
                spsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++

                val naluBuffer = ByteArray(decodeUnitLength)
                System.arraycopy(decodeData, 0, naluBuffer, 0, decodeUnitLength)
                ppsBuffers.add(naluBuffer)
                return MoonBridge.DR_OK
            } else if ((videoFormat and (MoonBridge.VIDEO_FORMAT_MASK_H264 or MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR
                    }

                    for (vpsBuffer in vpsBuffers) {
                        nextInputBuffer!!.put(vpsBuffer)
                    }
                    for (spsBuffer in spsBuffers) {
                        nextInputBuffer!!.put(spsBuffer)
                    }
                    for (ppsBuffer in ppsBuffers) {
                        nextInputBuffer!!.put(ppsBuffer)
                    }

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR
                    }

                    csdSubmittedForThisFrame = true
                    submittedCsd = true

                    if (needsBaselineSpsHack) {
                        needsBaselineSpsHack = false

                        if (!replaySps()) {
                            return MoonBridge.DR_NEED_IDR
                        }

                        LimeLog.info("SPS replay complete")
                    }
                }
            }
        }

        if (frameHostProcessingLatency.code != 0) {
            activeWindowVideoStats.minHostProcessingLatency = if (activeWindowVideoStats.minHostProcessingLatency.code != 0) {
                minOf(activeWindowVideoStats.minHostProcessingLatency, frameHostProcessingLatency)
            } else {
                frameHostProcessingLatency
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1
        }
        activeWindowVideoStats.maxHostProcessingLatency =
            maxOf(activeWindowVideoStats.maxHostProcessingLatency, frameHostProcessingLatency)
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency.code

        activeWindowVideoStats.totalFramesReceived++
        activeWindowVideoStats.totalFrames++

        if (!FRAME_RENDER_TIME_ONLY) {
            activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR
        }

        var codecFlags = 0

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_SYNC_FRAME

            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (vpsBuffer in vpsBuffers) {
                    nextInputBuffer!!.put(vpsBuffer)
                }
                for (spsBuffer in spsBuffers) {
                    nextInputBuffer!!.put(spsBuffer)
                }
                for (ppsBuffer in ppsBuffers) {
                    nextInputBuffer!!.put(ppsBuffer)
                }
            }
        }

        var timestampUs = enqueueTimeMs * 1000
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + 1
        }
        lastTimestampUs = timestampUs

        numFramesIn++

        if (decodeUnitLength > nextInputBuffer!!.limit() - nextInputBuffer!!.position()) {
            val exception = IllegalArgumentException(
                "Decode unit length " + decodeUnitLength + " too large for input buffer " + nextInputBuffer!!.limit(),
            )
            if (!reportedCrash) {
                reportedCrash = true
                crashListener.notifyCrash(exception)
            }
            throw RendererException(this, exception)
        }

        nextInputBuffer!!.put(decodeData, 0, decodeUnitLength)

        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return MoonBridge.DR_NEED_IDR
        }

        return MoonBridge.DR_OK
    }

    private fun replaySps(): Boolean {
        if (!fetchNextInputBuffer()) {
            return false
        }

        nextInputBuffer!!.put(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67))

        savedSps!!.profileIdc = 100

        doProfileSpecificSpsPatching(savedSps!!)

        val escapedNalu = H264Utils.writeSPS(savedSps, 128)
        nextInputBuffer!!.put(escapedNalu)

        savedSps = null

        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    override fun getCapabilities(): Int {
        var capabilities = 0

        capabilities = capabilities or MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame)

        if (refFrameInvalidationAvc) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
        }
        if (refFrameInvalidationHevc) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        }
        if (refFrameInvalidationAv1) {
            capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
        }

        if (directSubmit) {
            capabilities = capabilities or MoonBridge.CAPABILITY_DIRECT_SUBMIT
        }

        return capabilities
    }

    fun getAverageEndToEndLatency(): Int {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0
        }
        return (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived).toInt()
    }

    fun getAverageDecoderLatency(): Int {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0
        }
        return (globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived).toInt()
    }

    fun performanceWasTracked(): Boolean? {
        return minDecodeTime < Float.MAX_VALUE
    }

    @SuppressLint("DefaultLocale")
    fun getMinDecoderLatency(): String {
        return String.format("%1$.2f", minDecodeTime)
    }

    fun getMinDecoderLatencyFullLog(): String {
        return minDecodeTimeFullLog
    }

    private fun getCrashDiagnosticVideoStats(): VideoStats {
        val stats = VideoStats()
        stats.add(globalVideoStats)
        stats.add(activeWindowVideoStats)
        return stats
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun appendDecoderCapabilities(
        append: (String) -> Unit,
        label: String,
        mimeType: String,
        decoder: MediaCodecInfo?,
    ) {
        if (decoder == null) {
            return
        }

        val videoCapabilities = decoder.getCapabilitiesForType(mimeType).videoCapabilities
        if (videoCapabilities == null) {
            append("$label capabilities: UNAVAILABLE" + RendererException.DELIMITER)
            return
        }

        append("$label supported width range: ${videoCapabilities.supportedWidths}" + RendererException.DELIMITER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            append(
                try {
                    val fpsRange = videoCapabilities.getAchievableFrameRatesFor(initialWidth, initialHeight)
                    "$label achievable FPS range: $fpsRange" + RendererException.DELIMITER
                } catch (_: IllegalArgumentException) {
                    "$label achievable FPS range: UNSUPPORTED!" + RendererException.DELIMITER
                },
            )
        }
    }

    class DecoderHungException(private val hangTimeMs: Int) : RuntimeException() {
        override fun toString(): String {
            var str = ""

            str += "Hang time: " + hangTimeMs + " ms" + RendererException.DELIMITER
            str += super.toString()

            return str
        }
    }

    class RendererException(renderer: MediaCodecDecoderRenderer, e: Exception) : RuntimeException() {
        private val text: String = generateText(renderer, e)

        override fun toString(): String {
            return text
        }

        private fun generateText(renderer: MediaCodecDecoderRenderer, originalException: Exception): String {
            var str: String

            str = if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
                "PreSPSError"
            } else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
                "PrePPSError"
            } else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
                "PreIFrameError"
            } else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
                "PreOutputConfigError"
            } else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
                "PreOutputError"
            } else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                "EarlyOutputError"
            } else {
                "ErrorWhileStreaming"
            }

            val videoStats = renderer.getCrashDiagnosticVideoStats()

            str += "Format: " + String.format("%x", renderer.videoFormat) + DELIMITER
            str += "AVC Decoder: " + (renderer.avcDecoder?.name ?: "(none)") + DELIMITER
            str += "HEVC Decoder: " + (renderer.hevcDecoder?.name ?: "(none)") + DELIMITER
            str += "AV1 Decoder: " + (renderer.av1Decoder?.name ?: "(none)") + DELIMITER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                renderer.appendDecoderCapabilities(append = { str += it }, "AVC", "video/avc", renderer.avcDecoder)
                renderer.appendDecoderCapabilities(append = { str += it }, "HEVC", "video/hevc", renderer.hevcDecoder)
                renderer.appendDecoderCapabilities(append = { str += it }, "AV1", "video/av01", renderer.av1Decoder)
            }
            str += "Configured format: " + renderer.configuredFormat + DELIMITER
            str += "Input format: " + renderer.inputFormat + DELIMITER
            str += "Output format: " + renderer.outputFormat + DELIMITER
            str += "Adaptive playback: " + renderer.adaptivePlayback + DELIMITER
            str += "GL Renderer: " + renderer.glRenderer + DELIMITER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                str += "SOC: " + Build.SOC_MANUFACTURER + " - " + Build.SOC_MODEL + DELIMITER
                str += "Performance class: " + Build.VERSION.MEDIA_PERFORMANCE_CLASS + DELIMITER
            }
            str += "Consecutive crashes: " + renderer.consecutiveCrashCount + DELIMITER
            str += "RFI active: " + renderer.refFrameInvalidationActive + DELIMITER
            str += "Using modern SPS patching: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) + DELIMITER
            str += "Fused IDR frames: " + renderer.fusedIdrFrame + DELIMITER
            str += "Video dimensions: " + renderer.initialWidth + "x" + renderer.initialHeight + DELIMITER
            str += "FPS target: " + renderer.refreshRate + DELIMITER
            str += "Bitrate: " + renderer.prefs.bitrate + " Kbps" + DELIMITER
            str += "CSD stats: " + renderer.numVpsIn + ", " + renderer.numSpsIn + ", " + renderer.numPpsIn + DELIMITER
            str += "Frames in-out: " + renderer.numFramesIn + ", " + renderer.numFramesOut + DELIMITER
            str += "Total frames received: " + videoStats.totalFramesReceived + DELIMITER
            str += "Total frames rendered: " + videoStats.totalFramesRendered + DELIMITER
            str += "Frame losses: " + videoStats.framesLost + " in " +
                videoStats.frameLossEvents + " loss events" + DELIMITER
            str += "Decoder pacing counters: starvation=" + videoStats.decoderStarvationEvents +
                ", intentionalDrops=" + videoStats.intentionalFrameDrops +
                ", watchdogFlushes=" + videoStats.watchdogFlushes +
                ", formatChanges=" + videoStats.outputFormatChanges + DELIMITER
            str += "Average end-to-end client latency: " + renderer.getAverageEndToEndLatency() + "ms" + DELIMITER
            str += "Average hardware decoder latency: " + renderer.getAverageDecoderLatency() + "ms" + DELIMITER
            str += "Frame pacing mode: " + renderer.prefs.framePacing + DELIMITER

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (originalException is CodecException) {
                    str += "Diagnostic Info: " + originalException.diagnosticInfo + DELIMITER
                    str += "Recoverable: " + originalException.isRecoverable + DELIMITER
                    str += "Transient: " + originalException.isTransient + DELIMITER

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        str += "Codec Error Code: " + originalException.errorCode + DELIMITER
                    }
                }
            }

            str += originalException.toString()

            return str
        }

        companion object {
            private const val serialVersionUID = 8985937536997012406L
            val DELIMITER: String = if (BuildConfig.DEBUG) "\n" else " | "
        }
    }

    private fun applySurfaceFrameRate(surface: Surface?, targetFps: Int) {
        if (surface == null) return
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val surfaceFrameRate = chooseSurfaceFrameRateHint(targetFps)
                surface.setFrameRate(surfaceFrameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                LimeLog.info("Applied Surface frame rate: $surfaceFrameRate Hz for $targetFps FPS stream")
            }
        } catch (_: Throwable) {
        }
    }

    private fun chooseSurfaceFrameRateHint(targetFps: Int): Float {
        if (targetFps <= 0) return 60f

        var displayHz = 60f
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            val display = windowManager?.defaultDisplay
            if (display != null && display.refreshRate > 0f) {
                displayHz = display.refreshRate
            }
        } catch (_: Throwable) {
        }

        return if (displayHz > targetFps + 0.5f && isWholeRefreshMultiple(displayHz, targetFps)) {
            displayHz
        } else {
            min(targetFps.toFloat(), displayHz)
        }
    }

    private fun isWholeRefreshMultiple(displayHz: Float, targetFps: Int): Boolean {
        if (displayHz <= 0f || targetFps <= 0 || displayHz < targetFps) {
            return false
        }

        val ratio = displayHz / targetFps.toDouble()
        val nearestWhole = Math.rint(ratio)
        return nearestWhole >= 1.0 && abs(ratio - nearestWhole) <= 0.05
    }

    private fun isMTKDecoderName(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase(Locale.getDefault())
        return n.startsWith("c2.mtk") || n.startsWith("omx.mtk")
    }

    companion object {
        private const val USE_FRAME_RENDER_TIME = false
        private const val FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false

        private const val CR_MAX_TRIES = 10
        private const val CR_RECOVERY_TYPE_NONE = 0
        private const val CR_RECOVERY_TYPE_FLUSH = 1
        private const val CR_RECOVERY_TYPE_RESTART = 2
        private const val CR_RECOVERY_TYPE_RESET = 3

        private const val CR_FLAG_INPUT_THREAD = 0x1
        private const val CR_FLAG_RENDER_THREAD = 0x2
        private const val CR_FLAG_CHOREOGRAPHER = 0x4
        private const val CR_FLAG_ALL = CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD or CR_FLAG_CHOREOGRAPHER

        private const val EXCEPTION_REPORT_DELAY_MS = 3000

        private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2

        private const val ENQUEUE_MAP_MAX_ENTRIES = 256
        private const val T3_T4_LOG_SAMPLE_INTERVAL = 120
    }
}
