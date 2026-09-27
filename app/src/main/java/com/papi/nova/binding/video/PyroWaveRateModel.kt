package com.papi.nova.binding.video

import kotlin.math.sqrt

/**
 * The bitrate PyroWave's author measured the codec needing, for a given picture, viewing distance,
 * chroma and quality.
 *
 * A port of `pyrowave_psnr_hvs_m_h_estimate_mbits` from upstream's generated
 * eval-results/pyrowave_regression_results.h, whose coefficients [PyroWaveRateTable] carries. Upstream
 * ran four lossless game clips through the codec at every 16:9 size from 1280x720 to 3840x2160, scored
 * each result with PSNR-HVS-M-H (PSNR-HVS-M with a contrast sensitivity curve for the viewing distance),
 * and fitted one polynomial per quality, distance and chroma to the bitrate each quality needed. The
 * author reports the fit within about 1%, and calls 35 dB the default good quality curve.
 *
 * It answers a different question from [PyroWaveDecoderRenderer.recommendedKbps], which is one bits per
 * pixel figure judged by eye. A wavelet codec gives up fine detail first, so what it needs grows much
 * more slowly than the pixel count: at 35 dB and H 2.0 this asks for nearly 2.5 bits per pixel at 720p and
 * under 0.6 at 4K, where the flat figure asks for 0.73 at both.
 *
 * Nothing calls this yet, on purpose. Which quality to aim for (35 dB or 33 dB) and which viewing
 * distance to assume for a handheld's own screen or for a television are the owner's calls, and until
 * they are made the advice stays what it is.
 *
 * Its limits travel with every number it gives. It is upstream's objective metric on four game clips of
 * about ten frames each; it scores luma only, so 4:4:4 shows up as a cost and never as a benefit; it was
 * sampled at 16:9 only; and its sources were downscaled from 4K, which makes small pictures richer in
 * detail, and so harder to code, than a game rendered natively at that size. It is not a measurement on
 * Nova's devices.
 *
 * Pure Kotlin with no Android API, so it answers the same on a phone, a Deck and a test JVM.
 */
object PyroWaveRateModel {
    /** The lowest quality upstream has a curve for, in dB of PSNR-HVS-M-H. */
    const val MIN_PSNR_DB = PyroWaveRateTable.MIN_PSNR

    /** The highest quality upstream has a curve for, in dB of PSNR-HVS-M-H. */
    const val MAX_PSNR_DB = PyroWaveRateTable.MAX_PSNR

    /** The smallest picture the model was fitted on, 1280x720, in pixels. */
    const val MIN_PIXELS = PyroWaveRateTable.MIN_PIXELS

    /** The largest picture the model was fitted on, 3840x2160, in pixels. */
    const val MAX_PIXELS = PyroWaveRateTable.MAX_PIXELS

    /** How many viewing distances the table has, indexed from 0 to one less than this. */
    const val HEIGHT_FACTORS = PyroWaveRateTable.HEIGHT_FACTORS

    /** H 2.0: a monitor at a normal distance, and the distance upstream's subjective tests used. */
    const val HEIGHT_FACTOR_2_00 = 8

    /** H 2.875, the farthest the model reaches. A handheld at arm's length sits farther still. */
    const val HEIGHT_FACTOR_2_87 = 15

    /**
     * Viewing distance divided by picture height for a height factor index, 1.0 + index / 8, which is
     * how upstream's sweep spaced them. The index is what [estimate] takes, as upstream's enum is.
     */
    fun heightFactor(index: Int): Double = 1.0 + index / 8.0

    /** Something about a question that the model was not fitted to answer. */
    enum class Flag(
        /**
         * True when there is no estimate to give. Upstream asserts on some of these and has no answer
         * for one; the rest it would answer, but with a number that means nothing. Each flag says which.
         */
        val outsideTable: Boolean,
    ) {
        /**
         * Width or height is zero or negative. Upstream multiplies the two before its pixel assert, so
         * it asserts on these unless both sides are negative, which it treats as if both were positive.
         * Refused here, because no picture has that size.
         */
        SIZE_NOT_POSITIVE(true),

        /** Fewer pixels than 1280x720. Upstream asserts on it. */
        PIXELS_BELOW_MODEL(true),

        /** More pixels than 3840x2160. Upstream asserts on it. */
        PIXELS_ABOVE_MODEL(true),

        /** A quality outside 30 to 50 dB. Upstream asserts on it. */
        PSNR_OUTSIDE_TABLE(true),

        /**
         * A height factor index outside 0 to 15, which upstream's enum cannot name. Upstream does not
         * assert on it: it finds no curve for it and returns 0.0, which is no answer.
         */
        HEIGHT_FACTOR_OUTSIDE_TABLE(true),

        /**
         * A frame rate that is not a positive finite number, NaN and infinity included. Upstream never
         * checks it and only multiplies by it, so 0 comes back as 0.0 and -60 as a negative bitrate.
         * Refused here, because no stream runs at such a rate.
         */
        FPS_NOT_POSITIVE(true),

        /**
         * The question has an answer, but the bitrate it multiplies out to is not a positive finite
         * number. A frame rate can be finite and positive and still carry the product past the largest
         * double, as Double.MAX_VALUE fps does, and upstream never checks its answer, so it returns
         * infinity. Refused here, because no stream runs at an infinite bitrate. Zero, a negative number
         * and NaN are refused the same way, should a curve ever give one.
         */
        RESULT_NOT_POSITIVE(true),

        /**
         * Not exactly 16:9, width * 9 == height * 16, as every size upstream sampled was. Upstream still
         * answers for the pixel count, so the estimate is given, as an extrapolation. 1280x800 (the
         * Deck, 16:10), ultrawide, portrait and near misses such as 1366x768 all land here.
         */
        ASPECT_NOT_16_9(false),
    }

