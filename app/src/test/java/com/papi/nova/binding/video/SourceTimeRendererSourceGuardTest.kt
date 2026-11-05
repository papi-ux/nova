package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-time scheduling (#242 phase 1) is opt-in and default off. When it is not active, every
 * presentation path must be the code that was there before; when it is, its anchor and feedback
 * must be the ones ROOT accepted. MediaCodec and Choreographer cannot run in a JVM test, so this
 * reads the renderer.
 */
class SourceTimeRendererSourceGuardTest {
    private val renderer = File("src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt").readText()
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()
    private val prefs = File("src/main/java/com/papi/nova/preferences/PreferenceConfiguration.kt").readText()

    @Test
    fun theLegacyBalancedQueueAndFrameCallbackAreUntouchedWhenInactive() {
        val branch = renderer.indexOf("} else if (sourceTimeActive) {")
        val legacy = renderer.indexOf("outputBufferQueue.add(lastIndex)")
        assertTrue("the legacy queue stays as the else branch", branch in 1 until legacy)
        assertTrue(renderer.contains("val expectedFrameTimeDeltaNs = 800000000L / refreshRate"))
        assertTrue(renderer.contains("frameTime -= cachedAppVsyncOffsetNanos"))
        assertTrue(renderer.contains("presentFrame(nextOutputBuffer, frameTime)"))
        assertEquals(1, Regex("""if \(sourceTimeActive\) \{\n\s+// Before API 33""").findAll(renderer).count())
    }

    @Test
    fun onlyTheBalancedPathCanBeScheduledAndOnlyWhenAsked() {
        assertTrue(
            renderer.contains(
                "val balanced = prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED\n" +
                    "        sourceTimeActive = request != SourceTimeRequest.OFF && balanced",
            ),
        )
        assertTrue(prefs.contains("const val DEFAULT_SOURCE_TIME_PACING = false"))
        assertTrue(prefs.contains("config.sourceTimePacing = prefs.getBoolean(SOURCE_TIME_PACING_PREF_STRING, DEFAULT_SOURCE_TIME_PACING)"))
        assertTrue(game.contains("decoderRenderer!!.setSourceTimePacingRequest("))
        assertTrue(game.contains("prefConfig!!.sourceTimePacing)\n                com.papi.nova.binding.video.sourcetime.SourceTimeRequest.SOURCE_TIME"))
    }

    @Test
    fun theAnchorIsTheCoreEnqueueTimeAndIngressStaysDiagnostic() {
        // ROOT D2: the decode callback can block behind MediaCodec input, so it is no anchor.
        assertTrue(renderer.contains("sourceTimeMapper?.observe(frameNumber, rtpTimestamp, enqueueTimeMs * 1000)"))
        assertTrue(!renderer.contains("observe(frameNumber, rtpTimestamp, submitNs"))
    }

    @Test
    fun frameRenderedFeedbackIsObservedOnlyAndTheLegacyStatisticIsSeparate() {
        assertTrue(renderer.contains("if (!USE_FRAME_RENDER_TIME && sourceTimeActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {"))
        assertTrue(renderer.contains("presentFeedback.rendered(presentationTimeUs, renderTimeNanos)"))
        val feedbackReads = Regex("""presentFeedback\.counters\(\)""").findAll(renderer).count()
        assertEquals("no pacing decision reads the feedback in phase 1", 0, feedbackReads)
    }

    @Test
    fun theApi33VsyncSourceIsOnlyReachedBehindAVersionCheck() {
        for (use in Regex("""SourceTimeFrameTimelineVsync""").findAll(renderer)) {
            val before = renderer.substring(maxOf(0, use.range.first - 240), use.range.first)
            assertTrue("unguarded use at ${use.range.first}", before.contains("Build.VERSION.SDK_INT >= 33"))
        }
    }
}
