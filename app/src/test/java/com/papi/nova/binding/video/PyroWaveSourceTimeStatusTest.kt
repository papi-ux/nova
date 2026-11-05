package com.papi.nova.binding.video

import com.papi.nova.binding.video.sourcetime.SourceTimeRequest
import com.papi.nova.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PyroWaveSourceTimeStatusTest {
    private fun renderer() = PyroWaveDecoderRenderer(object : PerfOverlayListener {
        override fun onPerfUpdate(text: String) = Unit
    })

    @Test
    fun pyroWaveReportsNotParticipatingForEveryRequest() {
        val renderer = renderer()
        assertEquals(
            "st1;requested=off;effective=unavailable;vrr_requested=0;vrr_effective=0;" +
                "fallback=renderer_not_participating",
            renderer.sourceTimePacingStatus().toFramePacingState(),
        )
        renderer.setSourceTimePacingRequest(SourceTimeRequest.SOURCE_TIME)
        assertEquals(
            "st1;requested=source_time;effective=unavailable;vrr_requested=0;vrr_effective=0;" +
                "fallback=renderer_not_participating",
            renderer.sourceTimePacingStatus().toFramePacingState(),
        )
    }

    @Test
    fun pyroWaveAcceptsTheRtpTimestampWithoutChangingWhatItReturns() {
        val renderer = renderer()
        renderer.start()
        // No renderer has been set up, so this is refused exactly as before the argument existed.
        assertEquals(
            MoonBridge.DR_NEED_IDR,
            renderer.submitDecodeUnit(byteArrayOf(1), 1, 0, 1, 2, 0.toChar(), 0, 0, 0xFFFF_FFFFL),
        )
        renderer.prepareForStop()
        renderer.stop()
        renderer.cleanup()
    }
}
