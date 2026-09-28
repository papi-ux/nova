package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveAvailability.Status
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import org.junit.Assert.*
import org.junit.Test

class PyroWaveAvailabilityTest {
    @Test fun onlyTheMeasuredComputePathCanEnableTheCurrentRenderer() {
        for (probe in PyroWave.Probe.entries) {
            val expected = when (probe) {
                PyroWave.Probe.COMPUTE -> Status.AVAILABLE
                PyroWave.Probe.FRAGMENT -> Status.COMPUTE_UNAVAILABLE
                PyroWave.Probe.UNUSABLE -> Status.DECODE_UNAVAILABLE
                PyroWave.Probe.UNKNOWN -> Status.CHECKING
            }
            assertEquals(expected, PyroWaveAvailability.evaluate(true, probe))
            assertEquals(Status.LIBRARY_UNAVAILABLE, PyroWaveAvailability.evaluate(false, probe))
        }
    }

    @Test fun unavailableOrUnmeasuredPyrowaveCannotBeSelectedOrLaunched() {
        for (status in Status.entries) {
            val allowed = status == Status.AVAILABLE
            assertEquals(allowed, PyroWaveAvailability.canSelect("forcepyrowave", status))
            assertEquals(allowed, PyroWaveAvailability.canLaunch(FormatOption.FORCE_PYROWAVE, status))
            assertEquals(allowed, status.reasonRes == 0)
        }
    }

    @Test fun standardCodecsRemainAvailableForEveryProbeFailure() {
        for (status in Status.entries) {
            for (value in listOf("auto", "neverh265", "forceh265", "forceav1")) {
                assertTrue(PyroWaveAvailability.canSelect(value, status))
            }
            for (format in FormatOption.entries.filter { it != FormatOption.FORCE_PYROWAVE }) {
                assertTrue(PyroWaveAvailability.canLaunch(format, status))
            }
        }
    }
}
