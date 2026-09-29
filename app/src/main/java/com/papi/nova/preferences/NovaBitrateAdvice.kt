package com.papi.nova.preferences

import com.papi.nova.binding.video.PyroWaveRateModel
import kotlin.math.ceil

enum class NovaBitrateBasis { TABLE_V1, HOST_PYROWAVE, PYROWAVE_MODEL, CUSTOM }
data class NovaBitrateRecommendation(val kbps: Int, val basis: NovaBitrateBasis)

/** All public values are requested stream kbps, not encoder kbps. */
object NovaBitrateAdvice {
    fun table(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps in 1..1000)
        val raw = PreferenceConfiguration.getDefaultBitrate("${width}x$height", fps.toString())
        return (((raw + 4999L) / 5000L) * 5000L).toInt().coerceIn(5000, 300000)
    }

    fun encoderForRequest(requestKbps: Int, audioKbps: Int = 512, fecPercent: Int = 10): Int {
        require(requestKbps > 0 && audioKbps >= 0 && fecPercent in 0..255)
        var rate = if (fecPercent <= 80) (requestKbps.toFloat() / (100f / (100 - fecPercent))).toInt() else requestKbps
        rate -= minOf(audioKbps, rate / 5)
        rate -= minOf(500, rate / 10)
        return rate
    }

    /** Exact inverse of Polaris stream_bitrate, including its single-precision FEC rounding. */
    fun requestForEncoder(encoderKbps: Int, audioKbps: Int = 512, fecPercent: Int = 10): Int {
        require(encoderKbps > 0 && audioKbps >= 0 && fecPercent in 0..255)
        fun encoder(wire: Long): Long {
            var rate = if (fecPercent <= 80) (wire.toFloat() / (100f / (100 - fecPercent))).toLong() else wire
            rate -= minOf(audioKbps.toLong(), rate / 5)
            rate -= minOf(500, rate / 10)
            return rate
        }
        var low = encoderKbps.toLong() - 1
        var high = encoderKbps.toLong()
        while (encoder(high) < encoderKbps) high *= 2
        while (high - low > 1) {
            val middle = low + (high - low) / 2
            if (encoder(middle) >= encoderKbps) high = middle else low = middle
        }
        return high.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun recommend(width: Int, height: Int, fps: Int, codec: NovaCodecChoice,
        distance: NovaDistance = NovaDistance.HAND, hostRaiseGoalKbps: Int? = null): NovaBitrateRecommendation {
        if (codec != NovaCodecChoice.PYROWAVE) return NovaBitrateRecommendation(table(width, height, fps), NovaBitrateBasis.TABLE_V1)
        hostRaiseGoalKbps?.takeIf { it in 1..300000 }?.let {
            return NovaBitrateRecommendation(it, NovaBitrateBasis.HOST_PYROWAVE)
        }
        val estimate = PyroWaveRateModel.estimate(psnrDb = 35, width = width, height = height,
            heightFactor = if (distance == NovaDistance.ROOM) PyroWaveRateModel.HEIGHT_FACTOR_2_00 else PyroWaveRateModel.HEIGHT_FACTOR_2_87,
            chroma444 = true, fps = fps.toDouble())
        val encoder = estimate.mbps?.let { ceil(it * 1000).toInt() } ?: 300000
        return NovaBitrateRecommendation(requestForEncoder(encoder).coerceAtMost(300000), NovaBitrateBasis.PYROWAVE_MODEL)
    }

    fun text(kbps: Int, automatic: Boolean): String = (if (automatic) "Auto · " else "") +
        (if (kbps % 1000 == 0) "${kbps / 1000}" else java.lang.String.format(java.util.Locale.ROOT, "%.1f", kbps / 1000.0)) + " Mbps"
}
