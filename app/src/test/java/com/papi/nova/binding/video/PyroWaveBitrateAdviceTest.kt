package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveDecoderRenderer.AdviceRule
import com.papi.nova.binding.video.PyroWaveRateModel.Flag
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.NovaBitrateAdvice
import com.papi.nova.preferences.NovaSettingsValidator
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/** Calibrated launch advice checked against upstream encoder fixtures and request units. */
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
    private fun rowsAtCalibratedTargets(): List<Row> {
        assertEquals("psnr,height_factor,chroma444,width,height,fps,mbits", fixture.first())
        return fixture.drop(1).map { it.split(",") }.filter { it[0] == if (it[1] == "15") "31" else "35" }.map { cells ->
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

    /** What upstream's C function answered at the calibrated target, in Mbps. */
    private fun upstreamMbps(heightFactor: Int, chroma444: Boolean, width: Int, height: Int, fps: Int): Double =
        rowsAtCalibratedTargets().single {
            it.heightFactor == heightFactor && it.chroma444 == chroma444 &&
                it.width == width && it.height == height && it.fps == fps
        }.mbits

    /** Mbps as whole kbps, truncated, which is how the advice has always been stated. */
    private fun kbps(mbps: Double): Int = NovaBitrateAdvice.requestForEncoder(floor(mbps * 1000.0).toInt())

    /** Kbps rounded up to the whole Mbps a player is told. */
    private fun roundedUpToMbps(kbps: Int): Int = (kbps + 999) / 1000

    private fun advice(width: Int, height: Int, fps: Int, chroma444: Boolean, heightFactor: Int) =
        PyroWaveDecoderRenderer.bitrateAdvice(width, height, fps, chroma444, heightFactor)

    private fun chroma(chroma444: Boolean) = if (chroma444) "4:4:4" else "4:2:0"

    @Test
    fun atBothDistancesItUsesTheAdviceIsWhatUpstreamMeasured() {
        val rows = rowsAtCalibratedTargets().filter { it.heightFactor == ownScreen || it.heightFactor == acrossTheRoom }
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
        assertEquals(109, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, ownScreen)) // 179.492
        assertEquals(106, PyroWaveDecoderRenderer.advisedMbps(1280, 720, 60, true, ownScreen)) // 137.513
        assertEquals(114, PyroWaveDecoderRenderer.advisedMbps(1280, 800, 60, true, ownScreen)) // 143.176
        assertEquals(298, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, acrossTheRoom)) // 266.7449
        assertEquals(350, PyroWaveDecoderRenderer.advisedMbps(3840, 2160, 60, true, acrossTheRoom)) // 313.803
        assertEquals(101, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, false, ownScreen)) // 153.572
    }

    @Test
    fun encoderRateCostsExactlyItsMultipleBeforeFixedRequestOverhead() {
        for (size in listOf(1920 to 1080, 1280 to 800, 854 to 480, 5120 to 2880)) {
            val at60 = PyroWaveDecoderRenderer.recommendedKbps(size.first, size.second, 60, true, ownScreen)
            val at120 = PyroWaveDecoderRenderer.recommendedKbps(size.first, size.second, 120, true, ownScreen)
            assertEquals("${size.first}x${size.second}", (NovaBitrateAdvice.encoderForRequest(at60) * 2).toDouble(), NovaBitrateAdvice.encoderForRequest(at120).toDouble(), 1.0)
        }
    }

    @Test
    fun pixelsCostFarLessThanTheirMultiple() {
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            val at720 = PyroWaveDecoderRenderer.recommendedKbps(1280, 720, 60, false, factor)
            val at1080 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60, false, factor)
            val upstream = upstreamMbps(factor, false, 1920, 1080, 60) / upstreamMbps(factor, false, 1280, 720, 60)
            assertEquals("H index $factor", upstream, NovaBitrateAdvice.encoderForRequest(at1080).toDouble() / NovaBitrateAdvice.encoderForRequest(at720).toDouble(), 1e-4)
            assertTrue("H index $factor: 1080p costs ${at1080.toDouble() / at720} of 720p", at1080 < at720 * 1.7)
        }
    }

    @Test
    fun theAdviceIsNeverWhatLookedSoft() {
        val least = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 120, false, ownScreen)
        assertEquals(kbps(upstreamMbps(ownScreen, false, 1920, 1080, 120)), least)
        assertTrue("advice of $least kbps is down where the picture looked soft", least > 50_000)
    }

    @Test
    fun calibratedHandheldAdviceAgreesWithTheApprovedRequestRange() {
        for (chroma444 in listOf(false, true)) {
            val got = advice(1920,1080,120,chroma444,ownScreen)
            assertEquals(kbps(upstreamMbps(ownScreen,chroma444,1920,1080,120)),got.kbps)
            assertTrue(got.kbps in 190000..230000)
        }
    }
    private fun sliderMaxKbps(key: String = "seekbar_bitrate_kbps"): Int {
        val prefs = File("src/main/res/xml/preferences.xml").readText()
        val slider = prefs.substringAfter("android:key=\"$key\"").substringBefore("/>")
        return Regex("android:max=\"(\\d+)\"").find(slider)!!.groupValues[1].toInt()
    }

    @Test
    fun uncappedAdviceDoesNotNagAtTheReachableAutomaticOrInputCeiling() {
        assertEquals(214898, advice(1920,1080,120,true,ownScreen).kbps)
        assertEquals(593890, advice(1920,1080,120,true,acrossTheRoom).kbps)
        for (limit in listOf(300000,500000)) {
            val wanted=advice(1920,1080,120,true,acrossTheRoom)
            assertFalse(PyroWaveDecoderRenderer.bitrateWarning(300000,1920,1080,120,wanted,limit)!!.tellPlayer)
            assertTrue(PyroWaveDecoderRenderer.bitrateWarning(299999,1920,1080,120,wanted,limit)!!.tellPlayer)
        }
    }

    @Test
    fun onATelevisionFourKIsAdvisedLessThanFourteenForty() {
        val at1440 = advice(2560, 1440, 60, true, acrossTheRoom).kbps
        val at1800 = advice(3200, 1800, 60, true, acrossTheRoom).kbps
        val at4k = advice(3840, 2160, 60, true, acrossTheRoom).kbps
        assertEquals(kbps(upstreamMbps(acrossTheRoom, true, 2560, 1440, 60)), at1440)
        assertEquals(kbps(upstreamMbps(acrossTheRoom, true, 3840, 2160, 60)), at4k)
        assertTrue("4K $at4k, 3200x1800 $at1800, 1440p $at1440", at4k < at1800 && at1800 < at1440)
        assertTrue(
            advice(3840, 2160, 60, true, ownScreen).kbps > advice(2560, 1440, 60, true, ownScreen).kbps,
        )
    }

    @Test
    fun followingTheAdviceSatisfiesIt() {
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
        assertTrue(PyroWaveDecoderRenderer.recommendedKbps(1280, 720, 60, false, ownScreen) > PreferenceConfiguration.getDefaultBitrate("1280x720", "60"))
    }

    @Test
    fun theDevicesOwnScreenIsTheFarthestDistanceTheModelCovers() {
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
        val own = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = false)
        val tv = PyroWaveDecoderRenderer.viewingHeightFactor(television = true, onExternalDisplay = false)
        val external = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = true)
        assertEquals(109, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, own))
        assertEquals(298, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, tv))
        assertEquals(298, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, external))
    }

    @Test
    fun theOfferNovaMakesForPyroWaveIsAdvisedAsFourFourFour() {
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
        assertEquals(298, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, true, acrossTheRoom))
        assertEquals(246, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 60, false, acrossTheRoom))
        assertTrue(advice(1920, 1080, 60, true, acrossTheRoom).chroma444)
        assertFalse(advice(1920, 1080, 60, false, acrossTheRoom).chroma444)
    }

    @Test
    fun belowSevenTwentyItIsTheBitsPerPixelOfSevenTwenty() {
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            for (chroma444 in listOf(false, true)) {
                val bitsPerPixel = upstreamMbps(factor, chroma444, 1280, 720, 60) * 1e6 / (1280.0 * 720.0 * 60.0)
                for (size in listOf(1280 to 719, 1024 to 600, 854 to 480, 640 to 360)) {
                    val where = "${size.first}x${size.second}, ${chroma(chroma444)}, H index $factor"
                    val got = advice(size.first, size.second, 60, chroma444, factor)
                    val wanted = bitsPerPixel * size.first * size.second * 60 / 1000.0
                    assertEquals(where, NovaBitrateAdvice.requestForEncoder(wanted.toInt()), got.kbps)
                    assertEquals(where, AdviceRule.BELOW_MODEL_EDGE, got.rule)
                    assertTrue(where, Flag.PIXELS_BELOW_MODEL in got.flags)
                }
            }
        }
        assertEquals(kbps(upstreamMbps(ownScreen,true,1280,720,60)/4.0), advice(640,360,60,true,ownScreen).kbps)
    }

    @Test
    fun aboveFourKItIsTheBitsPerPixelOfFourK() {
        for (factor in listOf(ownScreen, acrossTheRoom)) {
            for (chroma444 in listOf(false, true)) {
                val bitsPerPixel = upstreamMbps(factor, chroma444, 3840, 2160, 60) * 1e6 / (3840.0 * 2160.0 * 60.0)
                for (size in listOf(3840 to 2161, 5120 to 2160, 5120 to 2880, 7680 to 4320)) {
                    val where = "${size.first}x${size.second}, ${chroma(chroma444)}, H index $factor"
                    val got = advice(size.first, size.second, 60, chroma444, factor)
                    val wanted = bitsPerPixel * size.first * size.second * 60 / 1000.0
                    assertEquals(where, NovaBitrateAdvice.requestForEncoder(wanted.toInt()), got.kbps)
                    assertEquals(where, AdviceRule.ABOVE_MODEL_EDGE, got.rule)
                    assertTrue(where, Flag.PIXELS_ABOVE_MODEL in got.flags)
                }
            }
        }
        assertEquals(kbps(upstreamMbps(ownScreen,true,3840,2160,60)*4.0), advice(7680,4320,60,true,ownScreen).kbps)
    }

    @Test
    fun theEdgesThemselvesAreTheModel() {
        assertEquals(AdviceRule.MODEL, advice(1280, 720, 60, true, ownScreen).rule)
        assertEquals(AdviceRule.MODEL, advice(3840, 2160, 60, true, ownScreen).rule)
    }

    @Test
    fun whatNothingCanAnswerFallsBackToTheOldFlatFigure() {
        for (factor in listOf(-1, 16, 99)) {
            val got = advice(1920, 1080, 60, true, factor)
            assertEquals("H index $factor", AdviceRule.FLAT_FALLBACK, got.rule)
            assertEquals("H index $factor", NovaBitrateAdvice.requestForEncoder(90823), got.kbps)
            assertEquals("H index $factor", 103, got.mbps)
        }
        val small = advice(854, 480, 60, false, 16)
        assertEquals(AdviceRule.FLAT_FALLBACK, small.rule)
        assertEquals(NovaBitrateAdvice.requestForEncoder(17954), small.kbps)
    }

    @Test
    fun theLogLineSaysWhichRuleAndDistanceGaveTheAdvice() {
        val model = advice(1920, 1080, 60, true, ownScreen).describe()
        for (part in listOf("109 Mbps", "108012 kbps", "rule MODEL ", "31 dB", "H 2.875 (index 15)", "4:4:4")) {
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
    fun theSettingsMaximumIsTheSlidersAndTheCustomEntrys() {
        val top = PreferenceConfiguration.MAX_BITRATE_KBPS
        assertEquals(300_000, top)
        assertEquals("the bitrate slider", top, sliderMaxKbps())
        assertEquals("the metered bitrate slider", top, sliderMaxKbps("seekbar_metered_bitrate_kbps"))
        val custom = PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING
        assertTrue(NovaSettingsValidator.isValidTextValue(custom, "${top / 1000}"))
        assertFalse(NovaSettingsValidator.isValidTextValue(custom, "${top / 1000 + 1}"))
    }

    /** 1080p at 120 fps in 4:4:4 across the room: 594 requested Mbps. */
    private fun pastTheTop() = advice(1920, 1080, 120, true, acrossTheRoom)

    @Test
    fun atTheTopOfTheSliderTheWarningIsLoggedButThePlayerIsNotTold() {
        val wanted = pastTheTop()
        assertEquals(594, wanted.mbps)
        val top = PreferenceConfiguration.MAX_BITRATE_KBPS
        for (streamKbps in listOf(top, top + 1, top + 50_000)) {
            val warning = PyroWaveDecoderRenderer.bitrateWarning(streamKbps, 1920, 1080, 120, wanted)
            assertNotNull("$streamKbps kbps under $wanted is no longer logged", warning)
            assertFalse("$streamKbps kbps: the player is told to pass the top of the slider", warning!!.tellPlayer)
            assertEquals(
                "PyroWave: $streamKbps kbps for 1920x1080 at 120 fps; it wants about 594 Mbps, over the 300 " +
                    "Mbps maximum of the bitrate setting, so the player is not told",
                warning.logLine,
            )
        }
    }

    @Test
    fun oneUnderTheTopThePlayerIsToldAsBefore() {
        val warning = PyroWaveDecoderRenderer.bitrateWarning(
            PreferenceConfiguration.MAX_BITRATE_KBPS - 1, 1920, 1080, 120, pastTheTop(),
        )
        assertNotNull(warning)
        assertTrue("one kbps under the top, the player is no longer told", warning!!.tellPlayer)
        assertEquals("PyroWave: 299999 kbps for 1920x1080 at 120 fps; it wants about 594 Mbps", warning.logLine)

        val ordinary = PyroWaveDecoderRenderer.bitrateWarning(
            20_000, 1920, 1080, 60, advice(1920, 1080, 60, true, ownScreen),
        )
        assertNotNull(ordinary)
        assertTrue(ordinary!!.tellPlayer)
        assertEquals("PyroWave: 20000 kbps for 1920x1080 at 60 fps; it wants about 109 Mbps", ordinary.logLine)
    }

    @Test
    fun enoughOrNoAdviceSaysNothing() {
        val wanted = advice(1920, 1080, 60, true, ownScreen)
        assertNull(PyroWaveDecoderRenderer.bitrateWarning(108_012, 1920, 1080, 60, wanted))
        assertTrue(PyroWaveDecoderRenderer.bitrateWarning(108_011, 1920, 1080, 60, wanted)!!.tellPlayer)
        assertNull(
            PyroWaveDecoderRenderer.bitrateWarning(PreferenceConfiguration.MAX_BITRATE_KBPS, 1920, 1080, 60, wanted),
        )
        assertNull(PyroWaveDecoderRenderer.bitrateWarning(0, 0, 1080, 60, advice(0, 1080, 60, true, ownScreen)))
    }

    @Test
    fun gameLogsEveryWarningButTellsThePlayerOnlyWhenTheyCanAct() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        fun onlyIndexOf(anchor: String): Int {
            assertEquals("'$anchor' appears in Game.kt once", 1, Regex(Regex.escape(anchor)).findAll(game).count())
            return game.indexOf(anchor)
        }
        val weighed = onlyIndexOf("PyroWaveDecoderRenderer.bitrateWarning(")
        val logged = onlyIndexOf("LimeLog.warning(pyroWaveWarning.logLine)")
        val asked = onlyIndexOf("if (pyroWaveWarning.tellPlayer)")
        val told = onlyIndexOf("R.string.nova_pyrowave_bitrate_low")
        assertTrue("the warning is logged before it is weighed", weighed < logged)
        assertTrue("the line is logged only when the player is told", logged < asked)
        assertTrue("the player is told outside the branch that asks whether to", asked < told)
        assertFalse(
            "the branch that asks whether to tell the player closes before telling them",
            game.substring(asked, told).contains('}'),
        )
    }

    @Test
    fun gameAdvisesForTheOfferTheScreenAndTheDistance() {
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
        assertTrue(
            "the warning no longer weighs the stream against this advice",
            site.contains(
                "PyroWaveDecoderRenderer.bitrateWarning(\n" +
                    "                configuredStreamBitrateKbps, displayWidth, displayHeight, pyroWaveFps, pyroWaveAdvice,",
            ),
        )
        assertTrue(
            "the player is no longer told the figure the warning compared",
            game.contains("getString(R.string.nova_pyrowave_bitrate_low, pyroWaveAdvice.mbps)"),
        )
        assertTrue(
            game.indexOf("MoonBridge.VIDEO_FORMAT_PYROWAVE or MoonBridge.VIDEO_FORMAT_PYROWAVE_444") in
                0 until game.indexOf(call),
        )
    }

    @Test
    fun gameAdvisesForTheStreamItSendsNotTheSettingsItStartedFrom() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        fun onlyIndexOf(anchor: String): Int {
            assertEquals("'$anchor' appears in Game.kt once", 1, Regex(Regex.escape(anchor)).findAll(game).count())
            return game.indexOf(anchor)
        }
        val call = onlyIndexOf("PyroWaveDecoderRenderer.bitrateAdvice(")
        for (settled in listOf(
            "if (workerLaunch != null) supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264",
            "configuredStreamBitrateKbps = (if (isMetered) prefConfig!!.meteredBitrate else prefConfig!!.bitrate)",
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

        val guard = onlyIndexOf("if ((supportedVideoFormats and MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0)")
        assertTrue("the advice is not inside the PyroWave guard", guard < call)
        assertFalse("the PyroWave guard closes before the advice", game.substring(guard, call).contains('}'))

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
            site.contains("bitrateWarning(\n                configuredStreamBitrateKbps,"),
        )
        assertFalse("the advice still reads a saved setting", site.contains("prefConfig"))
        assertTrue("the log no longer says whether the stream is HDR", site.contains("\" hdr=\" + willStreamHdr"))
    }
}
