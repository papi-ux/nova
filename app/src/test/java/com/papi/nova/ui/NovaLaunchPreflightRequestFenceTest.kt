package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLaunchPreflightRequestFenceTest {

    @Test
    fun lateOlderResponseCannotPublishAfterNewerRequestStarts() {
        val fence = NovaLaunchPreflightRequestFence()
        val older = fence.begin()
        val newer = fence.begin()
        val published = mutableListOf<String>()

        // Deliberately complete the network responses out of order.
        if (fence.owns(newer)) published += "newer"
        if (fence.owns(older)) published += "older"

        assertEquals(listOf("newer"), published)
        assertTrue(fence.owns(newer))
        assertFalse(fence.owns(older))
    }

    @Test
    fun selectionSettleInvalidatesInFlightResponseBeforeReplacementStarts() {
        val fence = NovaLaunchPreflightRequestFence()
        val inFlight = fence.begin()

        fence.invalidate()

        assertFalse(fence.owns(inFlight))
        val replacement = fence.begin()
        assertTrue(fence.owns(replacement))
        assertFalse(fence.owns(inFlight))
    }
}
