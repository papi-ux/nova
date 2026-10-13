package com.papi.nova.binding.video

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A CPU model of the two pictures the PyroWave renderer can draw into a buffer (#304): present.frag's
 * and sgsr1.frag's.
 *
 * Written statement by statement from the two shaders, in fp64, with Vulkan's sampling rules spelled
 * out: LINEAR filtering from texel centres, CLAMP_TO_EDGE, and textureGather's component order. The
 * JVM tests compare it with an independent model of Qualcomm's own shader and with deliberately wrong
 * conventions; the debug readback self test compares it with what a device's GPU draws.
 *
 * Nothing in a stream calls it.
 */
internal object PyroWaveSgsrReference {

    /** Three tightly packed 8 bit planes, luma first; chroma is a quarter the samples unless 4:4:4. */
    class Planes(
        val width: Int,
        val height: Int,
        val chroma444: Boolean,
        val luma: ByteArray,
        val cb: ByteArray,
        val cr: ByteArray,
    ) {
        val chromaWidth: Int get() = if (chroma444) width else width shr 1
        val chromaHeight: Int get() = if (chroma444) height else height shr 1

        init {
            require(width > 0 && height > 0)
            require(luma.size == width * height)
            require(cb.size == chromaWidth * chromaHeight && cr.size == cb.size)
        }
    }

    /** The four coefficients the renderer pushes. */
    data class Matrix(val crToR: Double, val cbToG: Double, val crToG: Double, val cbToB: Double)

    /** pyrowave_renderer.h colour_matrix() for SDR, as the float32 values it pushes. */
    val REC709 = Matrix(1.5748f.toDouble(), (-0.187324f).toDouble(), (-0.468124f).toDouble(), 1.8556f.toDouble())

    /** What sgsr1.frag does when a filter's weights sum to zero or less. */
    enum class WeightGuard {
        /** Nova: leave the pixel as present.frag draws it. */
        KEEP_BASE,

        /** Qualcomm: divide anyway. */
        UNGUARDED,

        /** RubyLight: floor the sum at 1e-8. */
        FLOOR,
    }

    /**
     * The conventions sgsr1.frag uses. The defaults are Nova's; a test changes one at a time to show
     * the controls can tell it apart.
     */
    data class Convention(
        val imgCoordOffsetX: Double = -0.5,
        val imgCoordOffsetY: Double = 0.5,
        val chromaCentre: Double = 0.5,
        val flipV: Boolean = false,
        val edgeThreshold: Double = 8.0 / 255.0,
        val voteOnRightColumn: Boolean = false,
        val swapLowerGather: Boolean = false,
        val stdScale: Double = 2.181818,
        val distanceScale: Double = 0.55,
        val deltaLimit: Double = 23.0 / 255.0,
        val edgeSharpness: Double = 2.0,
        val preClamp: Boolean = true,
        val weightGuard: WeightGuard = WeightGuard.KEEP_BASE,
    )

    val NOVA = Convention()

    /**
     * The twelve taps, in sgsr1.frag's order: weight_y(pl.x + dx, pl.y + dy, source). The source
     * names the gathered value, and the shader source guard holds the shader to this table.
     */
    data class Tap(val dx: Int, val dy: Int, val source: String)

    val TAPS = listOf(
        Tap(0, 1, "up_down.x"), Tap(-1, 1, "up_down.y"), Tap(-1, -2, "up_down.z"), Tap(0, -2, "up_down.w"),
        Tap(1, -1, "left.x"), Tap(0, -1, "left.y"), Tap(0, 0, "left.z"), Tap(1, 0, "left.w"),
        Tap(-1, -1, "right.x"), Tap(-2, -1, "right.y"), Tap(-2, 0, "right.z"), Tap(-1, 0, "right.w"),
    )

    /** Counts a render gathers, so a test can say what it exercised. */
    class Stats {
        var pixels = 0
        var edgePixels = 0
        var nonPositiveWeights = 0
    }

    // Vulkan sampling, CLAMP_TO_EDGE.

    fun texel(plane: ByteArray, w: Int, h: Int, i: Int, j: Int): Double =
        (plane[j.coerceIn(0, h - 1) * w + i.coerceIn(0, w - 1)].toInt() and 0xff) / 255.0

    /** LINEAR filtering at a normalised coordinate, from texel centres. */
    fun bilinear(plane: ByteArray, w: Int, h: Int, u: Double, v: Double): Double {
        val x = u * w - 0.5
        val y = v * h - 0.5
        val i0 = floor(x).toInt()
        val j0 = floor(y).toInt()
        val fx = x - i0
        val fy = y - j0
        val top = texel(plane, w, h, i0, j0) * (1 - fx) + texel(plane, w, h, i0 + 1, j0) * fx
        val bottom = texel(plane, w, h, i0, j0 + 1) * (1 - fx) + texel(plane, w, h, i0 + 1, j0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** textureGather: the LINEAR footprint at (u, v), as (i0, j1), (i1, j1), (i1, j0), (i0, j0). */
    fun gather(plane: ByteArray, w: Int, h: Int, u: Double, v: Double): DoubleArray {
        val i0 = floor(u * w - 0.5).toInt()
        val j0 = floor(v * h - 0.5).toInt()
        return doubleArrayOf(
            texel(plane, w, h, i0, j0 + 1),
            texel(plane, w, h, i0 + 1, j0 + 1),
            texel(plane, w, h, i0 + 1, j0),
            texel(plane, w, h, i0, j0),
        )
    }

    // The shaders.

    /** present.frag at (u, v), unclamped, as the shader writes it before the UNORM store. */
    fun presentPixel(planes: Planes, matrix: Matrix, u: Double, v: Double, convention: Convention = NOVA): DoubleArray {
        val vv = if (convention.flipV) 1.0 - v else v
        val y = bilinear(planes.luma, planes.width, planes.height, u, vv)
        val cb = bilinear(planes.cb, planes.chromaWidth, planes.chromaHeight, u, vv) - convention.chromaCentre
        val cr = bilinear(planes.cr, planes.chromaWidth, planes.chromaHeight, u, vv) - convention.chromaCentre
        return doubleArrayOf(
            y + matrix.crToR * cr,
            y + matrix.cbToG * cb + matrix.crToG * cr,
            y + matrix.cbToB * cb,
        )
    }

    private fun fastLanczos2(x: Double): Double {
        var wA = x - 4.0
        val wB = x * wA - wA
        wA *= wA
        return wB * wA
    }

    /**
     * The luma delta sgsr1.frag adds at (u, v), or null where it adds none: no edge, or weights that
     * sum to zero or less under Nova's guard.
     */
    fun lumaDelta(planes: Planes, u: Double, v0: Double, convention: Convention = NOVA, stats: Stats? = null): Double? {
        val v = if (convention.flipV) 1.0 - v0 else v0
        val w = planes.width
        val h = planes.height
        val luma = planes.luma
        val invW = 1.0 / w
        val invH = 1.0 / h
        val y = bilinear(luma, w, h, u, v)

        val imgX = u * w + convention.imgCoordOffsetX
        val imgY = v * h + convention.imgCoordOffsetY
        val pixelX = floor(imgX)
        val pixelY = floor(imgY)
        var coordX = pixelX * invW
        val coordY = pixelY * invH
        val plX = imgX - pixelX
        val plY = imgY - pixelY
        val left = gather(luma, w, h, coordX, coordY)

        val voteA = if (convention.voteOnRightColumn) gather(luma, w, h, coordX + invW, coordY) else left
        val edgeVote = if (convention.voteOnRightColumn) {
            abs(voteA[1] - voteA[2]) + abs(y - voteA[2]) + abs(y - voteA[1])
        } else {
            abs(left[2] - left[1]) + abs(y - left[1]) + abs(y - left[2])
        }
        if (edgeVote <= convention.edgeThreshold) return null
        stats?.let { it.edgePixels++ }

        coordX += invW
        val right = gather(luma, w, h, coordX + invW, coordY)
        val up = gather(luma, w, h, coordX, coordY - invH)
        val down = gather(luma, w, h, coordX, coordY + invH)
        val upDown = if (convention.swapLowerGather) {
            doubleArrayOf(up[3], up[2], down[0], down[1])
        } else {
            doubleArrayOf(up[3], up[2], down[1], down[0])
        }

        val mean = (left[1] + left[2] + right[0] + right[3]) * 0.25
        for (k in 0 until 4) {
            left[k] -= mean
            right[k] -= mean
            upDown[k] -= mean
        }
        val centre = y - mean

        val sum = (((((abs(left[0]) + abs(left[1])) + abs(left[2])) + abs(left[3])) +
            (((abs(right[0]) + abs(right[1])) + abs(right[2])) + abs(right[3]))) +
            (((abs(upDown[0]) + abs(upDown[1])) + abs(upDown[2])) + abs(upDown[3])))
        val stdInv = convention.stdScale / sum

        val values = mapOf(
            "up_down.x" to upDown[0], "up_down.y" to upDown[1], "up_down.z" to upDown[2], "up_down.w" to upDown[3],
            "left.x" to left[0], "left.y" to left[1], "left.z" to left[2], "left.w" to left[3],
            "right.x" to right[0], "right.y" to right[1], "right.z" to right[2], "right.w" to right[3],
        )
        var weight = 0.0
        var weighted = 0.0
        for (tap in TAPS) {
            val dx = plX + tap.dx
            val dy = plY + tap.dy
            val c = values.getValue(tap.source)
            val x = ((dx * dx) + (dy * dy)) * convention.distanceScale + (abs(c) * stdInv).coerceIn(0.0, 1.0)
            val wt = fastLanczos2(x)
            weight += wt
            weighted += wt * c
        }

        if (weight <= 0.0) {
            stats?.let { it.nonPositiveWeights++ }
            if (convention.weightGuard == WeightGuard.KEEP_BASE) return null
        }
        var finalY = when (convention.weightGuard) {
            WeightGuard.FLOOR -> weighted / max(weight, 1.0e-8)
            else -> weighted / weight
        }
        val maxY = max(max(left[1], left[2]), max(right[0], right[3]))
        val minY = min(min(left[1], left[2]), min(right[0], right[3]))
        finalY = min(max(convention.edgeSharpness * finalY, minY), maxY)
        return (finalY - centre).coerceIn(-convention.deltaLimit, convention.deltaLimit)
    }

    /** sgsr1.frag at (u, v), unclamped where no edge is filtered, clamped where one is. */
    fun sgsrPixel(planes: Planes, matrix: Matrix, u: Double, v: Double, convention: Convention = NOVA, stats: Stats? = null): DoubleArray {
        stats?.let { it.pixels++ }
        val rgb = presentPixel(planes, matrix, u, v, convention)
        val delta = lumaDelta(planes, u, v, convention, stats) ?: return rgb
        return DoubleArray(3) { k ->
            val base = if (convention.preClamp) rgb[k].coerceIn(0.0, 1.0) else rgb[k]
            (base + delta).coerceIn(0.0, 1.0)
        }
    }

    /** A whole buffer, three values a pixel, rows from the top, at the pixel centres a draw samples. */
    fun render(
        planes: Planes,
        matrix: Matrix,
        outputWidth: Int,
        outputHeight: Int,
        sgsr: Boolean,
        convention: Convention = NOVA,
        stats: Stats? = null,
    ): DoubleArray {
        val out = DoubleArray(outputWidth * outputHeight * 3)
        for (y in 0 until outputHeight) {
            val v = (y + 0.5) / outputHeight
            for (x in 0 until outputWidth) {
                val u = (x + 0.5) / outputWidth
                val rgb = if (sgsr) sgsrPixel(planes, matrix, u, v, convention, stats) else presentPixel(planes, matrix, u, v, convention)
                val at = (y * outputWidth + x) * 3
                out[at] = rgb[0]
                out[at + 1] = rgb[1]
                out[at + 2] = rgb[2]
            }
        }
        return out
    }

    /** What an R8G8B8A8_UNORM store keeps of a value. */
    fun unorm8(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()

    /**
     * Largest difference, in 8 bit steps, between a GPU readback (RGBA, rows from the top) and a
     * render of this model. Alpha is ignored.
     */
    fun maxReadbackError(rgba: ByteArray, expected: DoubleArray, outputWidth: Int, outputHeight: Int): Int {
        require(rgba.size == outputWidth * outputHeight * 4 && expected.size == outputWidth * outputHeight * 3)
        var worst = 0
        for (p in 0 until outputWidth * outputHeight) {
            for (k in 0 until 3) {
                val got = rgba[p * 4 + k].toInt() and 0xff
                worst = max(worst, abs(got - unorm8(expected[p * 3 + k])))
            }
        }
        return worst
    }

    /** Synthetic SDR8 pictures for the controls, full range, chroma sampled at its own grid. */
    object Patterns {
        private fun code(value: Double): Byte = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte()

        /** From per-pixel functions of luma coordinates; chroma takes the value at its top left luma pixel. */
        fun of(
            width: Int,
            height: Int,
            chroma444: Boolean,
            luma: (Int, Int) -> Double,
            cb: (Int, Int) -> Double = { _, _ -> 128.0 / 255.0 },
            cr: (Int, Int) -> Double = { _, _ -> 128.0 / 255.0 },
        ): Planes {
            val shift = if (chroma444) 0 else 1
            val cw = width shr shift
            val ch = height shr shift
            return Planes(
                width, height, chroma444,
                ByteArray(width * height) { code(luma(it % width, it / width)) },
                ByteArray(cw * ch) { code(cb((it % cw) shl shift, (it / cw) shl shift)) },
                ByteArray(cw * ch) { code(cr((it % cw) shl shift, (it / cw) shl shift)) },
            )
        }

        /** Rec. 709 full range Y, Cb and Cr of an RGB colour. */
        fun ycbcr(r: Double, g: Double, b: Double): DoubleArray {
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return doubleArrayOf(y, (b - y) / 1.8556 + 0.5, (r - y) / 1.5748 + 0.5)
        }

        fun neutral(width: Int, height: Int, chroma444: Boolean, code: Int) =
            of(width, height, chroma444, { _, _ -> code / 255.0 })

        /** Eight vertical bars at the given level: white, yellow, cyan, green, magenta, red, blue, black. */
        fun colourBars(width: Int, height: Int, chroma444: Boolean, level: Double): Planes {
            val bars = listOf(
                doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 1.0, 0.0), doubleArrayOf(0.0, 1.0, 1.0),
                doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 1.0), doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0),
            ).map { ycbcr(it[0] * level, it[1] * level, it[2] * level) }
            val bar = { x: Int -> bars[(x * 8 / width).coerceIn(0, 7)] }
            return of(width, height, chroma444, { x, _ -> bar(x)[0] }, { x, _ -> bar(x)[1] }, { x, _ -> bar(x)[2] })
        }

        /** A luma ramp across the frame, from `from` to `to`, horizontally, vertically or diagonally. */
        fun ramp(width: Int, height: Int, chroma444: Boolean, from: Double, to: Double, dx: Int, dy: Int): Planes {
            val span = (dx * (width - 1) + dy * (height - 1)).coerceAtLeast(1)
            return of(width, height, chroma444, { x, y -> from + (to - from) * (dx * x + dy * y) / span })
        }

        /** A vertical luma step at column `at`. */
        fun step(width: Int, height: Int, chroma444: Boolean, at: Int, low: Double, high: Double) =
            of(width, height, chroma444, { x, _ -> if (x < at) low else high })

        /** One pixel lines both ways every `period` pixels, and a checkerboard in the right half. */
        fun lines(width: Int, height: Int, chroma444: Boolean, period: Int) = of(width, height, chroma444, { x, y ->
            when {
                x >= width / 2 -> if ((x + y) % 2 == 0) 0.9 else 0.1
                x % period == 0 || y % period == 0 -> 0.9
                else -> 0.1
            }
        })

        /** A one pixel frame, with a distinct colour touching each edge inside it. */
        fun borders(width: Int, height: Int, chroma444: Boolean): Planes {
            val red = ycbcr(0.8, 0.1, 0.1)
            val green = ycbcr(0.1, 0.8, 0.1)
            val blue = ycbcr(0.1, 0.1, 0.8)
            val yellow = ycbcr(0.8, 0.8, 0.1)
            val grey = ycbcr(0.4, 0.4, 0.4)
            val pick = { x: Int, y: Int ->
                when {
                    x == 0 || y == 0 || x == width - 1 || y == height - 1 -> doubleArrayOf(1.0, 0.5, 0.5)
                    x < 3 -> red
                    x >= width - 3 -> green
                    y < 3 -> blue
                    y >= height - 3 -> yellow
                    else -> grey
                }
            }
            return of(width, height, chroma444, { x, y -> pick(x, y)[0] }, { x, y -> pick(x, y)[1] }, { x, y -> pick(x, y)[2] })
        }

        /**
         * Quadrants marked red top left, green top right, blue bottom left and white bottom right, with
         * an "F" in the top left that reads wrong under either mirror.
         */
        fun orientation(width: Int, height: Int, chroma444: Boolean): Planes {
            val colours = listOf(ycbcr(0.9, 0.1, 0.1), ycbcr(0.1, 0.9, 0.1), ycbcr(0.1, 0.1, 0.9), ycbcr(0.95, 0.95, 0.95))
            val unit = (minOf(width, height) / 16).coerceAtLeast(1)
            val glyph = { x: Int, y: Int ->
                val gx = x / unit - 1
                val gy = y / unit - 1
                gx in 0..3 && gy in 0..5 && (gx == 0 || gy == 0 || (gy == 2 && gx <= 2))
            }
            val pick = { x: Int, y: Int ->
                if (glyph(x, y)) doubleArrayOf(0.0, 0.5, 0.5)
                else colours[(if (x < width / 2) 0 else 1) + (if (y < height / 2) 0 else 2)]
            }
            return of(width, height, chroma444, { x, y -> pick(x, y)[0] }, { x, y -> pick(x, y)[1] }, { x, y -> pick(x, y)[2] })
        }

        /** One bright luma row (or column) at index `at` on a dark field. */
        fun line(width: Int, height: Int, chroma444: Boolean, at: Int, horizontal: Boolean) =
            of(width, height, chroma444, { x, y -> if ((if (horizontal) y else x) == at) 0.9 else 0.1 })

        /** Constant luma with a step in Cb, Cr or both. */
        fun chromaStep(width: Int, height: Int, chroma444: Boolean, at: Int, cbStep: Boolean, crStep: Boolean) =
            of(width, height, chroma444, { _, _ -> 0.5 },
                { x, _ -> if (cbStep && x >= at) 0.8 else 0.5 },
                { x, _ -> if (crStep && x >= at) 0.2 else 0.5 })

        /** Seeded noise, for comparing whole footprints. */
        fun noise(width: Int, height: Int, chroma444: Boolean, seed: Long): Planes {
            val random = java.util.Random(seed)
            val luma = DoubleArray(width * height) { random.nextDouble() }
            return of(width, height, chroma444, { x, y -> luma[y * width + x] },
                { _, _ -> random.nextDouble() }, { _, _ -> random.nextDouble() })
        }
    }
}
