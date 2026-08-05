package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaActiveSessionRefreshGateTest {
    @Test
    fun newerRefreshRejectsOlderPublication() {
        val gate = NovaActiveSessionRefreshGate()
        val published = mutableListOf<String>()
        val older = gate.begin()
        val newer = gate.begin()

        assertFalse(gate.publishIfCurrent(older) { published += "older" })
        assertTrue(gate.publishIfCurrent(newer) { published += "newer" })
        assertEquals(listOf("newer"), published)
    }

    @Test
    fun localEndInvalidationRejectsAnInFlightRefresh() {
        val gate = NovaActiveSessionRefreshGate()
        val inFlight = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(inFlight))
        assertFalse(gate.publishIfCurrent(inFlight) { error("ended session was resurrected") })
    }

    @Test
    fun currentGenerationCanPublishMoreThanOnceForSequencedFollowUps() {
        val gate = NovaActiveSessionRefreshGate()
        val generation = gate.begin()
        var publications = 0

        assertTrue(gate.publishIfCurrent(generation) { publications += 1 })
        assertTrue(gate.publishIfCurrent(generation) { publications += 1 })
        assertEquals(2, publications)
    }

    @Test
    fun quickStopResumeDuringInitialLoadRequiresOneReplacementRefresh() {
        val gate = NovaActiveSessionRefreshGate()
        val inFlight = gate.begin()

        gate.invalidateForStop()

        assertFalse(gate.isCurrent(inFlight))
        assertTrue(gate.shouldRefreshOnResume(isInitialLoading = true))
        assertFalse(gate.shouldRefreshOnResume(isInitialLoading = true))
    }

    @Test
    fun firstResumeDuringInitialLoadDoesNotDuplicateColdStartRefresh() {
        val gate = NovaActiveSessionRefreshGate()

        assertFalse(gate.shouldRefreshOnResume(isInitialLoading = true))
        assertTrue(gate.shouldRefreshOnResume(isInitialLoading = false))
    }
}
