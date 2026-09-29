package com.papi.nova.preferences

import com.papi.nova.binding.video.PyroWaveRateModel

enum class NovaBitrateBasis { TABLE_V1, HOST_PYROWAVE, PYROWAVE_MODEL, CUSTOM }
data class NovaBitrateRecommendation(val kbps: Int, val basis: NovaBitrateBasis)

/** Recommendations use requested stream kbps. Encoder helpers explicitly name their units. */
object NovaBitrateAdvice {
    const val MANUAL_MAX_KBPS = 500000
    const val LEGACY_MANUAL_MAX_KBPS = 300000
    /** Hosts must explicitly advertise a larger manual range; malformed metadata is legacy. */
    fun manualMaximum(advertisedKbps: Int?): Int =
        advertisedKbps?.takeIf { it in 1000..MANUAL_MAX_KBPS } ?: LEGACY_MANUAL_MAX_KBPS
    const val AUTOMATIC_MAX_KBPS = 300000
    /** Mirrors Polaris pyrowave_advice::k_far_target_db and k_target_db (#218). */
    const val HANDHELD_PYROWAVE_TARGET_DB = 31
    const val ROOM_PYROWAVE_TARGET_DB = 35

    fun table(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps in 1..1000)
        val raw = PreferenceConfiguration.getDefaultBitrate("${width}x$height", fps.toString())
        return (((raw + 4999L) / 5000L) * 5000L).toInt().coerceIn(5000, AUTOMATIC_MAX_KBPS)
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
        hostRaiseGoalKbps?.takeIf { it > 0 }?.let {
            return NovaBitrateRecommendation(it.coerceAtMost(AUTOMATIC_MAX_KBPS), NovaBitrateBasis.HOST_PYROWAVE)
        }
        val encoder = pyrowaveEncoderKbps(width, height, fps, distance)
        return NovaBitrateRecommendation(requestForEncoder(encoder).coerceAtMost(AUTOMATIC_MAX_KBPS), NovaBitrateBasis.PYROWAVE_MODEL)
    }

    /** Uncapped model result, truncated to encoder kbps exactly as Polaris does before gross-up. */
    fun pyrowaveEncoderKbps(width: Int, height: Int, fps: Int, distance: NovaDistance,
        chroma444: Boolean = true): Int {
        require(width > 0 && height > 0 && fps in 1..1000)
        val target = if (distance == NovaDistance.ROOM) ROOM_PYROWAVE_TARGET_DB else HANDHELD_PYROWAVE_TARGET_DB
        val factor = if (distance == NovaDistance.ROOM) PyroWaveRateModel.HEIGHT_FACTOR_2_00 else PyroWaveRateModel.HEIGHT_FACTOR_2_87
        fun estimate(w: Int, h: Int) = PyroWaveRateModel.estimate(target, w, h, factor, chroma444, fps.toDouble()).mbps
        val pixels = width.toLong() * height
        val mbps = estimate(width, height) ?: if (pixels < PyroWaveRateModel.MIN_PIXELS || pixels > PyroWaveRateModel.MAX_PIXELS) {
            val edge = if (pixels < PyroWaveRateModel.MIN_PIXELS) NovaSize(1280,720) else NovaSize(3840,2160)
            estimate(edge.width, edge.height)?.let { it / edge.pixels * pixels }
        } else null
        // The model's nearest edge keeps the same calibrated target. The flat fallback is only
        // for a question neither the table nor its edges can answer, matching Polaris.
        return ((mbps ?: (0.73 * pixels * fps / 1000000.0)) * 1000).toInt().coerceAtLeast(1)
    }

    fun text(kbps: Int, automatic: Boolean): String = (if (automatic) "Auto · " else "") +
        (if (kbps % 1000 == 0) "${kbps / 1000}" else java.lang.String.format(java.util.Locale.ROOT, "%.1f", kbps / 1000.0)) + " Mbps"
}
