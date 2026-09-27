package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveRateModel.Flag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The port of PyroWave's bitrate model, checked against upstream's own C function.
 *
 * The reference numbers in pyrowave-rate-reference.csv were printed by tools/pyrowave_rate_fixture.c,
 * which calls `pyrowave_psnr_hvs_m_h_estimate_mbits` compiled from the unmodified upstream header. The
 * port's table was made from the same header by a different path, a script reading its text, so the
 * two agreeing is evidence the port is the model and not a copy of a mistake.
 */
class PyroWaveRateModelTest {

    private class Reference(
        val psnr: Int,
        val heightFactor: Int,
        val chroma444: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
        val mbits: Double,
    )

    private val fixture: List<String> =
        javaClass.getResource("/pyrowave-rate-reference.csv")!!.readText().lines()

    private fun provenance(key: String): String =
        fixture.single { it.startsWith("# $key ") }.removePrefix("# $key ").trim()

    private fun references(): List<Reference> {
        val rows = fixture.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("psnr,height_factor,chroma444,width,height,fps,mbits", rows.first())
        return rows.drop(1).map { line ->
            val cells = line.split(",")
            assertEquals("a fixture row with the wrong shape: $line", 7, cells.size)
            Reference(
                psnr = cells[0].toInt(),
                heightFactor = cells[1].toInt(),
                chroma444 = cells[2] == "1",
                width = cells[3].toInt(),
                height = cells[4].toInt(),
                fps = cells[5].toInt(),
                mbits = cells[6].toDouble(),
            )
        }
    }

    private fun estimate(
        psnr: Int = 35,
        width: Int = 1920,
        height: Int = 1080,
        heightFactor: Int = PyroWaveRateModel.HEIGHT_FACTOR_2_00,
        chroma444: Boolean = false,
        fps: Double = 60.0,
    ) = PyroWaveRateModel.estimate(psnr, width, height, heightFactor, chroma444, fps)

    @Test
    fun theReferenceWasMadeFromTheHeaderTheTableWasMadeFrom() {
        // Two files regenerated at different times from different headers would each look right and
        // disagree for a reason that is not the port's fault. They name their source, so hold them to it.
        assertEquals(PyroWaveRateTable.HEADER_SHA256, provenance("header-sha256"))
        assertEquals(PyroWaveRateTable.UPSTREAM_COMMIT, provenance("upstream-commit"))
    }

    @Test
    fun everyAnswerUpstreamGaveIsReproduced() {
        val references = references()
        // 8 qualities x 16 distances x 2 chroma x 8 sizes x 4 frame rates, then the other 13 qualities
        // at two sizes. A fixture that lost rows would pass on what was left.
        assertEquals(8 * 16 * 2 * 8 * 4 + 13 * 16 * 2 * 2, references.size)

        // 1e-9 relative. Both sides evaluate the same eight term polynomial on the same doubles in the
        // same order, so they agree to the last bit in practice. The slack is for a C compiler that fuses
        // a multiply and an add, which moves an answer by parts in 1e15 at most. Anything a player could
        // notice is many orders larger: 1e-9 of a 300 Mbps estimate is 0.3 bits per second.
        val tolerance = 1e-9
        var worst = 0.0
        val failures = mutableListOf<String>()
        var extrapolated = 0
        for (r in references) {
            val got = PyroWaveRateModel.estimate(r.psnr, r.width, r.height, r.heightFactor, r.chroma444, r.fps.toDouble())
            val mbps = got.mbps
            val sixteenByNine = r.width * 9 == r.height * 16
            val where = "${r.psnr} dB, H index ${r.heightFactor}, ${if (r.chroma444) "4:4:4" else "4:2:0"}, " +
                "${r.width}x${r.height} at ${r.fps}"
            if (mbps == null) {
                failures += "$where: no estimate, flags ${got.flags}, upstream gave ${r.mbits}"
                continue
            }
            val relative = abs(mbps - r.mbits) / abs(r.mbits)
            worst = maxOf(worst, relative)
            if (relative > tolerance) failures += "$where: $mbps, upstream gave ${r.mbits}"
            if (sixteenByNine) {
                if (!got.withinModel) failures += "$where: flagged ${got.flags} although the model covers it"
            } else {
                extrapolated++
                if (got.flags != setOf(Flag.ASPECT_NOT_16_9) || !got.extrapolated) {
                    failures += "$where: flags ${got.flags}, wanted only the aspect ratio"
                }
            }
        }
        println("PyroWaveRateModel against upstream: ${references.size} rows, worst relative difference $worst")
        assertTrue(
            "${failures.size} of ${references.size} rows differ from upstream, first: ${failures.take(5)}",
            failures.isEmpty(),
        )
        // 1280x800 and 2560x1080 at every quality, distance, chroma and frame rate in the grid.
        assertEquals(8 * 16 * 2 * 2 * 4, extrapolated)
    }

