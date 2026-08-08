package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecDecoderRendererStageTimingSourceGuardTest {
    private val source: String
        get() = File("src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt").readText()

    @Test
    fun enqueueTimestampMapIsNoLongerAnUnboundedUnsynchronizedLongSparseArray() {
        assertFalse(
            "enqueueNsByPtsUs must not regress to importing android.util.LongSparseArray: put() " +
                "runs on the decode-unit submission thread while get()/remove() run on the renderer " +
                "thread, and LongSparseArray is neither thread-safe nor bounded.",
            source.contains("import android.util.LongSparseArray")
        )
        assertFalse(
            "enqueueNsByPtsUs must not regress to instantiating a LongSparseArray.",
            source.contains("LongSparseArray<")
        )
        assertTrue(
            "enqueueNsByPtsUs must stay bounded so a frame whose output is dropped rather than " +
                "presented (routine under the non-BALANCED frame-pacing policies) cannot leak its " +
                "entry forever.",
            source.contains("removeEldestEntry")
        )
    }

    @Test
    fun stageTimingLogIsSampledNotPerFrame() {
        val fnStart = source.indexOf("private fun logSampledStageTiming(presentationTimeUs: Long) {")
        val fnBody = source.substring(fnStart).substringBefore("\n    }")

        assertTrue(
            "The T3->T4 structured log must be sampled (not emitted every frame) to avoid " +
                "flooding logcat at up to ~120 Hz.",
            fnStart >= 0 &&
                fnBody.contains("t3t4LogCounter") &&
                fnBody.contains("% T3_T4_LOG_SAMPLE_INTERVAL")
        )
    }

    @Test
    fun stageTimingLogHasAStableGreppablePrefix() {
        assertTrue(
            "P0-5's bench harness needs a stable, greppable prefix to scrape T3->T4 samples from logcat.",
            source.contains("\"Nova: stage_timing t3_to_t4_ms=\"")
        )
    }
}
