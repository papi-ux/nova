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
        assertTrue(
            "If a resumed stream restarts frame numbering, renderer perf stats must reset before the next HUD sample.",
            source.contains("frameNumber < lastFrameNumber") &&
                source.contains("resetRollingPerfStatsForNewStream(")
        )
    }
}
