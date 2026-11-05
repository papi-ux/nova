package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTimeSideMapTest {
    private fun record(frame: Int, submitNs: Long, generation: Int = 1) = SourceTimeSideMap.Record(
        frameNumber = frame,
        rtpTimestamp = frame * 1500L,
        receiveTimeMs = 10L,
        enqueueTimeMs = 11L,
        submitNs = submitNs,
        hostLatencyTenths = 0,
        connectionGeneration = 1,
        decoderGeneration = generation,
    )

    @Test
    fun aDecodedFrameGetsBackExactlyTheRecordQueuedWithItsTimestamp() {
        val map = SourceTimeSideMap()
        assertEquals(SourceTimeSideMap.PutResult.STORED, map.put(11_000L, record(1, 0), 0))
        assertEquals(SourceTimeSideMap.PutResult.STORED, map.put(11_001L, record(2, 1), 1))

        assertEquals(2, map.take(11_001L, 1, 0)!!.frameNumber)
        assertNull("a timestamp no frame was queued with matches nothing", map.take(11_002L, 1, 0))
        assertEquals(1, map.take(11_000L, 1, 0)!!.frameNumber)
        assertNull("a record is handed out once", map.take(11_000L, 1, 0))
        assertEquals(2L, map.counters().misses)
    }

    @Test
    fun aSecondRecordForTheSameTimestampIsRefusedNotOverwritten() {
        val map = SourceTimeSideMap()
        map.put(5L, record(1, 0), 0)
        assertEquals(SourceTimeSideMap.PutResult.DUPLICATE, map.put(5L, record(2, 1), 1))
        assertEquals(1, map.take(5L, 1, 0)!!.frameNumber)
    }

    @Test
    fun aFullMapRefusesTheNewFrameInsteadOfEvictingAYoungOne() {
        val map = SourceTimeSideMap(capacity = 64)
        for (i in 0 until 64) {
            assertEquals(SourceTimeSideMap.PutResult.STORED, map.put(i.toLong(), record(i, i.toLong()), i.toLong()))
        }
        assertEquals(SourceTimeSideMap.PutResult.EXHAUSTED, map.put(64L, record(64, 64), 64))
        assertEquals(0, map.take(0L, 1, 0)!!.frameNumber)
        assertNull(map.take(64L, 1, 0))
        assertEquals(1L, map.counters().exhausted)
    }

    @Test
    fun entriesOlderThanASecondExpireAndFreeTheirSlots() {
        val map = SourceTimeSideMap(capacity = 2)
        map.put(1L, record(1, 0), 0)
        map.put(2L, record(2, 500_000_000L), 500_000_000L)
        // At 1.2 s the first is 1.2 s old and goes; the second is 0.7 s old and stays.
        assertEquals(SourceTimeSideMap.PutResult.STORED, map.put(3L, record(3, 1_200_000_000L), 1_200_000_000L))
        assertNull(map.take(1L, 1, 0))
        assertEquals(2, map.take(2L, 1, 0)!!.frameNumber)
        assertEquals(1L, map.counters().expired)
    }

    @Test
    fun aRecordFromAnEarlierDecoderGenerationIsNeverHandedToALaterOne() {
        val map = SourceTimeSideMap()
        map.put(7L, record(1, 0, generation = 1), 0)
        assertNull(map.take(7L, 2, 0))
        assertEquals(1L, map.counters().staleGeneration)
        assertNull("and it is gone, not left to match later", map.take(7L, 1, 0))
    }

    @Test
    fun clearForgetsEverything() {
        val map = SourceTimeSideMap()
        map.put(1L, record(1, 0), 0)
        map.clear()
        assertEquals(0, map.counters().live)
        assertNull(map.take(1L, 1, 0))
    }

    @Test
    fun aRecordOlderThanASecondIsNeverHandedOutEvenWhenNoLaterPutExpiredIt() {
        // W26: a final record no later insert aged out could otherwise be taken a long time after.
        val map = SourceTimeSideMap()
        map.put(9L, record(1, 0), 0)
        assertNull(map.take(9L, 1, 1_000_000_001L))
        assertEquals(1L, map.counters().expired)
        assertEquals(0L, map.counters().taken)
        map.put(10L, record(2, 2_000_000_000L), 2_000_000_000L)
        assertEquals("a second to the nanosecond is still young", 2, map.take(10L, 1, 3_000_000_000L)!!.frameNumber)
    }

    @Test
    fun aWithdrawnRecordIsGoneAndIsNotAMiss() {
        val map = SourceTimeSideMap()
        map.put(11L, record(1, 0), 0)
        map.withdraw(11L)
        map.withdraw(12L)
        assertEquals(1L, map.counters().withdrawn)
        assertEquals(0, map.counters().live)
        assertEquals(0L, map.counters().misses)
    }
}