    @Test
    fun everyBucketUpstreamPublishedIsInTheTable() {
        var buckets = 0
        for (psnr in PyroWaveRateModel.MIN_PSNR_DB..PyroWaveRateModel.MAX_PSNR_DB) {
            for (factor in 0 until PyroWaveRateModel.HEIGHT_FACTORS) {
                for (chroma444 in listOf(false, true)) {
                    val coefficients = PyroWaveRateTable.coefficients(psnr, factor, chroma444)
                    assertNotNull("no bucket for $psnr dB, H index $factor, chroma444 $chroma444", coefficients)
                    assertEquals(PyroWaveRateTable.COEFFICIENTS, coefficients!!.size)
                    assertTrue(coefficients.all { it.isFinite() })
                    buckets++
                }
            }
        }
        assertEquals(21 * 16 * 2, buckets)
        assertNull(PyroWaveRateTable.coefficients(29, 0, false))
        assertNull(PyroWaveRateTable.coefficients(30, 16, false))
    }

    @Test
    fun heightFactorsAreUpstreamsEighths() {
        assertEquals(1.0, PyroWaveRateModel.heightFactor(0), 0.0)
        assertEquals(2.0, PyroWaveRateModel.heightFactor(PyroWaveRateModel.HEIGHT_FACTOR_2_00), 0.0)
        assertEquals(2.875, PyroWaveRateModel.heightFactor(PyroWaveRateModel.HEIGHT_FACTOR_2_87), 0.0)
    }