    /**
     * An estimate, and every way the question strayed from what the model was fitted on.
     *
     * [mbps] is null when any flag is [Flag.outsideTable]. Upstream asserts on a quality or a pixel count
     * outside its table, and returns 0.0 for a distance its enum cannot name. It never checks the frame
     * rate and treats two negative sides as positive, but a rate or a size that is not positive describes
     * no stream, so those get no estimate either. Nor does it check its own answer, which a finite frame
     * rate can carry to infinity, so an answer that is not a positive finite number gets none. With
     * [Flag.ASPECT_NOT_16_9] alone it is upstream's own answer for that pixel count, and [extrapolated]
     * says so. Any [mbps] given is a positive finite number.
     */
    data class Estimate(
        /** Megabits per second, as upstream computes it: kilobytes per frame, times 8 / 1000, times fps. */
        val mbps: Double?,
        val flags: Set<Flag>,
    ) {
        /** Answered from inside everything the model was fitted on. */
        val withinModel: Boolean get() = mbps != null && flags.isEmpty()

        /** Answered, but for a shape the model never saw. */
        val extrapolated: Boolean get() = mbps != null && Flag.ASPECT_NOT_16_9 in flags
    }

    /**
     * What PyroWave needs for [width] x [height] at [fps] to reach [psnrDb] of PSNR-HVS-M-H when watched
     * from [heightFactor] (an index, see [heightFactor]), in 4:4:4 if [chroma444] and 4:2:0 otherwise.
     *
     * Every flag about the question is collected rather than the first one found, so a caller sees every
     * reason at once. [Flag.RESULT_NOT_POSITIVE] is about the answer, so it can only join
     * [Flag.ASPECT_NOT_16_9]: a question refused for any other reason has no answer to judge.
     */
    fun estimate(
        psnrDb: Int,
        width: Int,
        height: Int,
        heightFactor: Int,
        chroma444: Boolean,
        fps: Double,
    ): Estimate {
        // In Long, so a size too large to multiply in an Int is too large rather than wrapped around
        // into range.
        val pixels = width.toLong() * height.toLong()
        val flags = buildSet {
            if (width <= 0 || height <= 0) {
                add(Flag.SIZE_NOT_POSITIVE)
            } else {
                if (pixels < MIN_PIXELS) add(Flag.PIXELS_BELOW_MODEL)
                if (pixels > MAX_PIXELS) add(Flag.PIXELS_ABOVE_MODEL)
                if (width.toLong() * 9 != height.toLong() * 16) add(Flag.ASPECT_NOT_16_9)
            }
            if (psnrDb !in MIN_PSNR_DB..MAX_PSNR_DB) add(Flag.PSNR_OUTSIDE_TABLE)
            if (heightFactor !in 0 until HEIGHT_FACTORS) add(Flag.HEIGHT_FACTOR_OUTSIDE_TABLE)
            if (!(fps > 0.0) || fps.isInfinite()) add(Flag.FPS_NOT_POSITIVE)
        }
        if (flags.any { it.outsideTable }) return Estimate(null, flags)

        val coefficients = checkNotNull(PyroWaveRateTable.coefficients(psnrDb, heightFactor, chroma444)) {
            "the table has no bucket for $psnrDb dB, height factor $heightFactor, chroma444 $chroma444"
        }
        // Upstream's evaluation, term for term and in the same order, so the same inputs give the same
        // double: a polynomial in sqrt(megapixels) - 2, summed from the constant term up.
        val x = sqrt(pixels.toDouble() * 1e-6) - 2.0
        var power = 1.0
        var kilobytesPerFrame = 0.0
        for (coefficient in coefficients) {
            kilobytesPerFrame += power * coefficient
            power *= x
        }
        val mbps = kilobytesPerFrame * 8e-3 * fps
        // Every input can be in range and the product still not be: a finite frame rate near the top of
        // a double's range carries it to infinity. Judged the way the frame rate is, so NaN, zero and a
        // negative answer are refused too.
        if (!(mbps > 0.0) || mbps.isInfinite()) return Estimate(null, flags + Flag.RESULT_NOT_POSITIVE)
        return Estimate(mbps, flags)
    }
}
