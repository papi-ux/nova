package com.papi.nova.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorTelemetryUploadGateTest {
    @Test
    fun admitsOnlyOneUploadAtATime() {
        val gate = DoctorTelemetryUploadGate()
        val first = gate.tryAcquire()

        assertNotNull(first)
        assertNull(gate.tryAcquire())
        assertTrue(gate.hasActiveUpload())
        assertTrue(gate.release(first!!))
        assertFalse(gate.hasActiveUpload())
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun disconnectInvalidationAllowsTheNextStreamToUpload() {
        val gate = DoctorTelemetryUploadGate()

        assertNotNull(gate.tryAcquire())
        gate.invalidate()

        assertFalse(gate.hasActiveUpload())
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun staleCompletionCannotReleaseANewerStreamsLease() {
        val gate = DoctorTelemetryUploadGate()
        val oldToken = gate.tryAcquire()!!
        gate.invalidate()
        val newToken = gate.tryAcquire()!!

        assertFalse(gate.release(oldToken))
        assertTrue(gate.hasActiveUpload())
        assertTrue(gate.release(newToken))
        assertFalse(gate.hasActiveUpload())
    }
}
