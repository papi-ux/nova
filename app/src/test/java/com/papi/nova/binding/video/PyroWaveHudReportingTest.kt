package com.papi.nova.binding.video

import com.papi.nova.ui.NovaHudUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the HUD is told about this codec, and who is to blame for it.
 */
class PyroWaveHudReportingTest {

    @Test
    fun aGapInTheNumberingIsTheFramesThatNeverArrived() {
        assertEquals(0, PyroWaveDecoderRenderer.framesMissedBetween(41, 42))
        assertEquals(1, PyroWaveDecoderRenderer.framesMissedBetween(41, 43))
        assertEquals(9, PyroWaveDecoderRenderer.framesMissedBetween(41, 51))
    }

    @Test
    fun theFirstFrameHasMissedNothing() {
        // A session whose first frame is numbered in the thousands, which is what watching a stream
        // already in progress looks like, would otherwise open on thousands of losses.
        assertEquals(0, PyroWaveDecoderRenderer.framesMissedBetween(0, 4212))
    }

    @Test
    fun aRepeatOrARestartIsNotALoss() {
        assertEquals(0, PyroWaveDecoderRenderer.framesMissedBetween(41, 41))
        assertEquals(0, PyroWaveDecoderRenderer.framesMissedBetween(4212, 1))
    }

    /**
     * The renderer needs a surface and the native library to submit a frame, so what is checked here is
     * where the number the HUD paints red comes from.
     */
    @Test
    fun theLossTheHudPaintsRedIsTheNetworksAndNotThisDevices() {
        val renderer = File("src/main/java/com/papi/nova/binding/video/PyroWaveDecoderRenderer.kt").readText()
        val reported = renderer.substringAfter("packetLossPct = ").substringBefore("monotonicTimestampMs")
        assertTrue(
            "the frames this renderer could not draw are being reported as packet loss again, which " +
                "blames the network for a fault on this device",
            reported.contains("windowMissing"),
        )
        assertTrue(
            "the count of frames this renderer refused is back in the packet loss figure",
            !reported.contains("windowDrawn"),
        )
    }

    @Test
    fun theCodecLabelFitsTheRowItIsShownIn() {
        // Eight characters arrived on screen as PYROWA with an ellipsis, beside AV1 and HEVC.
        assertEquals("PYRO", NovaHudUiState.normalizeCodecLabel("PyroWave"))
        assertEquals("PYRO", NovaHudUiState.normalizeCodecLabel("pyrowave"))
        assertTrue(NovaHudUiState.normalizeCodecLabel("PyroWave").length <= 4)
    }

    @Test
    fun theOtherCodecsKeepTheirLabels() {
        assertEquals("AV1", NovaHudUiState.normalizeCodecLabel("AV1 Main10"))
        assertEquals("HEVC", NovaHudUiState.normalizeCodecLabel("c2.qti.hevc.decoder"))
        assertEquals("H264", NovaHudUiState.normalizeCodecLabel("OMX.qcom.video.decoder.avc"))
        assertEquals("", NovaHudUiState.normalizeCodecLabel("  "))
    }
}
