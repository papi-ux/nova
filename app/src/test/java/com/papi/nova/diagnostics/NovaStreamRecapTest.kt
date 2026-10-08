package com.papi.nova.diagnostics

import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.diagnostics.NovaStreamRecap.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accumulator's measurement rules, driven with samples shaped like the renderer's. */
class NovaStreamRecapTest {
    companion object {
        fun launch(host: String? = "host-entry-1", reconnect: Boolean = false) = NovaStreamRecap.Launch(
            role = NovaMomentReport.StreamRole.PLAYING,
            requested = NovaMomentReport.requested(1920, 1080, 60f, 20_000, "FORCE_HEVC"),
            reconnectRequested = reconnect,
            requestedHost = host,
        )

        fun sample(
            at: Long,
            expected: Long = 0,
            lost: Long = 0,
            generation: Long = 1,
            rendered: Double = 60.0,
            received: Double = 60.0,
            decode: Double = 3.0,
            rtt: Int = 4,
            hostLatency: Double? = 2.0,
            codec: String = "c2.qti.hevc.decoder",
            width: Int = 1920,
            height: Int = 1080,
            windowLossPct: Double = 0.0,
        ) = PerfOverlaySample(
            fps = rendered, incomingFps = received, renderedFps = rendered, width = width, height = height, codec = codec,
            rttMs = rtt, rttVarianceMs = 1, decodeTimeMs = decode, packetLossPct = windowLossPct,
            monotonicTimestampMs = at, framesExpected = expected, framesReceived = expected - lost,
            framesRendered = expected - lost, framesLost = lost, hostProcessingLatencyMs = hostLatency,
            sessionGeneration = generation,
        )
    }

    private fun connected(generation: Long = 7L) = NovaStreamRecap.Accumulator(generation).apply {
        launch(launch())
        connected(1_000L)
    }

    private fun NovaStreamRecap.Accumulator.end(at: Long = 100_000L, end: End = End.CONNECTION_ENDED, code: Int? = null) =
        finish(end, code, at, "2026-10-08T14:00:00Z")

    @Test fun aConnectionWithNoSamplesSaysSoInsteadOfZeros() {
        val recap = connected().end()!!
        assertFalse(recap.mediaSampled)
        assertEquals(0, recap.acceptedSamples)
        listOf(recap.renderedFps, recap.receivedFps, recap.decodeTimeMs, recap.hostProcessingLatencyMs, recap.controlRttMs)
            .forEach { assertNull(it) }
        assertNull(recap.lossBetweenSamples)
        assertNull(recap.connectionLoss)
        assertNull(recap.last)
        assertNull(recap.samplesCoveredMs)
        assertEquals(99_000L, recap.connectedForMs)
    }

    @Test fun aConnectionThatNeverStartedOrWasNeverLaunchedHasNoRecap() {
        val notConnected = NovaStreamRecap.Accumulator(1L).apply { launch(launch()) }
        assertNull(notConnected.end())
        val notLaunched = NovaStreamRecap.Accumulator(1L).apply { connected(1_000L) }
        assertNull(notLaunched.end())
    }

