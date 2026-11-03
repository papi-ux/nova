package com.papi.nova.binding.video

import org.junit.Assert.*
import org.junit.Test

class VideoPayloadRateSamplerTest {
    private fun snapshot(generation: Long = 1, bytes: Long, now: Long) =
        longArrayOf(generation, bytes, now)

    @Test fun measuresBytesInKbpsWithoutBridgingConnectionsOrMissingData() {
        val sampler = VideoPayloadRateSampler()
        assertNull(sampler.sample(snapshot(bytes = 10_000_000, now = 1000)))
        assertEquals(12000.0, sampler.sample(snapshot(bytes = 11_500_000, now = 2000))!!, 0.0)
        assertNull(sampler.sample(snapshot(bytes = 11_500_000, now = 2400)))
        assertNull(sampler.sample(snapshot(generation = 2, bytes = 200_000, now = 2500)))
        assertNull(sampler.sample(snapshot(generation = 2, bytes = 100_000, now = 3600)))
        assertNull(sampler.sample(null))
        assertNull(sampler.sample(snapshot(generation = 2, bytes = 200_000, now = 4600)))
        assertEquals(0.0, sampler.sample(snapshot(generation = 2, bytes = 200_000, now = 5600))!!, 0.0)
    }

    @Test fun rejectsInvalidAndBackwardsClocksAndResetsAfterLongGaps() {
        val sampler = VideoPayloadRateSampler()
        for (invalid in listOf(longArrayOf(), longArrayOf(1, 2), snapshot(0, 1, 1000),
                snapshot(bytes = -1, now = 1000), snapshot(bytes = 1, now = -1))) {
            assertNull(sampler.sample(invalid))
        }
        assertNull(sampler.sample(snapshot(bytes = 100, now = 2000)))
        assertNull(sampler.sample(snapshot(bytes = 200, now = 1000)))
        assertEquals(0.8, sampler.sample(snapshot(bytes = 300, now = 2000))!!, 0.0)
        assertNull(sampler.sample(snapshot(bytes = 1500, now = 8000)))
        assertEquals(8.0, sampler.sample(snapshot(bytes = 2500, now = 9000))!!, 0.0)
    }

    @Test fun snapshotsAreCopiedAndLargeCountersAreSubtractedBeforeConversion() {
        val sampler = VideoPayloadRateSampler()
        val first = snapshot(bytes = Long.MAX_VALUE - 1_500_000, now = 1000)
        assertNull(sampler.sample(first))
        first[1] = 0
        assertEquals(12000.0,
            sampler.sample(snapshot(bytes = Long.MAX_VALUE, now = 2000))!!, 0.0)
    }

    @Test fun usesActualIntervalAndKeepsBytesAcrossShortDuplicatePolling() {
        val sampler = VideoPayloadRateSampler()
        assertNull(sampler.sample(snapshot(bytes = 1000, now = 1000)))
        assertNull(sampler.sample(snapshot(bytes = 2000, now = 1499)))
        assertEquals(24.0, sampler.sample(snapshot(bytes = 2500, now = 1500))!!, 0.0)
        assertNull(sampler.sample(snapshot(bytes = 3000, now = 1500)))
        assertEquals(16.0, sampler.sample(snapshot(bytes = 3500, now = 2000))!!, 0.0)
    }
}
