package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveSgsrReference.Convention
import com.papi.nova.binding.video.PyroWaveSgsrReference.Patterns
import com.papi.nova.binding.video.PyroWaveSgsrReference.Planes
import com.papi.nova.binding.video.PyroWaveSgsrReference.WeightGuard
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #304, L1: the CPU model of sgsr1.frag against an independent model of Qualcomm's shader, the
 * correctness controls of the contract, and the wrong conventions those controls must catch.
 *
 * The independent model reads Qualcomm's twelve taps at explicit texel indices around the bilinear
 * quad, the way RubyLight loads them, instead of through textureGather as Nova's shader and its model
 * do, and runs Qualcomm's arithmetic on an RGB texture's green channel. A grey texture makes green and
 * luma the same samples, so the two must find the same edges and add the same delta. Everything here is
 * fp64; the GPU's fp32 and 8 bit filter weights are the debug readback's to compare.
 */
class PyroWaveSgsrReferenceTest {

    /** Qualcomm's sgsr1_shader_mobile.frag, operation mode 1, unguarded, at explicit texel indices. */
    private class QualcommModel(private val planes: Planes) {
        private val w = planes.width
        private val h = planes.height
        private fun g(i: Int, j: Int) = (planes.luma[j.coerceIn(0, h - 1) * w + i.coerceIn(0, w - 1)].toInt() and 0xff) / 255.0

        class Result(val edge: Boolean, val deltaY: Double, val weightSum: Double)

        fun at(u: Double, v: Double): Result {
            // The bilinear base texel and fractions, which textureGather's footprints are built around.
            val x = u * w - 0.5
            val y = v * h - 0.5
            val b = floor(x).toInt()
            val j0 = floor(y).toInt()
            val plx = x - b
            val ply = y - j0
            val colour = (g(b, j0) * (1 - plx) + g(b + 1, j0) * plx) * (1 - ply) +
                (g(b, j0 + 1) * (1 - plx) + g(b + 1, j0 + 1) * plx) * ply

            var left = doubleArrayOf(g(b - 1, j0 + 1), g(b, j0 + 1), g(b, j0), g(b - 1, j0))
            val edgeVote = abs(left[2] - left[1]) + abs(colour - left[1]) + abs(colour - left[2])
            if (edgeVote <= 8.0 / 255.0) return Result(false, 0.0, 0.0)

            var right = doubleArrayOf(g(b + 1, j0 + 1), g(b + 2, j0 + 1), g(b + 2, j0), g(b + 1, j0))
            var upDown = doubleArrayOf(g(b, j0 - 1), g(b + 1, j0 - 1), g(b + 1, j0 + 2), g(b, j0 + 2))
            val mean = (left[1] + left[2] + right[0] + right[3]) * 0.25
            left = DoubleArray(4) { left[it] - mean }
            right = DoubleArray(4) { right[it] - mean }
            upDown = DoubleArray(4) { upDown[it] - mean }
            val centre = colour - mean
            val sum = left.sumOf { abs(it) } + right.sumOf { abs(it) } + upDown.sumOf { abs(it) }
            val std = 2.181818 / sum
            fun lanczos(xx: Double): Double {
                var wA = xx - 4.0
                val wB = xx * wA - wA
                wA *= wA
                return wB * wA
            }
            var ws = 0.0
            var wc = 0.0
            fun weight(dx: Double, dy: Double, c: Double) {
                val xx = (dx * dx + dy * dy) * 0.55 + (abs(c) * std).coerceIn(0.0, 1.0)
                val wt = lanczos(xx)
                ws += wt
                wc += wt * c
            }
            weight(plx, ply + 1.0, upDown[0]); weight(plx - 1.0, ply + 1.0, upDown[1])
            weight(plx - 1.0, ply - 2.0, upDown[2]); weight(plx, ply - 2.0, upDown[3])
            weight(plx + 1.0, ply - 1.0, left[0]); weight(plx, ply - 1.0, left[1])
            weight(plx, ply, left[2]); weight(plx + 1.0, ply, left[3])
            weight(plx - 1.0, ply - 1.0, right[0]); weight(plx - 2.0, ply - 1.0, right[1])
            weight(plx - 2.0, ply, right[2]); weight(plx - 1.0, ply, right[3])
            var finalY = wc / ws
            val maxY = max(max(left[1], left[2]), max(right[0], right[3]))
            val minY = min(min(left[1], left[2]), min(right[0], right[3]))
            finalY = min(max(2.0 * finalY, minY), maxY)
            return Result(true, (finalY - centre).coerceIn(-23.0 / 255.0, 23.0 / 255.0), ws)
        }
    }

