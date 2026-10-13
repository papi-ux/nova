package com.papi.nova.binding.video

import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.PreferenceConfiguration.ScaleMode
import com.papi.nova.ui.StreamContainer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #304: when a PyroWave session asks for SGSR 1, and the buffer it asks for.
 *
 * The decision is pure and tested case by case. The fit size is held to what StreamContainer itself
 * measures, by measuring one, and the native constants and aspect guard are read out of the C header so
 * the two sides cannot drift apart.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PyroWaveScalerPolicyTest {

    private fun decide(
        enabled: Boolean = true,
        experimental: Boolean = true,
        pyrowave: Boolean = true,
        hdr: Boolean = false,
        mode: ScaleMode? = ScaleMode.FIT,
        frame: Pair<Int, Int> = 1280 to 720,
        window: Pair<Int, Int> = 1920 to 1080,
    ) = PyroWaveScalerPolicy.decide(enabled, experimental, pyrowave, hdr, mode, frame.first, frame.second, window.first, window.second)

    @Test fun eligibleFitAndStretchAskForTheOutputSize() {
        val fit = decide()
        assertEquals(PyroWaveScalerPolicy.Reason.ELIGIBLE, fit.reason)
        assertEquals(PyroWaveScalerPolicy.REQUEST_FIT, fit.request)
        assertEquals(1920 to 1080, fit.bufferWidth to fit.bufferHeight)

        val stretch = decide(mode = ScaleMode.STRETCH, window = 2400 to 1080)
        assertEquals(PyroWaveScalerPolicy.REQUEST_STRETCH, stretch.request)
        assertEquals(2400 to 1080, stretch.bufferWidth to stretch.bufferHeight)

        // A missing scale mode is the preference default, fit.
        assertEquals(PyroWaveScalerPolicy.REQUEST_FIT, decide(mode = null).request)
    }

    @Test fun everyRefusalKeepsTheStreamSizeBufferWithItsOwnReason() {
        val cases = mapOf(
            decide(experimental = false) to PyroWaveScalerPolicy.Reason.NOT_EXPERIMENTAL_BUILD,
            // A release build ignores the setting even when it is on.
            decide(enabled = true, experimental = false) to PyroWaveScalerPolicy.Reason.NOT_EXPERIMENTAL_BUILD,
            decide(enabled = false) to PyroWaveScalerPolicy.Reason.DISABLED,
            decide(pyrowave = false) to PyroWaveScalerPolicy.Reason.NOT_PYROWAVE,
            decide(hdr = true) to PyroWaveScalerPolicy.Reason.HDR_OR_10BIT,
            decide(mode = ScaleMode.FILL) to PyroWaveScalerPolicy.Reason.FILL_MODE,
            decide(frame = 1920 to 1080) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
            decide(frame = 2560 to 1440) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
            // Wider than the window and shorter: mixed, so not an upscale in both directions.
            decide(frame = 2400 to 720, mode = ScaleMode.STRETCH) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
            decide(frame = 1280 to 1080, mode = ScaleMode.STRETCH) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
            decide(window = 0 to 0) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
            decide(frame = 0 to 720) to PyroWaveScalerPolicy.Reason.NOT_UPSCALING,
        )
        for ((decision, reason) in cases) {
            assertEquals(decision.toString(), reason, decision.reason)
            assertFalse(decision.toString(), decision.requested)
            assertEquals(PyroWaveScalerPolicy.REQUEST_NONE, decision.request)
            assertEquals(decision.frameWidth to decision.frameHeight, decision.bufferWidth to decision.bufferHeight)
        }
    }

    @Test fun fitMatchesTheDevicePlansSizes() {
        val expected = listOf(
            Triple(1280 to 720, 1920 to 1080, 1920 to 1080),
            Triple(960 to 540, 1920 to 1080, 1920 to 1080),
            Triple(1280 to 800, 1920 to 1200, 1920 to 1200),
            Triple(720 to 1280, 1080 to 1920, 1080 to 1920),
            Triple(1280 to 720, 2400 to 1080, 1920 to 1080),
            Triple(1280 to 800, 1920 to 1080, 1728 to 1080),
        )
        for ((frame, window, buffer) in expected) {
            val decision = decide(frame = frame, window = window)
            assertEquals("$frame in $window", buffer, decision.bufferWidth to decision.bufferHeight)
            assertTrue(decision.requested)
            assertTrue(PyroWaveScalerPolicy.aspectMatches(frame.first, frame.second, decision.bufferWidth, decision.bufferHeight))
        }
    }

    @Test fun fitIsWhatStreamContainerMeasures() {
        val container = StreamContainer(ApplicationProvider.getApplicationContext())
        val frames = listOf(1280 to 720, 960 to 540, 1280 to 800, 720 to 1280, 1366 to 768, 1600 to 900, 853 to 480, 1024 to 768)
        val windows = listOf(1920 to 1080, 2400 to 1080, 1920 to 1200, 1080 to 1920, 2340 to 1080, 2560 to 1600, 3200 to 1440, 2000 to 1200)
        for (frame in frames) for (window in windows) {
            container.setDesiredAspectRatio(frame.first.toDouble() / frame.second.toDouble())
            container.setFillDisplay(false)
            container.measure(
                MeasureSpec.makeMeasureSpec(window.first, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(window.second, MeasureSpec.EXACTLY),
            )
            val policy = PyroWaveScalerPolicy.outputSize(ScaleMode.FIT, frame.first, frame.second, window.first, window.second)
            assertEquals("$frame in $window", container.measuredWidth to container.measuredHeight, policy)
        }
    }

    @Test fun aspectGuardHoldsHalfAPercent() {
        assertTrue(PyroWaveScalerPolicy.aspectMatches(1280, 720, 1920, 1080))
        assertTrue(PyroWaveScalerPolicy.aspectMatches(1280, 720, 1920, 1085))
        assertFalse(PyroWaveScalerPolicy.aspectMatches(1280, 720, 1920, 1200))
        assertFalse(PyroWaveScalerPolicy.aspectMatches(1280, 720, 1920, 1090))
    }

    @Test fun aSessionGameNeverDecidedForSaysSo() {
        val never = PyroWaveScalerPolicy.Decision.notRequested()
        assertFalse(never.requested)
        assertEquals(PyroWaveScalerPolicy.Reason.NOT_EVALUATED, never.reason)
        val line = PyroWaveScalerPolicy.describe(never, null)
        assertTrue(line, line.contains("kept the stream-size buffer (NOT_EVALUATED); renderer no renderer state"))
    }

    @Test fun appliedStateDecodesTheGettersSixValues() {
        assertEquals(null, PyroWaveScalerPolicy.applied(null))
        assertEquals(null, PyroWaveScalerPolicy.applied(intArrayOf(1, 0, 1)))
        val bilinear = PyroWaveScalerPolicy.applied(intArrayOf(2, 3, 1280, 720, 1280, 720))!!
        assertEquals("OUTPUT_BUFFER_BILINEAR", bilinear.stateName)
        assertEquals("NOT_UPSCALING", bilinear.reasonName)
        val line = PyroWaveScalerPolicy.describe(decide(), bilinear)
        assertTrue(line, line.contains("asked for SGSR 1 (fit) into 1920x1080"))
        assertTrue(line, line.contains("OUTPUT_BUFFER_BILINEAR (NOT_UPSCALING), frame 1280x720, buffer 1280x720"))
        val untouched = PyroWaveScalerPolicy.describe(decide(enabled = false), PyroWaveScalerPolicy.applied(intArrayOf(0, 1, 1280, 720, 1280, 720)))
        assertTrue(untouched, untouched.contains("kept the stream-size buffer (DISABLED)"))
        assertTrue(untouched, untouched.contains("DEFAULT (NOT_REQUESTED)"))
    }

    @Test fun kotlinAndNativeAgreeOnEveryConstant() {
        val header = File("src/main/jni/pyrowave-core/pyrowave_renderer_c.h").readText()
        val defines = Regex("""#define (PYROWAVE_[A-Z0-9_]+) (-?\d+)""").findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val expected = mapOf(
            "PYROWAVE_SGSR_REQUEST_NONE" to PyroWaveScalerPolicy.REQUEST_NONE,
            "PYROWAVE_SGSR_REQUEST_FIT" to PyroWaveScalerPolicy.REQUEST_FIT,
            "PYROWAVE_SGSR_REQUEST_STRETCH" to PyroWaveScalerPolicy.REQUEST_STRETCH,
            "PYROWAVE_SCALER_DEFAULT" to PyroWaveScalerPolicy.STATE_DEFAULT,
            "PYROWAVE_SCALER_SGSR1_ACTIVE" to PyroWaveScalerPolicy.STATE_SGSR1_ACTIVE,
            "PYROWAVE_SCALER_OUTPUT_BUFFER_BILINEAR" to PyroWaveScalerPolicy.STATE_OUTPUT_BUFFER_BILINEAR,
            "PYROWAVE_SCALER_REASON_NONE" to PyroWaveScalerPolicy.NATIVE_REASON_NONE,
            "PYROWAVE_SCALER_REASON_NOT_REQUESTED" to PyroWaveScalerPolicy.NATIVE_REASON_NOT_REQUESTED,
            "PYROWAVE_SCALER_REASON_HDR_OR_10BIT" to PyroWaveScalerPolicy.NATIVE_REASON_HDR_OR_10BIT,
            "PYROWAVE_SCALER_REASON_NOT_UPSCALING" to PyroWaveScalerPolicy.NATIVE_REASON_NOT_UPSCALING,
            "PYROWAVE_SCALER_REASON_ASPECT_MISMATCH" to PyroWaveScalerPolicy.NATIVE_REASON_ASPECT_MISMATCH,
            "PYROWAVE_SCALER_REASON_PIPELINE_FAILED" to PyroWaveScalerPolicy.NATIVE_REASON_PIPELINE_FAILED,
        )
        assertEquals(expected, defines.filterKeys { it.startsWith("PYROWAVE_SGSR_") || it.startsWith("PYROWAVE_SCALER_") })

        val renderer = File("src/main/jni/pyrowave-core/pyrowave_renderer.h").readText()
        assertTrue(
            "the renderer's aspect guard is no longer the policy's half a percent",
            renderer.contains("static constexpr double k_sgsr_aspect_tolerance = ${PyroWaveScalerPolicy.ASPECT_TOLERANCE};"),
        )
    }

    @Test fun theSurfaceIsResizedOnlyForAnEligibleSession() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val block = game.substringAfter("var vh:Int = if ((prefConfig != null && prefConfig!!.height > 0)) prefConfig!!.height else displayHeight")
            .substringBefore("streamSurfaceView!!.setZOrderOnTop(false)")
        assertTrue(block, block.contains("PyroWaveScalerPolicy.forSession(this, prefConfig!!, willStreamHdr, displayWidth, displayHeight)"))
        assertTrue(block, block.contains("if (sgsrScaler.requested)"))
        // The one sizing call is the one it always was; only an eligible session changes its arguments.
        assertEquals(1, Regex("""setFixedSize\(""").findAll(block).count())
        assertTrue(block, block.contains("streamSurfaceView!!.getHolder().setFixedSize(vw, vh)"))
        assertTrue(block, block.contains(".getOrDefault(com.papi.nova.binding.video.PyroWaveScalerPolicy.Decision.notRequested())"))
        // And the decoder hears about it only after the surface took the size.
        assertTrue(block.indexOf("setFixedSize(vw, vh)") < block.indexOf("?.scalerDecision = sgsrScaler"))
    }
}
