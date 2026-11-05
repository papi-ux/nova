package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertEquals
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
    fun spsFixUpsAreSaidOncePerStreamNotOncePerKeyframe() {
        val setup = source.substringAfter("override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {")
            .substringBefore("\n    }")
        assertFalse(
            "A host that repeats the SPS with every keyframe made these lines most of logcat on a Space " +
                "stream (4,994 of 8,675 in 42 minutes), so the SPS fix-ups go through logSpsPatchOnce.",
            source.contains("LimeLog.info(\"Adding bitstream restrictions\")") ||
                source.contains("LimeLog.info(\"Patching num_ref_frames in SPS\")") ||
                source.contains("LimeLog.info(\"Hacking SPS to baseline\")")
        )
        assertTrue(
            "Each stream says its fix-ups again, so a new setup forgets the ones already said.",
            source.contains("logSpsPatchOnce(\"Adding bitstream restrictions\")") &&
                setup.contains("loggedSpsPatches.clear()")
        )
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return source.substring(start).substringBefore("\n    }\n")
    }

    @Test
    fun codecTimestampStaysEnqueueTimeSoT3KeepsItsMeaning() {
        // Host source time (#242) travels beside the codec in SourceTimeSideMap. The codec
        // timestamp is still the core's enqueue milliseconds times a thousand, bumped a
        // microsecond on a collision, because T3, the decode statistics and the benchmark
        // capture all read it that way.
        assertTrue(source.contains("var timestampUs = enqueueTimeMs * 1000\n"))
        assertTrue(
            source.contains(
                "        if (timestampUs <= lastTimestampUs) {\n" +
                    "            timestampUs = lastTimestampUs + 1\n" +
                    "        }\n" +
                    "        lastTimestampUs = timestampUs\n",
            ),
        )
        val queue = functionBody("    private fun queueNextInputBuffer(")
        assertTrue(
            queue.contains(
                "videoDecoder!!.queueInputBuffer(\n" +
                    "                nextInputBufferIndex,\n" +
                    "                0,\n" +
                    "                nextInputBuffer!!.position(),\n" +
                    "                timestampUs,\n" +
                    "                codecFlags,\n" +
                    "            )",
            ),
        )
        assertFalse("source time must never reach the codec", queue.contains("rtpTimestamp"))
    }

    @Test
    fun onlyQueuedPictureDataIsMappedToSourceTime() {
        // Codec-config buffers go in at timestamp 0 and must never be given a source record.
        assertEquals(2, Regex("""queueNextInputBuffer\(0, MediaCodec\.BUFFER_FLAG_CODEC_CONFIG\)""").findAll(source).count())
        assertEquals(1, Regex("""queueNextInputBuffer\(timestampUs, codecFlags, sourceRecord\)""").findAll(source).count())
        val queue = functionBody("    private fun queueNextInputBuffer(")
        // W26: written before queueInputBuffer so a fast decoder's output finds it, and withdrawn
        // when the codec refuses the buffer, so only queued picture data keeps a record.
        val insert = queue.indexOf("sourceTimeSideMap.put(timestampUs, sourceRecord")
        assertTrue("the record is in place before the codec can return the picture", insert in 0 until queue.indexOf("queueInputBuffer("))
        assertTrue(queue.contains("} catch (e: IllegalStateException) {\n            if (recorded) sourceTimeSideMap.withdraw(timestampUs)"))
        assertTrue(source.contains("sourceTimeSideMap.take(presentationTimeUs, sourceDecoderGeneration, System.nanoTime())"))
    }

    @Test
    fun decodeEndToEndAndBenchmarkAccountingAreUnchanged() {
        val decode = functionBody("    private fun updateDecodeLatencyStats(presentationTimeUs: Long) {")
        assertTrue(decode.contains("val enqNs = enqueueNsByPtsUs[presentationTimeUs]"))
        assertTrue(decode.contains("val decMs = (System.nanoTime() - enqNs) / 1_000_000L"))
        assertTrue(decode.contains("activeWindowVideoStats.decoderTimeMs += decMs"))
        assertTrue(decode.contains("if (!USE_FRAME_RENDER_TIME) {\n                    activeWindowVideoStats.totalTimeMs += decMs"))
        assertTrue(decode.contains("logSampledStageTiming(presentationTimeUs)"))
        assertTrue(decode.contains("recordBenchmarkSample(presentationTimeUs)"))

        assertTrue(functionBody("    private fun logSampledStageTiming(presentationTimeUs: Long) {").contains("val t3Ns = presentationTimeUs * 1000L"))
        assertTrue(functionBody("    private fun recordBenchmarkSample(presentationTimeUs: Long) {").contains("val t3Ns = presentationTimeUs * 1000L"))
        assertTrue(source.contains("activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs"))
        assertTrue(source.contains("private const val USE_FRAME_RENDER_TIME = false"))
        assertTrue(source.contains("private const val ENQUEUE_MAP_MAX_ENTRIES = 256"))
        assertEquals(
            "the arrival-age drop decisions of the non-Balanced modes keep their meaning",
            2,
            Regex("""val frameAgeNs = nowNs - presentationTimeUs \* 1000L""").findAll(source).count(),
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
