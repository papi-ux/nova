package com.papi.nova.binding.video

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifyBoundaryTest {

    @Test
    fun acceptsAnObservationEntirelyWithinTheWindow() {
        assertEquals(BoundaryClassification.ACCEPTED, classifyBoundary(10, 20, 100))
    }

    @Test
    fun acceptsAZeroDurationObservation() {
        assertEquals(BoundaryClassification.ACCEPTED, classifyBoundary(10, 10, 100))
    }

    @Test
    fun acceptsAnObservationStartingExactlyAtS() {
        assertEquals(BoundaryClassification.ACCEPTED, classifyBoundary(0, 5, 100))
    }

    @Test
    fun excludesAnObservationThatStartedBeforeTheWindow() {
        assertEquals(BoundaryClassification.EXCLUDED_BEFORE_WINDOW, classifyBoundary(-1, 5, 100))
    }

    @Test
    fun ignoresAnObservationStartingAtOrAfterTheWindowEnd() {
        assertEquals(BoundaryClassification.IGNORED_POST_WINDOW, classifyBoundary(100, 105, 100))
    }

    @Test
    fun excludesAnObservationThatCompletesExactlyAtOrAfterTheWindowEnd() {
        assertEquals(BoundaryClassification.EXCLUDED_AFTER_WINDOW, classifyBoundary(90, 100, 100))
    }

    @Test
    fun rejectsANonMonotonicObservationAsInvalid() {
        assertEquals(BoundaryClassification.INVALID_NON_MONOTONIC, classifyBoundary(20, 10, 100))
    }

    @Test
    fun classificationOrderPrefersBeforeWindowOverNonMonotonic() {
        // a < 0 AND b < a - before-window must win, matching Polaris's own
        // five-step order (checked first, unconditionally).
        assertEquals(BoundaryClassification.EXCLUDED_BEFORE_WINDOW, classifyBoundary(-5, -10, 100))
    }
}

class BenchmarkStageCaptureTest {

    @Test
    fun recordAcceptsAndStoresWithinCapacity() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.record(10, 20, 100)
        capture.record(30, 45, 100)

        assertEquals(2, capture.acceptedCount)
        assertEquals(listOf(10, 30), capture.startOffsetUs().toList())
        assertEquals(listOf(20, 45), capture.endOffsetUs().toList())
        assertEquals(listOf(10, 15), capture.durationUs().toList())
    }

    @Test
    fun overflowsPastCapacityWithoutGrowingStorage() {
        val capture = BenchmarkStageCapture(capacity = 1)
        capture.record(10, 20, 100)
        capture.record(30, 45, 100)

        assertEquals(1, capture.acceptedCount)
        assertEquals(1, capture.overflowCount)
        assertEquals(listOf(10), capture.startOffsetUs().toList())
    }

    @Test
    fun countsExcludedBeforeWindowWithoutStoring() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.record(-5, 10, 100)

        assertEquals(0, capture.acceptedCount)
        assertEquals(1, capture.excludedStartedBeforeWindow)
    }

    @Test
    fun countsExcludedAfterWindowWithoutStoring() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.record(90, 105, 100)

        assertEquals(0, capture.acceptedCount)
        assertEquals(1, capture.excludedCompletedAfterWindow)
    }

    @Test
    fun countsInvalidNonMonotonicWithoutStoring() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.record(20, 10, 100)

        assertEquals(0, capture.acceptedCount)
        assertEquals(1, capture.invalidDurationCount)
    }

    @Test
    fun ignoredPostWindowObservationIncrementsNoCounterAtAll() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.record(100, 110, 100)

        assertEquals(0, capture.acceptedCount)
        assertEquals(0, capture.excludedStartedBeforeWindow)
        assertEquals(0, capture.excludedCompletedAfterWindow)
        assertEquals(0, capture.overflowCount)
        assertEquals(0, capture.invalidDurationCount)
    }

    @Test
    fun multipleAcceptedObservationsStayInInsertionOrder() {
        val capture = BenchmarkStageCapture(capacity = 8)
        capture.record(5, 8, 100)
        capture.record(1, 2, 100)
        capture.record(50, 60, 100)

        assertEquals(listOf(5, 1, 50), capture.startOffsetUs().toList())
    }

    @Test
    fun recordMissingIncrementsItsOwnCounterOnly() {
        val capture = BenchmarkStageCapture(capacity = 4)
        capture.recordMissing()
        capture.recordMissing()

        assertEquals(2, capture.missingTimestampCount)
        assertEquals(0, capture.acceptedCount)
        assertEquals(0, capture.invalidDurationCount)
    }
}
