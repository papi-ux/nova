package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecDecoderRendererPerfStatsSourceGuardTest {
    private val source: String
        get() = File("src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt").readText()

    @Test
    fun streamSetupResetsRollingPerfStatsForFreshReconnectSamples() {
        val setupBody = source.substringAfter("override fun setup(")
            .substringBefore("return initializeDecoder(false)")

        assertTrue(
            "New stream setup/reconnect must clear rolling FPS windows so NovaHUD does not divide fresh frames by stale pre-disconnect time.",
            setupBody.contains("resetRollingPerfStatsForNewStream(")
        )
    }

    @Test
    fun restartedFrameSequenceClearsStalePerfWindowsBeforeSampling() {
        val restartBranchStart = source.indexOf("if (frameNumber < lastFrameNumber) {")
        val restartReset = source.indexOf(
            "resetRollingPerfStatsForNewStream(\"frame sequence restart\")",
            restartBranchStart
        )
        val nextFpsSamplingBlock = source.indexOf(
            "if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000)",
            restartBranchStart
        )

        assertTrue(
            "If a resumed stream restarts frame numbering, renderer perf stats must reset inside that branch before the next HUD FPS sample.",
            restartBranchStart >= 0 &&
                restartReset > restartBranchStart &&
                nextFpsSamplingBlock > restartReset
        )
    }
}