    private data class Scale(val frameW: Int, val frameH: Int, val outW: Int, val outH: Int)

    /** The device plan's frame and output pairs, scaled down so fp64 renders stay quick. */
    private val scales = listOf(
        Scale(128, 72, 192, 108),   // 1.5x, as 1280x720 into 1920x1080
        Scale(96, 54, 192, 108),    // 2x, as 960x540 into 1920x1080
        Scale(128, 80, 192, 120),   // 16:10, as 1280x800 into 1920x1200
        Scale(72, 128, 108, 192),   // portrait, as 720x1280 into 1080x1920
        Scale(128, 72, 192, 108),   // 1280x720 fitted into a 2400x1080 window is 1920x1080 again
    )

    private val matrix = PyroWaveSgsrReference.REC709

    private fun forEachOutputPixel(scale: Scale, action: (Int, Int, Double, Double) -> Unit) {
        for (y in 0 until scale.outH) for (x in 0 until scale.outW) {
            action(x, y, (x + 0.5) / scale.outW, (y + 0.5) / scale.outH)
        }
    }

    /** How far a convention's luma delta strays from Qualcomm's, and how often the two disagree on an edge. */
    private data class Disagreement(var maxDelta: Double = 0.0, var edgeMismatches: Int = 0, var compared: Int = 0, var skippedNonPositive: Int = 0)

    private fun compareWithQualcomm(planes: Planes, scale: Scale, convention: Convention = PyroWaveSgsrReference.NOVA): Disagreement {
        val model = QualcommModel(planes)
        val out = Disagreement()
        forEachOutputPixel(scale) { _, _, u, v ->
            val q = model.at(u, v)
            val stats = PyroWaveSgsrReference.Stats()
            val nova = PyroWaveSgsrReference.lumaDelta(planes, u, v, convention, stats)
            if (q.edge && q.weightSum <= 0.0) {
                out.skippedNonPositive++
                return@forEachOutputPixel
            }
            out.compared++
            if ((nova != null) != q.edge) {
                // A Nova edge whose weights sum to zero or less adds nothing, which is not a mismatch of edges.
                if (!(q.edge && stats.nonPositiveWeights > 0)) out.edgeMismatches++
                return@forEachOutputPixel
            }
            if (nova != null) out.maxDelta = max(out.maxDelta, abs(nova - q.deltaY))
        }
        return out
    }

    private fun discriminatingPictures(chroma444: Boolean, scale: Scale): List<Pair<String, Planes>> {
        val w = scale.frameW
        val h = scale.frameH
        return listOf(
            "noise" to Patterns.noise(w, h, chroma444, 304L),
            "noise2" to Patterns.noise(w, h, chroma444, 1993L),
            "step" to Patterns.step(w, h, chroma444, w / 2 + 1, 0.2, 0.8),
            "lines" to Patterns.lines(w, h, chroma444, 5),
            "row" to Patterns.line(w, h, chroma444, h / 3, true),
            "column" to Patterns.line(w, h, chroma444, w / 3, false),
            "orientation" to Patterns.orientation(w, h, chroma444),
            "borders" to Patterns.borders(w, h, chroma444),
        )
    }

    @Test fun novasModelFindsQualcommsEdgesAndAddsItsDeltaEverywhere() {
        var compared = 0
        var edges = 0
        var skipped = 0
        for (chroma444 in listOf(false, true)) for (scale in scales) {
            for ((name, planes) in discriminatingPictures(chroma444, scale)) {
                val d = compareWithQualcomm(planes, scale)
                assertEquals("$name $scale edges", 0, d.edgeMismatches)
                assertTrue("$name $scale delta ${d.maxDelta}", d.maxDelta <= 1e-12)
                compared += d.compared
                skipped += d.skippedNonPositive
            }
            val stats = PyroWaveSgsrReference.Stats()
            PyroWaveSgsrReference.render(Patterns.noise(scale.frameW, scale.frameH, chroma444, 304L), matrix, scale.outW, scale.outH, true, stats = stats)
            edges += stats.edgePixels
        }
        println("SGSR1 L1 agreement: $compared pixels compared with Qualcomm's model, $edges noise edge pixels, " +
            "$skipped pixels with a non-positive weight sum left out of the comparison")
        assertTrue("the comparison must exercise the filter, not only flat ground", edges > 10_000)
    }

