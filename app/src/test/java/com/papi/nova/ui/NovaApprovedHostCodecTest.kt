package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.binding.video.PyroWave
import com.papi.nova.binding.video.PyroWaveAvailability
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NovaCompatiblePyrowaveShadow::class])
class NovaApprovedHostCodecTest {
    private fun hostRow(context: Context, refusal: PolarisCapabilities.PyrowaveUnavailable,
                        onSelect: (String?) -> Unit) = novaPlaySetupHostCodecRow(context,
        "forcepyrowave", FormatOption.FORCE_PYROWAVE, { PyroWaveAvailability.Status.AVAILABLE },
        { refusal }, { true }, onSelect = onSelect)
    @Test fun currentHostCaptureRefusalIsVisibleAndNeitherExplicitNorInheritedPyrowaveCanCommit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals(PyroWaveAvailability.Status.AVAILABLE, PyroWaveAvailability.inspect(context))
            var writes = 0
            val row = hostRow(context, PolarisCapabilities.PyrowaveUnavailable("capture_cpu", "This host needs GPU-native capture")) { writes++ }
            val pyro = row.options.first { it.label.contains("PyroWave") }
            assertEquals("This host needs GPU-native capture", pyro.consequence)
            assertEquals("This host needs GPU-native capture", row.caption)
            assertFalse(pyro.enabled); assertNull(pyro.onSelect)
            assertFalse(row.options.first().enabled); assertNull(row.options.first().onSelect)
            pyro.onSelect?.invoke(); row.options.first().onSelect?.invoke()
            assertEquals(0, writes)
            row.options.first { it.label == context.getString(com.papi.nova.R.string.videoformat_auto) }.onSelect!!.invoke()
            assertEquals(1, writes)

    }
    @Test fun hostRefusalAndChangedHostInvalidateAlreadyRenderedCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var refusal: PolarisCapabilities.PyrowaveUnavailable? = null
        var current = true
        var writes = 0
        val row = novaPlaySetupHostCodecRow(context, null, FormatOption.FORCE_PYROWAVE,
            { PyroWaveAvailability.Status.AVAILABLE }, { refusal }, { current }, onSelect = { writes++ })
        val pyro = row.options.first { it.label.contains("PyroWave") }
        assertTrue(pyro.enabled); assertTrue(row.options.first().enabled)
        refusal = PolarisCapabilities.PyrowaveUnavailable("capture_cpu", "GPU capture is unavailable")
        pyro.onSelect!!.invoke(); row.options.first().onSelect!!.invoke()
        assertEquals("a new refusal retires callbacks from the old row", 0, writes)
        refusal = null; current = false
        row.options.first { it.label == context.getString(com.papi.nova.R.string.videoformat_auto) }.onSelect!!.invoke()
        assertEquals("a row belonging to another host/generation cannot save even Auto", 0, writes)
        current = true; pyro.onSelect!!.invoke(); assertEquals(1, writes)
    }

}

@Implements(PyroWave::class, isInAndroidSdk = false)
class NovaCompatiblePyrowaveShadow {
    companion object {
        @JvmStatic @Implementation fun isLibraryAvailable(): Boolean = true
        @JvmStatic @Implementation fun probe(context: Context): PyroWave.Probe = PyroWave.Probe.COMPUTE
    }
}