    @Test fun finishFreezesOnceAndLaterSamplesChangeNothing() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 60), 2_000L)
        val recap = acc.end()!!
        acc.accept(sample(3_000L, expected = 120, rendered = 10.0), 3_000L)
        assertNull("a second finish publishes nothing", acc.end())
        assertEquals(1, recap.acceptedSamples)
        assertEquals(60.0, recap.renderedFps!!.mean, 0.0)
    }

    @Test fun eachMetricKeepsItsOwnDenominator() {
        val acc = connected()
        acc.accept(sample(2_000L, rtt = 0, hostLatency = null), 2_000L) // no RTT estimate yet, no host latency
        acc.accept(sample(3_000L, rtt = 8, received = Double.NaN), 3_000L)
        acc.accept(sample(4_000L, rtt = 6, decode = Double.POSITIVE_INFINITY), 4_000L)
        val recap = acc.end()!!
        assertEquals(3, recap.acceptedSamples)
        assertEquals(3, recap.renderedFps!!.count)
        assertEquals(2, recap.receivedFps!!.count)
        assertEquals(2, recap.decodeTimeMs!!.count)
        assertEquals(2, recap.hostProcessingLatencyMs!!.count)
        assertEquals(2, recap.controlRttMs!!.count)
        assertEquals(7.0, recap.controlRttMs!!.mean, 0.0)
        assertEquals(6.0, recap.controlRttMs!!.min, 0.0)
        assertEquals(8.0, recap.controlRttMs!!.max, 0.0)
    }

    @Test fun aGenuineZeroFrameRateStaysZeroButLeavesDecodeTimeOut() {
        val acc = connected()
        acc.accept(sample(2_000L, rendered = 0.0, received = 0.0, decode = 0.0), 2_000L)
        val recap = acc.end()!!
        assertEquals(0.0, recap.renderedFps!!.mean, 0.0)
        assertEquals(1, recap.renderedFps!!.count)
        assertNull("no frames rendered, so no decode time", recap.decodeTimeMs)
        assertEquals(0.0, recap.last!!.renderedFps!!, 0.0)
    }

    @Test fun outOfRangeReadingsAreUnavailable() {
        val acc = connected()
        acc.accept(sample(2_000L, rendered = 1_500.0, received = -1.0, decode = 20_000.0, rtt = 70_000, hostLatency = -2.0), 2_000L)
        val recap = acc.end()!!
        assertEquals(1, recap.acceptedSamples)
        listOf(recap.renderedFps, recap.receivedFps, recap.decodeTimeMs, recap.hostProcessingLatencyMs, recap.controlRttMs)
            .forEach { assertNull(it) }
    }

    @Test fun missingFutureAndOutOfOrderTimesAreRejectedWhole() {
        val acc = connected()
        acc.accept(sample(5_000L, expected = 100), 5_000L)
        acc.accept(sample(0L, expected = 200, rendered = 1.0), 6_000L)       // no time
        acc.accept(sample(9_000L, expected = 300, rendered = 1.0), 6_000L)   // in the future
        acc.accept(sample(4_000L, expected = 400, rendered = 1.0), 6_000L)   // older than the last
        acc.accept(sample(5_000L, expected = 500, rendered = 1.0), 6_000L)   // not advancing
        acc.accept(sample(6_000L, expected = 160, lost = 2), 6_000L)
        val recap = acc.end()!!
        assertEquals(2, recap.acceptedSamples)
        assertEquals(4, recap.rejectedSamples)
        assertEquals(60.0, recap.renderedFps!!.min, 0.0)
        assertEquals(1_000L, recap.samplesCoveredMs)
        val loss = recap.lossBetweenSamples!!
        assertEquals(1, loss.intervals)
        assertEquals(60L, loss.framesExpected)
        assertEquals(2L, loss.framesLost)
    }

    @Test fun lossBetweenSamplesSumsOnlyValidIntervalsAndIgnoresTheRollingWindowPercentage() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 100, lost = 0, windowLossPct = 50.0), 2_000L)
        acc.accept(sample(3_000L, expected = 160, lost = 3, windowLossPct = 0.0), 3_000L)
        acc.accept(sample(4_000L, expected = 160, lost = 3, windowLossPct = 0.0), 4_000L) // an empty interval
        acc.accept(sample(5_000L, expected = 200, lost = 4), 5_000L)
        val recap = acc.end()!!
        val loss = recap.lossBetweenSamples!!
        assertEquals("the first sample and the empty interval give none", 2, loss.intervals)
        assertEquals(100L, loss.framesExpected)
        assertEquals(4L, loss.framesLost)
        assertEquals(4.0, loss.pct, 1e-9)
        val whole = recap.connectionLoss!!
        assertEquals(200L, whole.framesExpected)
        assertEquals(4L, whole.framesLost)
    }

    @Test fun aCounterDecreaseBreaksThatIntervalAndTheWholeConnectionRatio() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 100, lost = 1), 2_000L)
        acc.accept(sample(3_000L, expected = 40, lost = 0), 3_000L)  // counters went backwards
        acc.accept(sample(4_000L, expected = 100, lost = 2), 4_000L) // a clean interval afterwards
        val recap = acc.end()!!
        assertNull(recap.connectionLoss)
        val loss = recap.lossBetweenSamples!!
        assertEquals(1, loss.intervals)
        assertEquals(60L, loss.framesExpected)
        assertEquals(2L, loss.framesLost)
    }

    @Test fun aGenerationChangeGivesNoIntervalAcrossItAndNoWholeConnectionRatio() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 100, lost = 0, generation = 1), 2_000L)
        acc.accept(sample(3_000L, expected = 30, lost = 1, generation = 2), 3_000L)
        acc.accept(sample(4_000L, expected = 90, lost = 1, generation = 2), 4_000L)
        val recap = acc.end()!!
        assertNull(recap.connectionLoss)
        assertEquals(1, recap.lossBetweenSamples!!.intervals)
        assertEquals(60L, recap.lossBetweenSamples!!.framesExpected)
    }

    @Test fun aRunThatDidNotStartAtTheFirstGenerationNeverClaimsTheWholeConnection() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 50, generation = 3), 2_000L)
        acc.accept(sample(3_000L, expected = 110, lost = 1, generation = 3), 3_000L)
        val recap = acc.end()!!
        assertNull(recap.connectionLoss)
        assertNotNull(recap.lossBetweenSamples)
    }

    @Test fun invalidCountersOrNonPositiveGenerationsGiveNoLoss() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 100, lost = 0, generation = 0), 2_000L)
        acc.accept(sample(3_000L, expected = 160, lost = 2, generation = 0), 3_000L)
        acc.accept(sample(4_000L, expected = 170, lost = 200, generation = 1), 4_000L) // lost > expected
        val recap = acc.end()!!
        assertNull(recap.lossBetweenSamples)
        assertNull(recap.connectionLoss)
    }

    @Test fun lostOutgrowingExpectedInAnIntervalIsNotCounted() {
        val acc = connected()
        acc.accept(sample(2_000L, expected = 100, lost = 0), 2_000L)
        acc.accept(sample(3_000L, expected = 110, lost = 20), 3_000L)
        assertNull(acc.end()!!.lossBetweenSamples)
    }

    @Test fun mixedCodecsAndSizesAreSaidRatherThanLabelledAsTheLastDecoder() {
        val acc = connected()
        acc.accept(sample(2_000L, codec = "c2.qti.hevc.decoder"), 2_000L)
        acc.accept(sample(3_000L, codec = "c2.qti.av1.decoder", width = 1280, height = 720), 3_000L)
        val recap = acc.end()!!
        assertEquals(listOf("hevc", "av1"), recap.codecs)
        assertTrue(recap.dimensionsChanged)
        assertEquals("av1", recap.last!!.codec)
    }

    @Test fun theLastSampleIsSeparateFromTheAverages() {
        val acc = connected()
        acc.accept(sample(2_000L, rendered = 60.0), 2_000L)
        acc.accept(sample(3_000L, rendered = 60.0), 3_000L)
        acc.accept(sample(4_000L, rendered = 12.0), 4_000L) // the degraded end
        val recap = acc.end(at = 4_500L)!!
        assertEquals(44.0, recap.renderedFps!!.mean, 1e-9)
        assertEquals(12.0, recap.last!!.renderedFps!!, 0.0)
        assertEquals(500L, recap.last!!.ageAtEndMs)
    }

    @Test fun connectedTimeAndSampledSpanAreDifferentQuantities() {
        val acc = connected() // connected at 1 s
        acc.accept(sample(10_000L), 10_000L)
        acc.accept(sample(40_000L), 40_000L)
        val recap = acc.end(at = 61_000L)!!
        assertEquals(60_000L, recap.connectedForMs)
        assertEquals(30_000L, recap.samplesCoveredMs)
    }

    @Test fun anErrorCodeIsKeptOnlyForAnErrorEnd() {
        assertEquals(-7, connected().end(end = End.ERROR, code = -7)!!.errorCode)
        assertNull(connected().end(end = End.CONNECTION_ENDED, code = 0)!!.errorCode)
    }

    @Test fun theLaunchIsFixedOnceAndRequestedBitrateIsOnlyARequest() {
        val acc = NovaStreamRecap.Accumulator(3L)
        acc.launch(launch(host = "first"))
        acc.launch(launch(host = "second"))
        acc.connected(1_000L)
        acc.connected(5_000L)
        val recap = acc.end(at = 6_000L)!!
        assertEquals("first", recap.launch.requestedHost)
        assertEquals(20_000, recap.launch.requested.bitrateKbps)
        assertEquals("the first connectionStarted counts", 5_000L, recap.connectedForMs)
        assertEquals(3L, recap.generation)
    }
}