    @Test fun everyWrongConventionIsCaughtByAtLeastOneControl() {
        val wrong = mapOf(
            "imgCoord offset (+0.5, +0.5)" to Convention(imgCoordOffsetX = 0.5, imgCoordOffsetY = 0.5),
            "imgCoord offset (-0.5, -0.5)" to Convention(imgCoordOffsetY = -0.5),
            "imgCoord offset (0, 0)" to Convention(imgCoordOffsetX = 0.0, imgCoordOffsetY = 0.0),
            "vertical flip of uv" to Convention(flipV = true),
            "edge vote on the quad's right column" to Convention(voteOnRightColumn = true),
            "lower gather read .xy instead of .yx" to Convention(swapLowerGather = true),
            "edge threshold 4/255" to Convention(edgeThreshold = 4.0 / 255.0),
            "edge threshold 16/255" to Convention(edgeThreshold = 16.0 / 255.0),
            "std divisor 1/sum" to Convention(stdScale = 1.0),
            "distance scale 0.5" to Convention(distanceScale = 0.5),
            "no delta limit" to Convention(deltaLimit = 1.0),
            "edge sharpness 1.0" to Convention(edgeSharpness = 1.0),
        )
        val scale = scales[0]
        for ((name, convention) in wrong) {
            val caught = listOf(false, true).any { chroma444 ->
                discriminatingPictures(chroma444, scale).any { (_, planes) ->
                    val d = compareWithQualcomm(planes, scale, convention)
                    d.edgeMismatches > 0 || d.maxDelta > 1.0 / 255.0
                }
            }
            assertTrue("$name is indistinguishable from Qualcomm's filter on every control picture", caught)
        }
    }

    @Test fun c1NeutralGreyEqualsTheDefaultAndCatchesA128Centre() {
        for (chroma444 in listOf(false, true)) for (scale in scales) for (code in listOf(0, 16, 64, 128, 192, 235, 255)) {
            val planes = Patterns.neutral(scale.frameW, scale.frameH, chroma444, code)
            val stats = PyroWaveSgsrReference.Stats()
            val sgsr = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, true, stats = stats)
            val default = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, false)
            assertEquals(0, stats.edgePixels)
            for (i in sgsr.indices) assertEquals("grey $code", default[i], sgsr[i], 0.0)

