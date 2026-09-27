package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveDecoderRenderer.AdviceRule
import com.papi.nova.binding.video.PyroWaveRateModel.Flag
import com.papi.nova.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * What PyroWave asks for, and why the answer is shaped the way it is.
 *
 * The advice is the bitrate PyroWave's author measured the codec needing for 35 dB of PSNR-HVS-M-H, at
 * a viewing distance chosen from the device, in the chroma the stream will carry. Every figure expected
 * here is read from pyrowave-rate-reference.csv, which upstream's own C function printed, or is a
 * literal copied out of that file. None is restated from the Kotlin that computes the advice, so a
 * mistake there cannot pass by agreeing with itself.
 */
class PyroWaveBitrateAdviceTest {

    private val ownScreen = PyroWaveRateModel.HEIGHT_FACTOR_2_87
    private val acrossTheRoom = PyroWaveRateModel.HEIGHT_FACTOR_2_00

    private class Row(
        val heightFactor: Int,
        val chroma444: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
        val mbits: Double,
    )

    private val fixture: List<String> =
        javaClass.getResource("/pyrowave-rate-reference.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    /** Every answer upstream gave at the quality the advice aims for. */
    private fun rowsAt35dB(): List<Row> {
        assertEquals("psnr,height_factor,chroma444,width,height,fps,mbits", fixture.first())
        return fixture.drop(1).map { it.split(",") }.filter { it[0] == "35" }.map { cells ->
            Row(
                heightFactor = cells[1].toInt(),
                chroma444 = cells[2] == "1",
                width = cells[3].toInt(),
                height = cells[4].toInt(),
                fps = cells[5].toInt(),
                mbits = cells[6].toDouble(),
            )
        }
    }

    /** What upstream's C function answered at 35 dB, in Mbps. */
    private fun upstreamMbps(heightFactor: Int, chroma444: Boolean, width: Int, height: Int, fps: Int): Double =
        rowsAt35dB().single {
            it.heightFactor == heightFactor && it.chroma444 == chroma444 &&
                it.width == width && it.height == height && it.fps == fps
        }.mbits

    /** Mbps as whole kbps, truncated, which is how the advice has always been stated. */
    private fun kbps(mbps: Double): Int = floor(mbps * 1000.0).toInt()

    /** Kbps rounded up to the whole Mbps a player is told. */
    private fun roundedUpToMbps(kbps: Int): Int = (kbps + 999) / 1000

    private fun advice(width: Int, height: Int, fps: Int, chroma444: Boolean, heightFactor: Int) =
        PyroWaveDecoderRenderer.bitrateAdvice(width, height, fps, chroma444, heightFactor)

    private fun chroma(chroma444: Boolean) = if (chroma444) "4:4:4" else "4:2:0"

    @Test
    fun atBothDistancesItUsesTheAdviceIsWhatUpstreamMeasured() {
        // 2 distances x 2 chroma x 8 sizes x 4 frame rates. The two shapes that are not 16:9, the Deck's
        // 1280x800 and 2560x1080, are inside the pixel range, so they are the model's answer too, flagged.
        val rows = rowsAt35dB().filter { it.heightFactor == ownScreen || it.heightFactor == acrossTheRoom }
        assertEquals(2 * 2 * 8 * 4, rows.size)
        val failures = mutableListOf<String>()
        for (r in rows) {
            val where = "H index ${r.heightFactor}, ${chroma(r.chroma444)}, ${r.width}x${r.height} at ${r.fps}"
            val got = advice(r.width, r.height, r.fps, r.chroma444, r.heightFactor)
            val wantedKbps = kbps(r.mbits)
            val wantedRule = if (r.width * 9 == r.height * 16) AdviceRule.MODEL else AdviceRule.MODEL_NOT_16_9
            if (got.kbps != wantedKbps) failures += "$where: ${got.kbps} kbps, upstream gave ${r.mbits} Mbps"
            if (got.mbps != roundedUpToMbps(wantedKbps)) failures += "$where: told ${got.mbps} Mbps for ${r.mbits}"
            if (got.rule != wantedRule) failures += "$where: rule ${got.rule}, wanted $wantedRule"
            val kbpsCall = PyroWaveDecoderRenderer.recommendedKbps(r.width, r.height, r.fps, r.chroma444, r.heightFactor)
            val mbpsCall = PyroWaveDecoderRenderer.advisedMbps(r.width, r.height, r.fps, r.chroma444, r.heightFactor)
            if (kbpsCall != got.kbps || mbpsCall != got.mbps) {
                failures += "$where: recommendedKbps $kbpsCall and advisedMbps $mbpsCall disagree with $got"
            }
        }
        assertTrue("${failures.size} of ${rows.size} differ, first: ${failures.take(5)}", failures.isEmpty())
    }

    @Test
    fun theFiguresAPlayerIsTold() {
        // Copied from the fixture, so they read as numbers rather than as arithmetic. First a phone, a
        // handheld or a tablet showing the stream on its own screen, in the 4:4:4 Nova's offer settles on.
        assertEquals(180, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, ownScreen)) // 179.492
        assertEquals(138, PyroWaveDecoderRenderer.advisedMbps(1280, 720, 60, true, ownScreen)) // 137.513
        assertEquals(144, PyroWaveDecoderRenderer.advisedMbps(1280, 800, 60, true, ownScreen)) // 143.176
        // A television, or a stream on an external display. 4K asks for more than the 300 Mbps the
        // slider reaches, and says so anyway: the number is the codec's, not the slider's.
        assertEquals(267, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, acrossTheRoom)) // 266.7449
        assertEquals(314, PyroWaveDecoderRenderer.advisedMbps(3840, 2160, 60, true, acrossTheRoom)) // 313.803
        // And 4:2:0, for an offer without 4:4:4.
        assertEquals(154, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, false, ownScreen)) // 153.572
    }

    @Test
    fun frameRateCostsExactlyItsMultiple() {
        // The whole difference between this codec and an inter frame one. Doubling the frame rate of
        // H.264 costs far less than double, because the extra frames resemble their neighbours and
        // are coded as differences. Here every frame is coded from scratch, so it costs double, and
        // advice that assumed otherwise would under ask at high frame rates, which is exactly where
        // the picture was measured falling apart. Within a kbps, because each answer is truncated to a
        // whole one. Inside the model, and past each of its edges.
        for (size in listOf(1920 to 1080, 1280 to 800, 854 to 480, 5120 to 2880)) {
            val at60 = PyroWaveDecoderRenderer.recommendedKbps(size.first, size.second, 60, true, ownScreen)
            val at120 = PyroWaveDecoderRenderer.recommendedKbps(size.first, size.second, 120, true, ownScreen)
            assertEquals("${size.first}x${size.second}", (at60 * 2).toDouble(), at120.toDouble(), 1.0)
        }
    }

    @Test
    fun pixelsCostFarLessThanTheirMultiple() {
        // Why the flat figure went. 1920x1080 is 2.25 times the pixels of 1280x720, and upstream measured
        // it needing about 1.63 times the bits at a monitor's distance and 1.36 at a handheld's. A wavelet
        // codec gives up the finest detail first, and most of what a bigger picture of the same scene adds
        // is finer detail.
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            val at720 = PyroWaveDecoderRenderer.recommendedKbps(1280, 720, 60, false, factor)
            val at1080 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60, false, factor)
            val upstream = upstreamMbps(factor, false, 1920, 1080, 60) / upstreamMbps(factor, false, 1280, 720, 60)
            assertEquals("H index $factor", upstream, at1080.toDouble() / at720.toDouble(), 1e-4)
            assertTrue("H index $factor: 1080p costs ${at1080.toDouble() / at720} of 720p", at1080 < at720 * 1.7)
        }
    }

    @Test
    fun theAdviceIsNeverWhatLookedSoft() {
        // Measured by eye on Control at 1920x1080: 50 Mbps at 120 fps looked soft. The least the advice
        // asks for at that size and rate is 4:2:0 on the device's own screen, 307.143 in the fixture.
        val least = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 120, false, ownScreen)
        assertEquals(kbps(upstreamMbps(ownScreen, false, 1920, 1080, 120)), least)
        assertTrue("advice of $least kbps is down where the picture looked soft", least > 50_000)
    }

    @Test
    fun followingTheAdviceSatisfiesIt() {
        // The warning compares against the exact figure and prints a rounded one, so rounding down would
        // tell a player a number that, once set, is still under what was wanted, on every launch,
        // forever. The one number is the one that is shown, rounded up.
        val sizes = listOf(
            854 to 480, 1280 to 720, 1280 to 800, 1920 to 1080, 2560 to 1080, 2560 to 1440,
            3840 to 2160, 5120 to 2880,
        )
        for (fps in listOf(30, 60, 90, 120, 144)) {
            for (size in sizes) {
                for (chroma444 in listOf(false, true)) {
                    for (factor in listOf(ownScreen, acrossTheRoom)) {
                        val got = advice(size.first, size.second, fps, chroma444, factor)
                        assertTrue(
                            "at ${size.first}x${size.second}@$fps, ${chroma(chroma444)}, H index $factor: " +
                                "setting the advised ${got.mbps} Mbps is still under the ${got.kbps} kbps it " +
                                "wanted, so the advice can never be taken",
                            got.mbps * 1000 >= got.kbps,
                        )
                        assertTrue("an advice of nothing for a real stream: $got", got.kbps > 0)
                    }
                }
            }
        }
    }

    @Test
    fun novasDefaultIsWellUnderWhatThisCodecNeeds() {
        // 20 Mbps is Nova's default and the reason this advice exists at all: a player who picks the
        // codec and changes nothing else would judge it at a setting it cannot meet. Even the cheapest
        // stream the model covers, 720p in 4:2:0 on a handheld, asks for 112.960 in the fixture.
        assertTrue(PyroWaveDecoderRenderer.recommendedKbps(1280, 720, 60, false, ownScreen) > 100_000)
    }

    @Test
    fun theDevicesOwnScreenIsTheFarthestDistanceTheModelCovers() {
        // A phone, a handheld or a tablet held at arm's length sits farther away than H 2.87, and the
        // model reaches no farther, so the last distance it has is the nearest to the truth.
        val factor = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = false)
        assertEquals(15, factor)
        assertEquals(PyroWaveRateModel.HEIGHT_FACTORS - 1, factor)
        assertEquals(2.875, PyroWaveRateModel.heightFactor(factor), 0.0)
    }

    @Test
    fun aTelevisionOrAnExternalDisplayIsWatchedFromAMonitorsDistance() {
        for ((television, external) in listOf(true to false, false to true, true to true)) {
            val factor = PyroWaveDecoderRenderer.viewingHeightFactor(television, external)
            assertEquals("television $television, external display $external", 8, factor)
            assertEquals(2.0, PyroWaveRateModel.heightFactor(factor), 0.0)
        }
    }

    @Test
    fun closerAsksForMore() {
        // What the distance rule changes, from the fixture: 1080p60 in 4:4:4 is 179.492 on the device's
        // own screen and 266.7449 on a television, because nearer, an eye finds more of what is missing.
        val own = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = false)
        val tv = PyroWaveDecoderRenderer.viewingHeightFactor(television = true, onExternalDisplay = false)
        val external = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = true)
        assertEquals(180, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, own))
        assertEquals(267, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, tv))
        assertEquals(267, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, external))
    }

    @Test
    fun theOfferNovaMakesForPyroWaveIsAdvisedAsFourFourFour() {
        // Game offers exactly one of these two pairs for this codec, eight bit or ten bit for HDR, and the
        // streaming library settles on the 4:4:4 half whenever the host advertises it, which a host that
        // serves PyroWave does.
        assertTrue(
            PyroWaveDecoderRenderer.adviceChroma444(
                MoonBridge.VIDEO_FORMAT_PYROWAVE or MoonBridge.VIDEO_FORMAT_PYROWAVE_444,
            ),
        )
        assertTrue(
            PyroWaveDecoderRenderer.adviceChroma444(
                MoonBridge.VIDEO_FORMAT_PYROWAVE_10BIT or MoonBridge.VIDEO_FORMAT_PYROWAVE_444_10BIT,
            ),
        )
    }

    @Test
    fun anOfferWithoutFourFourFourIsAdvisedAsFourTwoZero() {
        assertFalse(PyroWaveDecoderRenderer.adviceChroma444(MoonBridge.VIDEO_FORMAT_PYROWAVE))
        assertFalse(PyroWaveDecoderRenderer.adviceChroma444(MoonBridge.VIDEO_FORMAT_PYROWAVE_10BIT))
        assertFalse(PyroWaveDecoderRenderer.adviceChroma444(MoonBridge.VIDEO_FORMAT_H264))
    }

    @Test
    fun fourFourFourCostsWhatUpstreamMeasuredForIt() {
        // The metric scores luma only, so 4:4:4 shows up as a cost and never as a benefit: at 1080p60 on
        // a television the fixture has 266.7449 for 4:4:4 against 220.301 for 4:2:0.
        assertEquals(267, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, acrossTheRoom))
        assertEquals(221, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, false, acrossTheRoom))
        assertTrue(advice(1920, 1080, 60, true, acrossTheRoom).chroma444)
        assertFalse(advice(1920, 1080, 60, false, acrossTheRoom).chroma444)
    }

    @Test
    fun belowSevenTwentyItIsTheBitsPerPixelOfSevenTwenty() {
        // The model says nothing under 1280x720, so the advice holds the last bits per pixel it does say
        // at the same distance and chroma. An extrapolation: the model's bits per pixel rise as the
        // picture shrinks, so the truth down here is probably higher still, but by how much is exactly
        // what the model does not say, and holding the edge claims no more than was measured. 1280x719
        // is one row short of the edge.
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            for (chroma444 in listOf(false, true)) {
                val bitsPerPixel = upstreamMbps(factor, chroma444, 1280, 720, 60) * 1e6 / (1280.0 * 720.0 * 60.0)
                for (size in listOf(1280 to 719, 1024 to 600, 854 to 480, 640 to 360)) {
                    val where = "${size.first}x${size.second}, ${chroma(chroma444)}, H index $factor"
                    val got = advice(size.first, size.second, 60, chroma444, factor)
                    val wanted = bitsPerPixel * size.first * size.second * 60 / 1000.0
                    assertEquals(where, wanted, got.kbps.toDouble(), 1.0)
                    assertEquals(where, AdviceRule.BELOW_MODEL_EDGE, got.rule)
                    assertTrue(where, Flag.PIXELS_BELOW_MODEL in got.flags)
                }
            }
        }
        // A quarter of 720p's pixels, a quarter of 137.513: 34.378, told as 35.
        assertEquals(35, PyroWaveDecoderRenderer.advisedMbps(640, 360, 60, true, ownScreen))
    }

    @Test
    fun aboveFourKItIsTheBitsPerPixelOfFourK() {
        // The same at the other edge, where the trend runs the other way: bits per pixel fall as the
        // picture grows, so holding 4K's figure probably asks for more than is needed. 3840x2161 is one
        // row over the edge.
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            for (chroma444 in listOf(false, true)) {
                val bitsPerPixel = upstreamMbps(factor, chroma444, 3840, 2160, 60) * 1e6 / (3840.0 * 2160.0 * 60.0)
                for (size in listOf(3840 to 2161, 5120 to 2160, 5120 to 2880, 7680 to 4320)) {
                    val where = "${size.first}x${size.second}, ${chroma(chroma444)}, H index $factor"
                    val got = advice(size.first, size.second, 60, chroma444, factor)
                    val wanted = bitsPerPixel * size.first * size.second * 60 / 1000.0
                    assertEquals(where, wanted, got.kbps.toDouble(), 1.0)
                    assertEquals(where, AdviceRule.ABOVE_MODEL_EDGE, got.rule)
                    assertTrue(where, Flag.PIXELS_ABOVE_MODEL in got.flags)
                }
            }
        }
        // Four times 4K's pixels, four times 234.389: 937.554, told as 938.
        assertEquals(938, PyroWaveDecoderRenderer.advisedMbps(7680, 4320, 60, true, ownScreen))
    }

    @Test
    fun theEdgesThemselvesAreTheModel() {
        assertEquals(AdviceRule.MODEL, advice(1280, 720, 60, true, ownScreen).rule)
        assertEquals(AdviceRule.MODEL, advice(3840, 2160, 60, true, ownScreen).rule)
    }

    @Test
    fun whatNothingCanAnswerFallsBackToTheOldFlatFigure() {
        // No stream Nova builds reaches this, because the distance rule names only distances the table
        // has. It is there so that a change to the rule can never make the advice disappear. 0.73 bits
        // per pixel, the figure measured by eye before the model: 1920x1080 at 60 is 124416000 pixels a
        // second, so 90823.68 kbps.
        for (factor in listOf(-1, 16, 99)) {
            val got = advice(1920, 1080, 60, true, factor)
            assertEquals("H index $factor", AdviceRule.FLAT_FALLBACK, got.rule)
            assertEquals("H index $factor", 90823, got.kbps)
            assertEquals("H index $factor", 91, got.mbps)
        }
        // Past an edge too, where the edge has no answer at that distance either: 854x480 at 60 is
        // 24595200 pixels a second, so 17954.496 kbps.
        val small = advice(854, 480, 60, false, 16)
        assertEquals(AdviceRule.FLAT_FALLBACK, small.rule)
        assertEquals(17954, small.kbps)
    }

    @Test
    fun theLogLineSaysWhichRuleAndDistanceGaveTheAdvice() {
        val model = advice(1920, 1080, 60, true, ownScreen).describe()
        for (part in listOf("180 Mbps", "179492 kbps", "rule MODEL ", "35 dB", "H 2.875 (index 15)", "4:4:4")) {
            assertTrue("'$part' is missing from: $model", model.contains(part))
        }
        val edge = advice(854, 480, 60, false, acrossTheRoom).describe()
        for (part in listOf("rule BELOW_MODEL_EDGE", "H 2.0 (index 8)", "4:2:0", "PIXELS_BELOW_MODEL")) {
            assertTrue("'$part' is missing from: $edge", edge.contains(part))
        }
    }

    @Test
    fun nonsenseHasNoAdviceInMbpsEither() {
        assertEquals(0, PyroWaveDecoderRenderer.advisedMbps(0, 1080, 60, true, ownScreen))
        assertEquals(0, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 0, true, ownScreen))
    }

    @Test
    fun nonsenseIsRefusedRatherThanExtrapolated() {
        for (args in listOf(
            Triple(0, 1080, 60),
            Triple(1920, 0, 60),
            Triple(1920, 1080, 0),
            Triple(-1920, -1080, -60),
        )) {
            val got = advice(args.first, args.second, args.third, true, ownScreen)
            assertEquals("$args", 0, got.kbps)
            assertEquals("$args", AdviceRule.NONE, got.rule)
            assertEquals(
                "$args",
                0,
                PyroWaveDecoderRenderer.recommendedKbps(args.first, args.second, args.third, true, ownScreen),
            )
        }
    }

    @Test
    fun gameAdvisesForTheOfferTheScreenAndTheDistance() {
        // Game builds the advice where nothing can be instantiated in a JVM test, so the wiring is
        // pinned as text, the way this repository pins the rest of Game.kt's launch decisions.
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val call = "PyroWaveDecoderRenderer.bitrateAdvice("
        assertEquals("the advice is built in one place", 1, Regex(Regex.escape(call)).findAll(game).count())
        val site = game.substringAfter(call).substringBefore("NovaSnackbar.showQuiet")
        assertTrue(
            "the advice no longer reads the chroma from the formats Nova offers",
            site.contains("adviceChroma444(supportedVideoFormats)"),
        )
        assertTrue(
            "the advice no longer knows whether the stream is on an external display",
            site.contains("onExternalDisplay = isOnExternalDisplay"),
        )
        assertTrue("the advice no longer knows a television", site.contains("television = pyroWaveTelevision"))
        assertTrue(
            "the television check is no longer the UI mode",
            game.contains(
                "as? android.app.UiModeManager)\n                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION",
            ),
        )
        assertTrue("the rule and distance are no longer logged", site.contains("pyroWaveAdvice.describe()"))
        assertTrue("the warning no longer uses this advice", site.contains("val wantedMbps = pyroWaveAdvice.mbps"))
        assertTrue(
            "the warning no longer compares in the unit it prints",
            site.contains("configuredStreamBitrateKbps < wantedMbps * 1000"),
        )
        // The offer the chroma is read from has to be made before the advice reads it.
        assertTrue(
            game.indexOf("MoonBridge.VIDEO_FORMAT_PYROWAVE or MoonBridge.VIDEO_FORMAT_PYROWAVE_444") in
                0 until game.indexOf(call),
        )
    }

    @Test
    fun gameAdvisesForTheStreamItSendsNotTheSettingsItStartedFrom() {
        // Launch moves the stream away from the saved settings after the PyroWave offer is made. The
        // display's maximum, Auto Safe and frame pacing move the frame rate; a watched stream and Auto
        // Safe the size; a metered network and Auto Safe the bitrate; and a Space launch replaces the
        // offer with H.264. Advice given before all of that described a stream that was not the one
        // sent: a 120 fps setting on a 60 Hz phone was advised at 120, and an H.264 Space launch was
        // advised and logged as PyroWave. Pinned as text for the same reason as the test above.
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        fun onlyIndexOf(anchor: String): Int {
            assertEquals("'$anchor' appears in Game.kt once", 1, Regex(Regex.escape(anchor)).findAll(game).count())
            return game.indexOf(anchor)
        }
        val call = onlyIndexOf("PyroWaveDecoderRenderer.bitrateAdvice(")
        for (settled in listOf(
            "if (workerLaunch != null) supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264",
            "configuredStreamBitrateKbps = if (isMetered) prefConfig!!.meteredBitrate else prefConfig!!.bitrate",
            "configuredStreamBitrateKbps = autoSafeBitrateKbps",
            "displayHeight = autoSafeResolution!!.height",
            "configuredStreamFrameRateFps = chosenFrameRate",
        )) {
            assertTrue("the advice is given before the launch settles '$settled'", onlyIndexOf(settled) < call)
        }
        assertTrue(
            "the advice is given after the stream it describes is handed to the connection",
            call < onlyIndexOf("StreamConfiguration.Builder()"),
        )

        // Only an offer that is still PyroWave is advised as PyroWave, and the guard encloses the advice.
        val guard = onlyIndexOf("if ((supportedVideoFormats and MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0)")
        assertTrue("the advice is not inside the PyroWave guard", guard < call)
        assertFalse("the PyroWave guard closes before the advice", game.substring(guard, call).contains('}'))

        // The size, frame rate and bitrate the stream is configured with, not the saved ones.
        val site = game.substring(call).substringBefore("NovaSnackbar.showQuiet")
        assertTrue(
            "the advice no longer reads the size and frame rate the stream is sent at",
            site.startsWith("PyroWaveDecoderRenderer.bitrateAdvice(\n                displayWidth, displayHeight, pyroWaveFps,"),
        )
        assertTrue(
            "the frame rate advised is no longer the one the encoder is asked for",
            game.substring(guard, call).contains("val pyroWaveFps = Math.round(chosenFrameRate)"),
        )
        assertTrue(
            "the warning no longer compares the bitrate the stream is sent at",
            site.contains("configuredStreamBitrateKbps < wantedMbps * 1000"),
        )
        assertFalse("the advice still reads a saved setting", site.contains("prefConfig"))
        assertTrue("the log no longer says whether the stream is HDR", site.contains("\" hdr=\" + willStreamHdr"))
    }
}
