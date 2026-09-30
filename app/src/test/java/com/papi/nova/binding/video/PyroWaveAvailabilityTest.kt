package com.papi.nova.binding.video

import com.papi.nova.binding.video.PyroWaveAvailability.Status
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import org.junit.Assert.*
import org.junit.Test

class PyroWaveAvailabilityTest {
    @Test fun missingShieldFeaturesDisableSelectionAndSavedPyrowaveLaunches() {
        val mask = PyroWaveGpuFeatures.STORAGE_8BIT or PyroWaveGpuFeatures.TIMELINE_SEMAPHORE
        assertEquals(mask, PyroWaveGpuFeatures.fromNativeFailure(-268))
        val status = PyroWaveAvailability.evaluate(true, PyroWave.Probe.UNUSABLE, mask)
        assertEquals(Status.GPU_FEATURES_UNAVAILABLE, status)
        assertFalse(PyroWaveAvailability.canSelect("forcepyrowave", status))
        assertFalse(PyroWaveAvailability.canLaunch(FormatOption.FORCE_PYROWAVE, status))
        assertTrue(PyroWaveAvailability.canSelect("forceh265", status))
    }

    @Test fun unspecifiedNativeFailuresDoNotInventMissingFeatures() {
        for (result in listOf(Int.MIN_VALUE, -320, -256, -2, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(0, PyroWaveGpuFeatures.fromNativeFailure(result))
        }
        assertEquals(63, PyroWaveGpuFeatures.fromNativeFailure(-319))
        assertEquals(Status.DECODE_UNAVAILABLE, PyroWaveAvailability.evaluate(true, PyroWave.Probe.UNUSABLE))
    }

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
