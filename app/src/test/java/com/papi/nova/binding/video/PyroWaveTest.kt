package com.papi.nova.binding.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The native library is not present in a JVM unit test, so what is testable here is the promise the
 * object makes when it is missing: say so, and do not throw. A device proves the rest.
 */
class PyroWaveTest {
    @Test
    fun anAbsentLibraryIsReportedRatherThanThrown() {
        // Loading fails under the JVM runner, which is the same path an ABI without the library
        // takes on a device. Callers get a clean no.
        assertEquals(false, PyroWave.isLibraryAvailable)
        assertEquals("", PyroWave.apiVersion())
    }
}