            // RubyLight's 128/255 centre moves every channel off the default by about 0.8 of a step.
            val wrong = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, true, Convention(chromaCentre = 128.0 / 255.0))
            assertTrue("a 128/255 chroma centre went unnoticed", wrong.indices.any { abs(wrong[it] - default[it]) > 1e-3 })
        }
    }

    @Test fun c2ColourBarInteriorsEqualTheDefaultAndEdgesMoveLumaOnly() {
        var edgePixels = 0
        var hueChecked = 0
        for (chroma444 in listOf(false, true)) for (scale in scales) for (level in listOf(0.75, 1.0)) {
            val planes = Patterns.colourBars(scale.frameW, scale.frameH, chroma444, level)
            forEachOutputPixel(scale) { _, _, u, v ->
                val base = PyroWaveSgsrReference.presentPixel(planes, matrix, u, v)
                val sgsr = PyroWaveSgsrReference.sgsrPixel(planes, matrix, u, v)
                val delta = PyroWaveSgsrReference.lumaDelta(planes, u, v)
                if (delta == null) {
                    for (k in 0 until 3) assertEquals(base[k], sgsr[k], 0.0)
                } else {
                    edgePixels++
                    assertTrue(abs(delta) <= 23.0 / 255.0 + 1e-15)
                    // Bars are vertical, so an edge is within two source columns of a bar boundary.
                    val column = u * scale.frameW - 0.5
                    val boundary = (1 until 8).minOf { abs(column - (it * scale.frameW / 8.0 - 0.5)) }
                    assertTrue("edge $boundary columns from a bar boundary", boundary <= 2.0)
                    // The same delta on every channel that did not clip: luma moved, hue did not.
                    val clipped = (0 until 3).any { (base[it].coerceIn(0.0, 1.0) + delta) !in 0.0..1.0 }
                    if (!clipped) {
                        hueChecked++
                        for (k in 0 until 3) assertEquals(base[k].coerceIn(0.0, 1.0) + delta, sgsr[k], 1e-12)
                    }
                }
            }
        }
        assertTrue(edgePixels > 0 && hueChecked > 0)
    }

    @Test fun c3GentleRampsEqualTheDefaultAndStayMonotonic() {
        for (chroma444 in listOf(false, true)) for (scale in scales) for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val planes = Patterns.ramp(scale.frameW, scale.frameH, chroma444, 0.2, 0.3, dx, dy)
            val stats = PyroWaveSgsrReference.Stats()
            val sgsr = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, true, stats = stats)
            val default = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, false)
            assertEquals(0, stats.edgePixels)
            for (i in sgsr.indices) assertEquals(default[i], sgsr[i], 0.0)
            // Monotonic along the ramp, in green, the channel nearest luma.
            if (dy == 0) for (x in 1 until scale.outW) {
                assertTrue(sgsr[(scale.outH / 2 * scale.outW + x) * 3 + 1] >= sgsr[(scale.outH / 2 * scale.outW + x - 1) * 3 + 1] - 1e-15)
            }
        }
    }

    @Test fun c4StepsAreMeasuredNotPromised() {
        // The contract's "no new extrema" is not a guarantee of this filter (see the report): the clamps
        // bound luma to the quad only while no channel clips. So this measures and bounds the delta.
        var worstExcursion = 0.0
        for (chroma444 in listOf(false, true)) for (scale in scales) {
            val planes = Patterns.step(scale.frameW, scale.frameH, chroma444, scale.frameW / 2 + 1, 0.2, 0.8)
            forEachOutputPixel(scale) { _, _, u, v ->
                val delta = PyroWaveSgsrReference.lumaDelta(planes, u, v) ?: return@forEachOutputPixel
                assertTrue(abs(delta) <= 23.0 / 255.0 + 1e-15)
                val y = PyroWaveSgsrReference.bilinear(planes.luma, planes.width, planes.height, u, v) + delta
                worstExcursion = max(worstExcursion, max(0.2 - y, y - 0.8).coerceAtLeast(0.0))
            }
        }
        println("SGSR1 C4: worst luma excursion beyond a 0.2/0.8 step: $worstExcursion")
        assertTrue(worstExcursion <= 23.0 / 255.0)
    }

    @Test fun c6BordersStayPutWithoutWrapOrBleed() {
        for (chroma444 in listOf(false, true)) for (scale in scales) {
            val planes = Patterns.borders(scale.frameW, scale.frameH, chroma444)
            forEachOutputPixel(scale) { x, y, u, v ->
                if (x != 0 && y != 0 && x != scale.outW - 1 && y != scale.outH - 1) return@forEachOutputPixel
                val base = PyroWaveSgsrReference.presentPixel(planes, matrix, u, v)
                val sgsr = PyroWaveSgsrReference.sgsrPixel(planes, matrix, u, v)
                // A wrap would bring the opposite edge's colour in, far more than one delta away. Compared as
                // stored: the UNORM store clamps both, and an unfiltered pixel is present.frag's, unclamped.
                for (k in 0 until 3) {
                    assertTrue(abs(sgsr[k].coerceIn(0.0, 1.0) - base[k].coerceIn(0.0, 1.0)) <= 23.0 / 255.0 + 1e-12)
                }
            }
        }
    }

    @Test fun c7QuadrantsAndTheGlyphAreNotMirrored() {
        for (chroma444 in listOf(false, true)) for (scale in scales) for (sgsr in listOf(false, true)) {
            val planes = Patterns.orientation(scale.frameW, scale.frameH, chroma444)
            val out = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, sgsr)
            fun at(fx: Double, fy: Double) = ((fy * scale.outH).toInt() * scale.outW + (fx * scale.outW).toInt()) * 3
            val topLeft = at(0.4, 0.4)
            val topRight = at(0.75, 0.25)
            val bottomLeft = at(0.25, 0.75)
            val bottomRight = at(0.75, 0.75)
            assertTrue("red top left", out[topLeft] > 0.6 && out[topLeft + 2] < 0.4)
            assertTrue("green top right", out[topRight + 1] > 0.6 && out[topRight] < 0.4)
            assertTrue("blue bottom left", out[bottomLeft + 2] > 0.6 && out[bottomLeft] < 0.4)
            assertTrue("white bottom right", out[bottomRight] > 0.8 && out[bottomRight + 1] > 0.8 && out[bottomRight + 2] > 0.8)
            // The F's stem is at the left of the top left quadrant, its arm along the top.
            val unit = minOf(scale.frameW, scale.frameH) / 16
            val stem = at((1.5 * unit) / scale.frameW, (4.0 * unit) / scale.frameH)
            assertTrue("the F's stem is dark", out[stem + 1] < 0.3)

            val flipped = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, sgsr, Convention(flipV = true))
            assertTrue("a flipped picture went unnoticed", flipped[topLeft + 2] > 0.6)
        }
    }

    @Test fun c8ALineLandsWhereTheDefaultPutsIt() {
        for (chroma444 in listOf(false, true)) for (scale in scales) for (horizontal in listOf(true, false)) {
            val frameSize = if (horizontal) scale.frameH else scale.frameW
            val outSize = if (horizontal) scale.outH else scale.outW
            val k = frameSize / 3
            val planes = Patterns.line(scale.frameW, scale.frameH, chroma444, k, horizontal)
            val s = outSize.toDouble() / frameSize
            val expected = (k + 0.5) * s - 0.5
            for (sgsr in listOf(false, true)) {
                val green = { i: Int ->
                    val u = if (horizontal) 0.5 else (i + 0.5) / scale.outW
                    val v = if (horizontal) (i + 0.5) / scale.outH else 0.5
                    (if (sgsr) PyroWaveSgsrReference.sgsrPixel(planes, matrix, u, v) else PyroWaveSgsrReference.presentPixel(planes, matrix, u, v))[1]
                }
                // The field as stored, 26/255 rather than 0.1, measured far from the line.
                val field = green(0)
                var weight = 0.0
                var moment = 0.0
                for (i in 0 until outSize) {
                    val g = green(i)
                    val above = (g - field).coerceAtLeast(0.0)
                    weight += above
                    moment += above * i
                }
                val centroid = moment / weight
                assertEquals("line at $k, scale $s, sgsr $sgsr", expected, centroid, 0.25)
            }
        }
    }

    @Test fun c9ChromaOnlyEdgesEqualTheDefault() {
        for (chroma444 in listOf(false, true)) for (scale in scales) for ((cb, cr) in listOf(true to false, false to true, true to true)) {
            val planes = Patterns.chromaStep(scale.frameW, scale.frameH, chroma444, scale.frameW / 2, cb, cr)
            val stats = PyroWaveSgsrReference.Stats()
            val sgsr = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, true, stats = stats)
            val default = PyroWaveSgsrReference.render(planes, matrix, scale.outW, scale.outH, false)
            assertEquals("SGSR 1 changes luma detail only", 0, stats.edgePixels)
            for (i in sgsr.indices) assertEquals(default[i], sgsr[i], 0.0)
        }
    }

    @Test fun theWeightGuardIsADecisionWhereverItIsReached() {
        // Search small random neighbourhoods for a filter whose weights sum to zero or less. Where one
        // is found, Nova keeps the base pixel while Qualcomm's unguarded division and RubyLight's floor
        // both still add a delta; where none is, the guard is recorded as unreached.
        val random = java.util.Random(304)
        var tried = 0
        var found = 0
        var distinguished = 0
        while (tried < 200_000 && found < 20) {
            tried++
            val bytes = ByteArray(8 * 8) { (random.nextInt(256)).toByte() }
            val planes = Planes(8, 8, true, bytes, ByteArray(64) { 128.toByte() }, ByteArray(64) { 128.toByte() })
            val u = (2.5 + random.nextDouble() * 3) / 8
            val v = (2.5 + random.nextDouble() * 3) / 8
            val stats = PyroWaveSgsrReference.Stats()
            val nova = PyroWaveSgsrReference.lumaDelta(planes, u, v, stats = stats)
            if (stats.nonPositiveWeights == 0) continue
            found++
            assertNull(nova)
            val floor = PyroWaveSgsrReference.lumaDelta(planes, u, v, Convention(weightGuard = WeightGuard.FLOOR))
            assertNotNull(floor)
            if (floor != 0.0) distinguished++
        }
        println("SGSR1 weight guard: $found non-positive weight sums in $tried random neighbourhoods, $distinguished where RubyLight's floor adds a delta")
    }
}
