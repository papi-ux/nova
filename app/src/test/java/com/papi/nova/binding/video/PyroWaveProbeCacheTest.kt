package com.papi.nova.binding.video

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PyroWaveProbeCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("probe-cache-test", Context.MODE_PRIVATE)

    @Test fun oldGenericFailureMustBeMeasuredAgain() {
        prefs.edit().clear().putString("probe_fingerprint", "shield")
            .putInt("probe_result", -1).commit()
        assertNull(PyroWave.readProbeCache(prefs, "shield"))
    }

    @Test fun diagnosedFailureSurvivesRestartAndDriverChangeInvalidatesIt() {
        val expected = PyroWave.CachedProbe(PyroWave.Probe.UNUSABLE, 12)
        PyroWave.writeProbeCache(prefs, "shield", expected)
        assertEquals(expected, PyroWave.readProbeCache(prefs, "shield"))
        assertNull(PyroWave.readProbeCache(prefs, "new-driver"))
        val status = PyroWaveAvailability.evaluate(true, expected.probe, expected.missingFeatures)
        val reason = PyroWaveAvailability.reason(context, status, expected.missingFeatures)
        assertTrue(reason.contains("8-bit storage"))
        assertTrue(reason.contains("timeline semaphores"))
        assertFalse(reason.contains("16-bit storage"))
        assertFalse(reason.contains("subgroup"))
    }

    @Test fun laterSuccessClearsTheStoredMissingFeatures() {
        PyroWave.writeProbeCache(prefs, "shield", PyroWave.CachedProbe(PyroWave.Probe.UNUSABLE, 12))
        val expected = PyroWave.CachedProbe(PyroWave.Probe.COMPUTE, 0)
        PyroWave.writeProbeCache(prefs, "shield", expected)
        assertEquals(expected, PyroWave.readProbeCache(prefs, "shield"))
    }

    @Test fun inconsistentOrUnknownCachedDiagnosisIsNotReused() {
        PyroWave.writeProbeCache(prefs, "shield", PyroWave.CachedProbe(PyroWave.Probe.COMPUTE, 12))
        assertNull(PyroWave.readProbeCache(prefs, "shield"))
        PyroWave.writeProbeCache(prefs, "shield", PyroWave.CachedProbe(PyroWave.Probe.UNUSABLE, 64))
        assertNull(PyroWave.readProbeCache(prefs, "shield"))
    }
}