    @Test
    fun sixteenByNineInsideTheRangeIsWithinTheModel() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160)) {
            val got = estimate(width = width, height = height)
            assertTrue("${width}x$height: ${got.flags}", got.withinModel)
            assertFalse(got.extrapolated)
            assertTrue(got.mbps!! > 0.0)
        }
    }

    @Test
    fun sixteenByTenIsAnsweredButMarkedExtrapolated() {
        // The Deck's panel. Inside the pixel range, so upstream answers, but the model never saw its shape.
        val deck = estimate(width = 1280, height = 800)
        assertNotNull(deck.mbps)
        assertEquals(setOf(Flag.ASPECT_NOT_16_9), deck.flags)
        assertTrue(deck.extrapolated)
        assertFalse(deck.withinModel)
    }

    @Test
    fun everyOtherShapeIsExtrapolatedToo() {
        // Ultrawide, portrait, and a near miss: 1366x768 is 0.05% off 16:9, which was still not sampled.
        for ((width, height) in listOf(2560 to 1080, 3440 to 1440, 1080 to 1920, 1366 to 768)) {
            val got = estimate(width = width, height = height)
            assertNotNull("${width}x$height", got.mbps)
            assertEquals("${width}x$height", setOf(Flag.ASPECT_NOT_16_9), got.flags)
            assertTrue(got.extrapolated)
        }
    }

    @Test
    fun thePixelBoundsAreInclusive() {
        // Exactly the smallest and the largest pixel counts upstream accepts, in shapes it never sampled,
        // so the pixel test is shown apart from the aspect one.
        val smallest = estimate(width = 1152, height = 800)
        assertEquals(PyroWaveRateModel.MIN_PIXELS, 1152 * 800)
        assertNotNull(smallest.mbps)
        assertEquals(setOf(Flag.ASPECT_NOT_16_9), smallest.flags)

        val largest = estimate(width = 4608, height = 1800)
        assertEquals(PyroWaveRateModel.MAX_PIXELS, 4608 * 1800)
        assertNotNull(largest.mbps)
        assertEquals(setOf(Flag.ASPECT_NOT_16_9), largest.flags)
    }

    @Test
    fun aPictureSmallerThanTheModelSawHasNoEstimate() {
        val small = estimate(width = 1024, height = 576)
        assertNull(small.mbps)
        assertEquals(setOf(Flag.PIXELS_BELOW_MODEL), small.flags)

        val oneColumnShort = estimate(width = 1279, height = 720)
        assertNull(oneColumnShort.mbps)
        assertEquals(setOf(Flag.PIXELS_BELOW_MODEL, Flag.ASPECT_NOT_16_9), oneColumnShort.flags)
        assertFalse(oneColumnShort.extrapolated)
    }

    @Test
    fun aPictureLargerThanTheModelSawHasNoEstimate() {
        val large = estimate(width = 5120, height = 2880)
        assertNull(large.mbps)
        assertEquals(setOf(Flag.PIXELS_ABOVE_MODEL), large.flags)

        val oneRowOver = estimate(width = 3840, height = 2161)
        assertNull(oneRowOver.mbps)
        assertEquals(setOf(Flag.PIXELS_ABOVE_MODEL, Flag.ASPECT_NOT_16_9), oneRowOver.flags)
    }

    @Test
    fun aSizeTooLargeForAnIntIsStillTooLarge() {
        // 65536 x 65536 is 2^32, which an Int product wraps to 0 and a check in Int would call too small.
        val wrapped = estimate(width = 65536, height = 65536)
        assertNull(wrapped.mbps)
        assertEquals(setOf(Flag.PIXELS_ABOVE_MODEL, Flag.ASPECT_NOT_16_9), wrapped.flags)

        val widest = estimate(width = Int.MAX_VALUE, height = Int.MAX_VALUE)
        assertNull(widest.mbps)
        assertTrue(Flag.PIXELS_ABOVE_MODEL in widest.flags)
    }

    @Test
    fun aSizeThatIsNotPositiveHasNoEstimate() {
        // Negative by negative multiplies into range, which is why the sign is checked before the product.
        for ((width, height) in listOf(0 to 1080, 1920 to 0, -1920 to 1080, -1920 to -1080)) {
            val got = estimate(width = width, height = height)
            assertNull("${width}x$height", got.mbps)
            assertEquals("${width}x$height", setOf(Flag.SIZE_NOT_POSITIVE), got.flags)
        }
    }

    @Test
    fun aQualityOutsideTheTableHasNoEstimate() {
        for (psnr in listOf(29, 51, 0, -35, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val got = estimate(psnr = psnr)
            assertNull("$psnr dB", got.mbps)
            assertEquals("$psnr dB", setOf(Flag.PSNR_OUTSIDE_TABLE), got.flags)
        }
        assertNotNull(estimate(psnr = 30).mbps)
        assertNotNull(estimate(psnr = 50).mbps)
    }

    @Test
    fun aHeightFactorOutsideTheTableHasNoEstimate() {
        for (factor in listOf(-1, 16, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val got = estimate(heightFactor = factor)
            assertNull("H index $factor", got.mbps)
            assertEquals("H index $factor", setOf(Flag.HEIGHT_FACTOR_OUTSIDE_TABLE), got.flags)
        }
        assertNotNull(estimate(heightFactor = 0).mbps)
        assertNotNull(estimate(heightFactor = 15).mbps)
    }

    @Test
    fun aFrameRateThatIsNotPositiveHasNoEstimate() {
        for (fps in listOf(0.0, -0.0, -60.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val got = estimate(fps = fps)
            assertNull("$fps fps", got.mbps)
            assertEquals("$fps fps", setOf(Flag.FPS_NOT_POSITIVE), got.flags)
        }
    }

    @Test
    fun everyReasonIsReportedAtOnce() {
        val got = estimate(psnr = 29, width = 800, height = 600, heightFactor = 16, fps = 0.0)
        assertNull(got.mbps)
        assertEquals(
            setOf(
                Flag.PSNR_OUTSIDE_TABLE,
                Flag.HEIGHT_FACTOR_OUTSIDE_TABLE,
                Flag.PIXELS_BELOW_MODEL,
                Flag.ASPECT_NOT_16_9,
                Flag.FPS_NOT_POSITIVE,
            ),
            got.flags,
        )
        assertFalse(got.extrapolated)
    }

    @Test
    fun onlyTheShapeLeavesAnAnswer() {
        // Everything else is an input upstream asserts on. Extrapolating past its asserts would be
        // inventing a model it chose not to publish.
        assertEquals(listOf(Flag.ASPECT_NOT_16_9), Flag.entries.filter { !it.outsideTable })
    }

    @Test
    fun frameRateMultipliesItExactly() {
        // Intra only: every frame is coded from scratch, so twice the frames is twice the bits.
        val at60 = estimate(fps = 60.0).mbps!!
        val at120 = estimate(fps = 120.0).mbps!!
        assertEquals(at60 * 2, at120, at120 * 1e-12)
    }

    @Test
    fun theModelIsPureKotlin() {
        // It is meant to answer the same on Android, on the Deck and in a JVM test, so it may not reach
        // for a platform API. Checked in the source because the unit test classpath carries android.jar
        // stubs, and a call into one would compile here and only fail when it ran.
        for (name in listOf("PyroWaveRateModel.kt", "PyroWaveRateTable.kt")) {
            val source = File("src/main/java/com/papi/nova/binding/video/$name").readText()
            val imports = source.lines().filter { it.startsWith("import ") }
            assertTrue("$name imports $imports", imports.all { it == "import kotlin.math.sqrt" })
            assertFalse("$name names an Android package", source.contains("android."))
        }
    }
}
