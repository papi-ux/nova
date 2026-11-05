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
        // One read, for the host report; no pacing decision reads the feedback.
        val feedbackReads = Regex("""presentFeedback\.counters\(\)""").findAll(renderer).toList()
        assertEquals(1, feedbackReads.size)
        val report = renderer.substringAfter("override fun sourceTimeReport(): SourceTimeReport {").substringBefore("\n    }\n")
        assertTrue("the only read is the report's", report.contains("feedback = if (observing) presentFeedback.counters() else null"))
    }

    @Test
    fun everyResetIsNamedAndTheCountsAreReadBesideTheSharedMapper() {
        // Design E.2. The mapper stays the shared contract's port: the counts come from its points
        // and from the renderer's own named reset sites, never from inside it.
        assertEquals("no unnamed reset", 0, Regex("""[mM]apper\?\.reset\(\)""").findAll(renderer).count())
        for (reason in listOf("RESET_FOREGROUND", "RESET_DISPLAY_CHANGE", "RESET_CODEC_RECOVERY", "RESET_TARGET_OUT_OF_BOUNDS",
            "RESET_FRAME_RESTART")) {
            assertTrue(reason, renderer.contains("SourceTimeCounters.$reason"))
        }
        assertTrue(renderer.contains("sourceTimeMapper?.observe(frameNumber, rtpTimestamp, enqueueTimeMs * 1000)?.also(sourceTimeCounters::onPoint)"))
        assertTrue(renderer.contains("sourceTimeCounters.onAdmitted("))
        assertTrue(renderer.contains("presentFeedback.clear()\n        sourceTimeCounters.clear()"))
        // The frame window is the one the HUD sample was just made from, and a new stream forgets it.
        val rollover = renderer.indexOf("perfListener.onPerfSample(perfSample)")
        assertTrue(renderer.indexOf("lastFrameWindow = FrameWindow(fps.renderedFps.toDouble(), lastTwo.intentionalFrameDrops, lastTwo.totalFrames)") > rollover)
        assertTrue(renderer.substringAfter("private fun resetRollingPerfStatsForNewStream(").substringBefore("\n    }\n")
            .contains("lastFrameWindow = null"))
        val sourceTimeFiles = File("src/main/java/com/papi/nova/binding/video/sourcetime/SourceTimeMapper.kt").readText()
        assertTrue("the mapper knows nothing of the counts", !sourceTimeFiles.contains("SourceTimeCounters"))
    }

    @Test
    fun theRendererAimsNothingItselfAndHandsEveryFrameAndVsyncToThePresenter() {
        // W26 F2: the tested path is the shipped path. The renderer adds no offset of its own; the
        // presenter (with its learned delay) decides every target, and gets the time each vsync ran.
        assertEquals(0, Regex("""targetUs \* 1000""").findAll(renderer).count())
        assertTrue(renderer.contains("index, presentationTimeUs, System.nanoTime(), record, sourceSurfaceGeneration, sourceDecoderGeneration,"))
        assertTrue(renderer.contains("nowNs, slotsNs, deadlinesNs, vsyncPeriodNs, sourceSurfaceGeneration, sourceDecoderGeneration,"))
        assertTrue(renderer.contains("presentSourceTimeFrame(nowNs, slots, deadlines, periodNs)"))
        val timeline = File("src/main/java/com/papi/nova/binding/video/SourceTimeFrameTimelineVsync.kt").readText()
        assertTrue(timeline.contains("if (onSlots(now, slots, deadlines, periodNs)) {"))
    }

    @Test
    fun aResetOrANewSurfaceInvalidatesOldTimingAndFeedbackAtTheRenderer() {
        // W26 F3: every record carries the surface it was mapped for, every reset site tells the
        // presenter at once, and feedback pending under an old fence is cleared.
        assertTrue(renderer.contains("surfaceGeneration = sourceSurfaceGeneration,\n        )"))
        val reset = renderer.substringAfter("private fun resetSourceTimeMapping(reason: String) {").substringBefore("\n    }\n")
        assertTrue(reset.contains("sourceTimePresenter?.onMappingReset(SourceTimePresenter.reasonForReset(reason))"))
        assertTrue("outside the mapper's lock", reset.indexOf("onMappingReset") > reset.lastIndexOf("}"))
        assertTrue(renderer.contains("if (decision.fenceMoved) {\n            // Released frames still awaiting a render callback belong to the old epoch or surface.\n            presentFeedback.clear()"))
        assertTrue(renderer.contains("for (entry in decision.drop) {"))
    }

    @Test
    fun aReleaseReachesTheCodecOnlyUnderTheEpochItWasAimedAt() {
        // W34 D2: the one source-timed release call is inside the presenter's epoch hold, and a
        // release it refuses is dropped, never rendered.
        val present = renderer.substringAfter("private fun presentSourceTimeFrame(").substringBefore("\n    }\n")
        assertTrue(present.contains("presenter.submit(shown, sourceSurfaceGeneration, sourceDecoderGeneration) {\n" +
            "                videoDecoder!!.releaseOutputBuffer(shown.index, decision.releaseAtNs)\n            }"))
        assertEquals(1, Regex("""releaseOutputBuffer\(shown\.index, decision\.releaseAtNs\)""").findAll(renderer).count())
        assertTrue(present.contains("if (!submitted) {\n            try {\n                dropFrame(shown.index, intentional = true)"))
    }

    @Test
    fun theStatusIsReadFreshAndASecondOfFramesIsTheNegotiatedRate() {
        // W26 F4: the state the HUD and the host read is judged at the moment it is read, and the
        // stale-frame count is one second at the stream's negotiated rate.
        assertTrue(renderer.contains("return sourceTimePresenter?.pacing(request, System.nanoTime())"))
        assertTrue(renderer.contains("sourceTimePresenter = SourceTimePresenter(capacity, mapper, if (redrawRate > 0) redrawRate else 60)"))
    }

    @Test
    fun oneReleaseIsInFlightAndOnlyTheRenderCallbackOrTheDeadlineEndsIt() {
        // W26 F6: every reachable refresh comes with its deadline, on both vsync sources, and the
        // render callback only tells the scheduler a release is done.
        assertTrue(renderer.contains("val deadlines = LongArray(slots.size) { slots[it] - cachedPresentationDeadlineNs }"))
        val timeline = File("src/main/java/com/papi/nova/binding/video/SourceTimeFrameTimelineVsync.kt").readText()
        assertTrue(timeline.contains("val deadlines = reachable.map { it.deadlineNanos }.toLongArray()"))
        assertTrue(renderer.contains("presentFeedback.rendered(presentationTimeUs, renderTimeNanos)\n" +
            "                    // W26 F6: a rendered frame is no longer in flight."))
        assertEquals(1, Regex("""sourceTimePresenter\?\.onRendered\(presentationTimeUs\)""").findAll(renderer).count())
    }

    @Test
    fun theApi33VsyncSourceIsOnlyReachedBehindAVersionCheck() {
        for (use in Regex("""SourceTimeFrameTimelineVsync""").findAll(renderer)) {
            val before = renderer.substring(maxOf(0, use.range.first - 240), use.range.first)
            assertTrue("unguarded use at ${use.range.first}", before.contains("Build.VERSION.SDK_INT >= 33"))
        }
    }
}
