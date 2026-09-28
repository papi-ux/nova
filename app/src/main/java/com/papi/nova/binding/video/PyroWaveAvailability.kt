package com.papi.nova.binding.video

import android.content.Context
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption

/** Availability of the compute path the Android renderer actually uses. */
object PyroWaveAvailability {
    enum class Status(val reasonRes: Int) {
        CHECKING(R.string.nova_pyrowave_checking),
        AVAILABLE(0),
        LIBRARY_UNAVAILABLE(R.string.nova_pyrowave_library_unavailable),
        DECODE_UNAVAILABLE(R.string.nova_pyrowave_decode_unavailable),
        COMPUTE_UNAVAILABLE(R.string.nova_pyrowave_compute_unavailable),
    }

    fun evaluate(libraryAvailable: Boolean, probe: PyroWave.Probe): Status = when {
        !libraryAvailable -> Status.LIBRARY_UNAVAILABLE
        probe == PyroWave.Probe.COMPUTE -> Status.AVAILABLE
        // A correct fragment decode cannot validate the forced-compute stream renderer.
        probe == PyroWave.Probe.FRAGMENT -> Status.COMPUTE_UNAVAILABLE
        probe == PyroWave.Probe.UNKNOWN -> Status.CHECKING
        else -> Status.DECODE_UNAVAILABLE
    }

    /** Usually warmed by PcView; a cold shortcut must measure before it can launch. */
    fun inspect(context: Context): Status = evaluate(PyroWave.isLibraryAvailable, PyroWave.probe(context))

    fun canSelect(value: String, status: Status): Boolean =
        value != "forcepyrowave" || status == Status.AVAILABLE

    fun canLaunch(format: FormatOption?, status: Status): Boolean =
        format != FormatOption.FORCE_PYROWAVE || status == Status.AVAILABLE

    fun reason(context: Context, status: Status): String =
        if (status == Status.AVAILABLE) "" else context.getString(status.reasonRes)
}
